"""Exact price analysis without changing the receipt ledger or purpose decisions."""

import hashlib
import json

from .models import LONG_MAX, Candidate
from .pricing_models import PriceDocument

PRICE_RULESET = "pixelreceipt-product-price-1"


class InvalidPriceReview(ValueError):
    def __init__(self, code="invalid_price_allocation"):
        self.code = code
        super().__init__(code)


def unit_price(amount: int, quantity: int) -> dict:
    return {
        "numerator_yuan": amount,
        "denominator": quantity,
        "display_yuan": str(amount // quantity)
        if amount % quantity == 0
        else f"{amount} / {quantity}",
    }


def line_distribution(amount: int, positions: list[int], weights: dict[int, int]) -> dict[int, int]:
    total = sum(weights.values())
    if total == 0:
        raise InvalidPriceReview("zero_price_allocation_basis")
    if amount == 0:
        return dict.fromkeys(positions, 0)
    divisions = {position: divmod(amount * weights[position], total) for position in positions}
    result = {position: divisions[position][0] for position in positions}
    remaining = amount - sum(result.values())
    # Original line position determines equal-remainder order, independent of request order.
    ranked = sorted(positions, key=lambda position: (-divisions[position][1], position))
    for position in ranked[:remaining]:
        result[position] += 1
    return result


def price_lines(
    candidate: Candidate,
    document: PriceDocument,
    products: dict[str, dict],
    *,
    reject_invalid=False,
) -> tuple[list[dict], str]:
    mappings = {mapping.item_position: mapping for mapping in document.item_mappings}
    decisions = {
        decision.adjustment_position: decision for decision in document.adjustment_decisions
    }
    if any(position >= len(candidate.items) for position in mappings):
        raise InvalidPriceReview()
    if any(position >= len(candidate.adjustments) for position in decisions):
        raise InvalidPriceReview()
    if any(mapping.product_id not in products for mapping in document.item_mappings):
        raise InvalidPriceReview("invalid_product_reference")
    # Only confirmed receipt snapshots reach this domain. Unknown financial facts fail closed.
    if any(
        item.quantity is None or item.printed_line_total_yuan is None for item in candidate.items
    ):
        raise InvalidPriceReview()
    paid = {index: item.printed_line_total_yuan for index, item in enumerate(candidate.items)}
    unknown = {index: [] for index in range(len(candidate.items))}
    allocations = {index: [] for index in range(len(candidate.items))}
    for index, adjustment in enumerate(candidate.adjustments):
        decision = decisions.get(index)
        if adjustment.amount_effect == "included_in_line_totals":
            if decision is not None:
                raise InvalidPriceReview()
            continue
        if None in (
            adjustment.amount_yuan,
            adjustment.direction,
            adjustment.scope,
            adjustment.amount_effect,
        ):
            raise InvalidPriceReview()
        positions = (
            list(range(len(candidate.items)))
            if adjustment.scope == "receipt"
            else list(adjustment.item_positions)
        )
        if len(positions) == 1:
            shares = {positions[0]: adjustment.amount_yuan}
            method = "single_line_scope"
            # Accept a consistent explicit human decision, but its arithmetic cannot differ.
            if decision is not None:
                if decision.method == "explicit_amounts":
                    supplied = {a.item_position: a.amount_yuan for a in decision.amounts_by_item}
                    if supplied != shares:
                        raise InvalidPriceReview()
        elif decision is None:
            for position in positions:
                unknown[position].append("shared_adjustment_unallocated")
            continue
        elif decision.method == "proportional_line_amount":
            shares = line_distribution(
                adjustment.amount_yuan,
                positions,
                {p: candidate.items[p].printed_line_total_yuan for p in positions},
            )
            method = decision.method
        else:
            shares = {a.item_position: a.amount_yuan for a in decision.amounts_by_item}
            if set(shares) != set(positions) or sum(shares.values()) != adjustment.amount_yuan:
                raise InvalidPriceReview()
            method = decision.method
        for position, amount in shares.items():
            paid[position] += amount * (1 if adjustment.direction == "add" else -1)
            allocations[position].append(
                {
                    "adjustment_position": index,
                    "method": method,
                    "direction": adjustment.direction,
                    "amount_yuan": amount,
                }
            )

    lines = []
    for index, item in enumerate(candidate.items):
        mapping = mappings.get(index)
        reasons = list(dict.fromkeys(unknown[index]))
        if not unknown[index] and not 0 <= paid[index] <= LONG_MAX:
            if reject_invalid:
                raise InvalidPriceReview(
                    "price_amount_out_of_range"
                    if paid[index] > LONG_MAX
                    else "invalid_price_allocation"
                )
            reasons.append(
                "price_amount_out_of_range"
                if paid[index] > LONG_MAX
                else "negative_paid_line_total"
            )
        financial_known = not reasons
        if mapping is None:
            reasons.insert(0, "product_mapping_unconfirmed")
        lines.append(
            {
                "item_position": index,
                "product_id": mapping.product_id if mapping else None,
                "product": products[mapping.product_id] if mapping else None,
                "raw_name": item.raw_name,
                "quantity": item.quantity,
                "printed_line_total_yuan": item.printed_line_total_yuan,
                "paid_line_total_yuan": paid[index] if financial_known else None,
                "printed_unit_price": unit_price(item.printed_line_total_yuan, item.quantity),
                "paid_unit_price": unit_price(paid[index], item.quantity)
                if financial_known
                else None,
                "comparison_eligible": financial_known and mapping is not None,
                "unknown_reasons": reasons,
                "allocations": allocations[index],
            }
        )
    basis_payload = {
        "ruleset": PRICE_RULESET,
        "candidate": candidate.model_dump(mode="json", by_alias=True),
        "document": document.model_dump(mode="json"),
    }
    basis = hashlib.sha256(
        json.dumps(
            basis_payload, ensure_ascii=False, sort_keys=True, separators=(",", ":")
        ).encode()
    ).hexdigest()
    return lines, basis
