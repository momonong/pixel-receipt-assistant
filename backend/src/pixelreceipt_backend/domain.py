"""Integer-only central review, mirroring Android's reconciler and expense rules.

TWD inputs are whole yuan. A printed line total is never multiplied by quantity.
Included adjustments are evidence only. Tolerance never changes anyone's burden.
"""

import hashlib
import json

from .ledger_models import ReviewDocument
from .models import LONG_MAX, Candidate

RULESET = "pixelreceipt-central-ledger-1"
PURPOSES = ("self", "advance", "gift")  # Android's deterministic largest-remainder tie order.


def distribute(amount: int, weights: dict[str, int]) -> dict[str, int]:
    total = sum(weights.values())
    if amount == 0:
        return dict.fromkeys(PURPOSES, 0)
    if total == 0:
        raise ValueError("zero_allocation_basis")
    divisions = {purpose: divmod(amount * weights[purpose], total) for purpose in PURPOSES}
    shares = {purpose: division[0] for purpose, division in divisions.items()}
    remaining = amount - sum(shares.values())
    ranked = sorted(PURPOSES, key=lambda purpose: (-divisions[purpose][1], PURPOSES.index(purpose)))
    for purpose in ranked[:remaining]:
        shares[purpose] += 1
    return shares


def calculate(candidate: Candidate, document: ReviewDocument | None) -> dict:
    payload = {
        "ruleset": RULESET,
        "candidate": candidate.model_dump(mode="json", by_alias=True),
        "review_document": document.model_dump(mode="json", by_alias=True) if document else None,
    }
    basis = hashlib.sha256(
        json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()
    ).hexdigest()
    blockers = []

    def block(code, **position):
        blockers.append({"code": code, **position})

    if document is None or document.assembly_status != "complete_by_user":
        block("receipt_completeness_unconfirmed")
    if candidate.merchant is None:
        block("merchant_unknown")
    if candidate.receipt_date is None:
        block("receipt_date_unknown")
    if candidate.printed_total_yuan is None:
        block("printed_total_unknown")
    if not candidate.items:
        block("no_items")
    money_known = candidate.printed_total_yuan is not None and bool(candidate.items)
    for index, item in enumerate(candidate.items):
        for value, code in (
            (item.raw_name, "item_name_unknown"),
            (item.quantity, "item_quantity_unknown"),
            (item.printed_line_total_yuan, "item_amount_unknown"),
        ):
            if value is None:
                block(code, item_position=index)
        if item.printed_line_total_yuan is None:
            money_known = False
    for index, adjustment in enumerate(candidate.adjustments):
        for value, code in (
            (adjustment.amount_yuan, "adjustment_amount_unknown"),
            (adjustment.direction, "adjustment_direction_unknown"),
            (adjustment.scope, "adjustment_scope_unknown"),
            (adjustment.amount_effect, "adjustment_effect_unknown"),
        ):
            if value is None:
                block(code, adjustment_position=index)
                money_known = False

    computed = None
    difference = None
    status = "indeterminate"
    if money_known:
        computed = sum(item.printed_line_total_yuan for item in candidate.items)
        # All intermediate sums are arbitrary-precision integers, then bounded before persistence.
        for adjustment in candidate.adjustments:
            if adjustment.amount_effect == "separate_from_line_totals":
                computed += adjustment.amount_yuan * (1 if adjustment.direction == "add" else -1)
        if computed > LONG_MAX or computed < 0:
            block("computed_total_out_of_range")
            computed = None
            status = "invalid"
        else:
            difference = computed - candidate.printed_total_yuan
            if difference == 0:
                status = "balanced"
            elif abs(difference) <= 1:
                status = "within_tolerance"
                if (
                    document is None
                    or document.tolerance_acknowledgement != "accept_within_tolerance"
                ):
                    block("tolerance_acknowledgement_required")
            else:
                status = "unbalanced"
                block("receipt_total_mismatch")

    expense_block_start = len(blockers)
    line_decisions = {d.item_position: d for d in document.line_decisions} if document else {}
    adjustment_decisions = (
        {d.adjustment_position: d for d in document.adjustment_decisions} if document else {}
    )
    line_shares = {}
    line_members = {}
    totals = dict.fromkeys(PURPOSES, 0)
    for index, item in enumerate(candidate.items):
        decision = line_decisions.get(index)
        if decision is None:
            block("item_purpose_unconfirmed", item_position=index)
            continue
        if item.quantity is None or item.printed_line_total_yuan is None:
            continue
        if decision.method == "whole_line":
            shares = {
                p: item.printed_line_total_yuan if p == decision.whole_purpose else 0
                for p in PURPOSES
            }
            members = {decision.whole_purpose}
        else:
            quantities = decision.quantities.model_dump()
            if sum(quantities.values()) != item.quantity:
                block("item_quantity_allocation_mismatch", item_position=index)
                continue
            members = {p for p in PURPOSES if quantities[p] > 0}
            if decision.method == "by_quantity":
                shares = distribute(item.printed_line_total_yuan, quantities)
            else:
                shares = decision.amounts_yuan.model_dump()
                if sum(shares.values()) != item.printed_line_total_yuan:
                    block("item_amount_allocation_mismatch", item_position=index)
                    continue
                if any(shares[p] and p not in members for p in PURPOSES):
                    block("item_allocation_without_quantity", item_position=index)
                    continue
        line_shares[index] = shares
        line_members[index] = members
        for purpose in PURPOSES:
            totals[purpose] += shares[purpose]

    adjustment_shares = {}
    for index, adjustment in enumerate(candidate.adjustments):
        if adjustment.amount_effect == "included_in_line_totals":
            continue
        if None in (
            adjustment.amount_yuan,
            adjustment.direction,
            adjustment.scope,
            adjustment.amount_effect,
        ):
            continue
        decision = adjustment_decisions.get(index)
        if decision is None:
            block("adjustment_allocation_unconfirmed", adjustment_position=index)
            continue
        positions = (
            list(range(len(candidate.items)))
            if adjustment.scope == "receipt"
            else adjustment.item_positions
        )
        if any(position not in line_shares for position in positions):
            block("adjustment_item_purpose_unconfirmed", adjustment_position=index)
            continue
        weights = {p: sum(line_shares[position][p] for position in positions) for p in PURPOSES}
        eligible = set().union(*(line_members[position] for position in positions))
        if decision.method == "proportional_amount":
            try:
                shares = distribute(adjustment.amount_yuan, weights)
            except ValueError:
                block("zero_allocation_basis", adjustment_position=index)
                continue
        else:
            shares = decision.amounts_yuan.model_dump()
            if sum(shares.values()) != adjustment.amount_yuan:
                block("adjustment_allocation_mismatch", adjustment_position=index)
                continue
            if any(shares[p] and p not in eligible for p in PURPOSES):
                block("adjustment_allocation_outside_scope", adjustment_position=index)
                continue
        adjustment_shares[index] = shares
        for purpose in PURPOSES:
            totals[purpose] += shares[purpose] * (1 if adjustment.direction == "add" else -1)

    if any(value < 0 for value in totals.values()):
        block("negative_purpose_burden")
    if any(value > LONG_MAX for value in totals.values()) or sum(totals.values()) > LONG_MAX:
        block("expense_total_out_of_range")
    ready = not blockers
    expenses_ready = (
        money_known
        and len(line_shares) == len(candidate.items)
        and len(blockers) == expense_block_start
    )
    allocated = sum(totals.values()) if expenses_ready else None
    expense_difference = allocated - candidate.printed_total_yuan if allocated is not None else None
    return {
        "ruleset": RULESET,
        "basis_sha256": basis,
        "ready": ready,
        "blockers": blockers,
        "reconciliation_status": status,
        "tolerance_yuan": 1,
        "computed_total_yuan": computed,
        "printed_total_yuan": candidate.printed_total_yuan,
        "allocated_total_yuan": allocated,
        "difference_yuan": expense_difference,
        "reconciliation_difference_yuan": difference,
        "expenses_ready": expenses_ready,
        "self_yuan": totals["self"] if expenses_ready else None,
        "gift_yuan": totals["gift"] if expenses_ready else None,
        "advance_yuan": totals["advance"] if expenses_ready else None,
        "personal_yuan": totals["self"] + totals["gift"] if expenses_ready else None,
        "line_shares": [
            {"item_position": index, **{p + "_yuan": shares[p] for p in PURPOSES}}
            for index, shares in line_shares.items()
        ]
        if expenses_ready
        else [],
        "adjustment_shares": [
            {"adjustment_position": index, **{p + "_yuan": shares[p] for p in PURPOSES}}
            for index, shares in adjustment_shares.items()
        ]
        if expenses_ready
        else [],
    }
