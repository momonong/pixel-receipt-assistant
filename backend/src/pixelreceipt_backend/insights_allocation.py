"""Attribute existing burdens to analysis rows without changing financial facts."""

from .models import LONG_MAX
from .store import StoreError

PURPOSES = ("self", "gift", "advance")


def split_integer(amount, weights):
    """Largest remainders, stable original item order; never floats."""
    total = sum(weights.values())
    if not total:
        return {}
    divisions = {position: divmod(amount * weight, total) for position, weight in weights.items()}
    result = {position: value[0] for position, value in divisions.items()}
    remaining = amount - sum(result.values())
    order = sorted(divisions, key=lambda position: (-divisions[position][1], position))
    for position in order[:remaining]:
        result[position] += 1
    return result


def analysis_lines(entry):
    candidate = entry["snapshot"]["candidate"]
    calculation = entry["snapshot"]["calculation"]
    annotations = {row["item_position"]: row for row in entry["insights"]["document"]["items"]}
    shares = {row["item_position"]: row for row in calculation["line_shares"]}
    lines = []
    for position, _item in enumerate(candidate["items"]):
        fields = annotations.get(position, {})
        line = {
            "position": position,
            "category": fields.get("category", "unknown"),
            "subcategory": fields.get("subcategory"),
            # Meal labels describe food only, including when filtering old annotations.
            "meal": fields.get("meal", "unknown")
            if fields.get("category") == "food"
            else "not_applicable",
            "meal_group": fields.get("meal_group"),
            "missing_name": not bool(entry["display_item_names"][position]),
            # A full discount changes its cost, not whether a personal meal occurred.
            "personal_participation": shares[position]["self_yuan"] + shares[position]["gift_yuan"]
            > 0,
            **{purpose: shares[position][purpose + "_yuan"] for purpose in PURPOSES},
        }
        lines.append(line)
    remainder = dict.fromkeys(PURPOSES, 0)
    for allocation in calculation["adjustment_shares"]:
        adjustment = candidate["adjustments"][allocation["adjustment_position"]]
        if adjustment["amount_effect"] == "included_in_line_totals":
            continue
        positions = (
            list(range(len(lines)))
            if adjustment["scope"] == "receipt"
            else adjustment["item_positions"]
        )
        for purpose in PURPOSES:
            amount = allocation[purpose + "_yuan"]
            weights = {
                position: lines[position][purpose]
                for position in positions
                if lines[position][purpose] > 0
            }
            available = sum(weights.values())
            subtract = adjustment["direction"] == "subtract"
            attributed = min(amount, available) if subtract else amount if available else 0
            for position, share in split_integer(attributed, weights).items():
                lines[position][purpose] += -share if subtract else share
            remainder[purpose] += (amount - attributed) * (-1 if subtract else 1)
    for purpose in PURPOSES:
        if (
            sum(line[purpose] for line in lines) + remainder[purpose]
            != calculation[purpose + "_yuan"]
        ):
            raise StoreError(503, "analysis_burden_integrity_failure")
        if any(not 0 <= line[purpose] <= LONG_MAX for line in lines):
            raise StoreError(503, "analysis_burden_integrity_failure")
    if any(remainder.values()):
        lines.append(
            {
                "position": None,
                "category": "unallocated",
                "subcategory": None,
                "meal": "not_applicable",
                "meal_group": None,
                "missing_name": False,
                "personal_participation": False,
                **remainder,
            }
        )
    for line in lines:
        line["personal"] = line["self"] + line["gift"]
    return lines
