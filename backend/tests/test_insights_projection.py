"""Consumer-visible dimensions must reconcile to actual immutable burden."""

from copy import deepcopy

import pytest

from pixelreceipt_backend.domain import calculate
from pixelreceipt_backend.ledger_models import ReviewDocument
from pixelreceipt_backend.models import LONG_MAX, Candidate
from pixelreceipt_backend.store import StoreError


def example(
    key="first",
    amounts=(100, 50, 30),
    purposes=("self", "gift", "advance"),
    categories=("food", "household", "food"),
    meals=("dinner", "not_applicable", "dinner"),
    receipt_date="2026-11-12",
    adjustments=(),
    city="臺南市",
    meal_group=None,
):
    candidate = Candidate.model_validate(
        {
            "schema": "pixelreceipt-central-candidate-1",
            "currency": "TWD",
            "amount_unit": "TWD_YUAN",
            "merchant": "SYNTHETIC 台北品牌 台南分店",
            "receipt_date": receipt_date,
            "printed_total_yuan": sum(amounts)
            + sum(
                a["amount_yuan"] * (1 if a["direction"] == "add" else -1)
                for a in adjustments
                if a["amount_effect"] == "separate_from_line_totals"
            ),
            "items": [
                {"raw_name": f"合成品項 {i}", "quantity": 1, "printed_line_total_yuan": amount}
                for i, amount in enumerate(amounts)
            ],
            "adjustments": list(adjustments),
        }
    )
    document = ReviewDocument.model_validate(
        {
            "assembly_status": "complete_by_user",
            "line_decisions": [
                {"item_position": i, "method": "whole_line", "whole_purpose": p}
                for i, p in enumerate(purposes)
            ],
            "adjustment_decisions": [
                {"adjustment_position": i, "method": "proportional_amount"}
                for i, a in enumerate(adjustments)
                if a["amount_effect"] == "separate_from_line_totals"
            ],
        }
    )
    result = calculate(candidate, document)
    assert result["ready"], result
    return {
        "id": key,
        "draft_id": key,
        "status": "active",
        "revision": 1,
        "snapshot": {"candidate": candidate.model_dump(by_alias=True), "calculation": result},
        "display_item_names": [item.raw_name for item in candidate.items],
        "missing_item_name_count": 0,
        "insights": {
            "revision": 1,
            "document": {
                "brand": "合成品牌",
                "branch": "合成分店",
                "city": city,
                "district": "東區",
                "address": None,
                "channel": "in_store",
                "transaction_time": "18:30",
                "date_source": "receipt",
                "items": [
                    {
                        "item_position": i,
                        "category": category,
                        "subcategory": None,
                        "meal": meals[i],
                        "meal_group": meal_group,
                    }
                    for i, category in enumerate(categories)
                ],
            },
        },
    }


def project(entries, **query):
    from pixelreceipt_backend.insights_projection import project_insights

    return project_insights(entries, query)


def test_mixed_categories_use_item_burden_and_advance_stays_separate():
    result = project([example()])
    assert result["summary"]["personal_yuan"] == 150
    assert result["summary"]["advance_yuan"] == 30
    groups = {g["key"]: g for g in result["groups"]["categories"]}
    assert groups["food"]["personal_yuan"] == 100
    assert groups["household"]["personal_yuan"] == 50
    assert sum(g["personal_yuan"] for g in groups.values()) == 150
    food = project([example()], category="food")
    assert food["summary"]["personal_yuan"] == 100
    assert food["summary"]["gift_yuan"] == 0
    assert food["summary"]["advance_yuan"] == 30


def test_extra_discount_integer_remainder_and_included_discount_not_deducted_twice():
    adjustments = [
        {
            "raw_label": "合成額外折扣",
            "direction": "subtract",
            "amount_yuan": 3,
            "amount_effect": "separate_from_line_totals",
            "scope": "receipt",
            "item_positions": [],
        },
        {
            "raw_label": "合成內含折扣",
            "direction": "subtract",
            "amount_yuan": 10,
            "amount_effect": "included_in_line_totals",
            "scope": "receipt",
            "item_positions": [],
        },
    ]
    row = example(
        amounts=(100, 100),
        purposes=("self", "self"),
        categories=("food", "household"),
        meals=("lunch", "not_applicable"),
        adjustments=adjustments,
    )
    result = project([row])
    groups = {g["key"]: g["personal_yuan"] for g in result["groups"]["categories"]}
    assert groups == {"food": 98, "household": 99}
    assert result["summary"]["personal_yuan"] == 197
    assert result["summary"]["explicit_discount_yuan"] == 13


def test_unallocatable_negative_adjustment_is_visible_and_reconciles():
    adjustment = {
        "raw_label": "範圍不足的合成折扣",
        "direction": "subtract",
        "amount_yuan": 30,
        "amount_effect": "separate_from_line_totals",
        "scope": "items",
        "item_positions": [0],
    }
    row = example(
        amounts=(10, 100),
        purposes=("self", "self"),
        categories=("food", "household"),
        meals=("dinner", "not_applicable"),
        adjustments=[adjustment],
    )
    result = project([row])
    assert result["summary"]["personal_yuan"] == 80
    assert result["coverage"]["unallocated_personal_yuan"] == -20
    assert sum(g["personal_yuan"] for g in result["groups"]["categories"]) == 80
    assert any(g["key"] == "unallocated" for g in result["groups"]["categories"])


def test_cross_city_meal_and_period_filters_apply_before_entries_pagination():
    from pixelreceipt_backend.insights_projection import filter_insight_entries, identity_key

    rows = [example(key=f"synthetic-{i}") for i in range(23)]
    rows.append(example(key="other-city", city="臺北市"))
    query = dict(
        category="food",
        meal="dinner",
        city_key=identity_key("臺南市"),
        date_from="2026-11-01",
        date_to="2026-11-30",
    )
    assert project(rows, **query)["summary"]["personal_yuan"] == 2300
    page = filter_insight_entries(rows, query, limit=20, offset=20)
    assert page["total_count"] == 23 and len(page["entries"]) == 3
    assert page["entries"][0]["matched_personal_yuan"] == 100
    assert page["filters"]["meal"] == "dinner"


def test_meal_count_is_not_item_count_and_explicit_same_meal_can_merge():
    one = example(
        amounts=(60, 40),
        purposes=("self", "self"),
        categories=("food", "food"),
        meals=("lunch", "lunch"),
        meal_group="合成一起吃",
    )
    two = example(
        key="second",
        amounts=(30,),
        purposes=("self",),
        categories=("food",),
        meals=("lunch",),
        meal_group="合成一起吃",
    )
    result = project([one, two])
    meal = next(g for g in result["groups"]["meals"] if g["key"] == "lunch")
    assert meal["meal_count"] == 1
    assert meal["average_yuan"] == {"numerator": 130, "denominator": 1}
    assert meal["item_count"] == 3
    two["snapshot"]["candidate"]["receipt_date"] = "2026-11-13"
    assert (
        next(g for g in project([one, two])["groups"]["meals"] if g["key"] == "lunch")["meal_count"]
        == 2
    )


def test_unknown_and_undated_are_visible_without_inventing_city_or_time():
    row = example(amounts=(100,), purposes=("self",), categories=("unknown",), meals=("unknown",))
    row["snapshot"]["candidate"]["receipt_date"] = None
    row["insights"]["document"].update(
        city=None, transaction_time=None, date_source="default_today"
    )
    result = project([row])
    assert result["summary"]["personal_yuan"] == 100
    assert result["series"] == []
    assert result["coverage"]["uncategorized_personal_yuan"] == 100
    assert result["coverage"]["unknown_city_personal_yuan"] == 100
    assert result["coverage"]["undated_personal_yuan"] == 100
    assert result["groups"]["times"][0]["key"] == "unknown"
    assert (
        project([row], date_from="2026-11-01", date_to="2026-11-30")["summary"]["personal_yuan"]
        == 0
    )


def test_comparison_same_filters_zero_baseline_and_negative_delta():
    current = example(
        key="now", amounts=(100,), purposes=("self",), categories=("food",), meals=("lunch",)
    )
    previous = deepcopy(current)
    previous["id"] = "before"
    previous["snapshot"]["candidate"]["receipt_date"] = "2026-10-12"
    previous["snapshot"]["calculation"]["line_shares"][0]["self_yuan"] = 150
    previous["snapshot"]["calculation"].update(self_yuan=150, personal_yuan=150)
    result = project(
        [current, previous],
        category="food",
        date_from="2026-11-01",
        date_to="2026-11-30",
        compare_from="2026-10-01",
        compare_to="2026-10-31",
    )
    assert result["comparison"]["delta_personal_yuan"] == -50
    assert result["comparison"]["personal_yuan"] == 150


def test_voided_never_counted_and_large_integer_overflow_is_explicit():
    maxrow = example(
        amounts=(LONG_MAX,), purposes=("self",), categories=("food",), meals=("unknown",)
    )
    voided = example(key="voided")
    voided["status"] = "voided"
    assert project([maxrow, voided])["summary"]["personal_yuan"] == LONG_MAX
    extra = example(
        key="extra", amounts=(1,), purposes=("self",), categories=("food",), meals=("unknown",)
    )
    with pytest.raises(StoreError, match="summary_amount_out_of_range"):
        project([maxrow, extra])


@pytest.mark.parametrize(
    "query",
    [
        {"meal": "dinner&token=x"},
        {"city_key": "台南"},
        {"date_from": "2026-02-30"},
        {"compare_from": "2026-10-01"},
        {"date_from": "0001-01-01", "date_to": "9999-12-31", "bucket": "day"},
        {"undated_only": True, "date_from": "2026-01-01"},
    ],
)
def test_invalid_filter_or_unbounded_series_rejected(query):
    with pytest.raises((StoreError, ValueError)):
        project([], **query)


def test_new_insights_route_is_authenticated(client):
    assert client.get("/v1/insights").status_code == 200
    del client.headers["Authorization"]
    assert client.get("/v1/insights").status_code == 401


def test_original_merchant_filter_does_not_require_brand_mapping():
    from pixelreceipt_backend.insights_projection import identity_key

    one = example(key="original-one")
    two = example(key="original-two")
    one["insights"]["document"].update(brand=None, branch=None)
    two["insights"]["document"].update(brand=None, branch=None)
    two["snapshot"]["candidate"]["merchant"] = "另一間合成原始店家"
    result = project([one, two], merchant_key=identity_key("另一間合成原始店家"))
    assert result["summary"]["personal_yuan"] == 150
    assert result["summary"]["receipt_count"] == 1


def test_city_typographic_alias_does_not_split_same_city_or_rewrite_source():
    one = example(key="traditional", city="臺南市")
    two = example(key="common-spelling", city="台南市")
    result = project([one, two])
    assert len(result["groups"]["cities"]) == 1
    assert result["groups"]["cities"][0]["personal_yuan"] == 300
    assert len(result["groups"]["districts"]) == 1
    assert two["insights"]["document"]["city"] == "台南市"


@pytest.mark.parametrize("other_category,meal", [("household", "dinner"), ("unknown", "unknown")])
def test_only_food_enters_meal_amount_and_filter(other_category, meal):
    entry = example(
        amounts=(100, 50),
        purposes=("self", "self"),
        categories=("food", other_category),
        meals=(meal, meal),
    )
    report = project([entry])
    dinner = next(group for group in report["groups"]["meals"] if group["key"] == meal)
    assert dinner["personal_yuan"] == 100
    assert dinner["average_yuan"] == {"numerator": 100, "denominator": 1}
    assert project([entry], meal=meal)["summary"]["personal_yuan"] == 100
    assert sum(group["personal_yuan"] for group in report["groups"]["meals"]) == 150
    assert entry["insights"]["document"]["items"][1]["meal"] == meal


def test_partial_shared_meal_label_never_splits_a_receipts_meal_even_with_item_filter():
    one = example(
        amounts=(100, 50),
        purposes=("self", "self"),
        categories=("food", "food"),
        meals=("dinner", "dinner"),
    )
    one["insights"]["document"]["items"][0]["meal_group"] = "合成一起吃"
    two = example(
        key="other-receipt",
        amounts=(30,),
        purposes=("gift",),
        categories=("food",),
        meals=("dinner",),
        meal_group="合成一起吃",
    )
    group = next(
        group for group in project([one, two])["groups"]["meals"] if group["key"] == "dinner"
    )
    assert group["meal_count"] == 1
    assert group["average_yuan"] == {"numerator": 180, "denominator": 1}


def test_conflicting_shared_labels_do_not_pick_one_or_split_the_original_meal():
    one = example(
        amounts=(100, 50),
        purposes=("self", "self"),
        categories=("food", "food"),
        meals=("dinner", "dinner"),
    )
    one["insights"]["document"]["items"][0]["meal_group"] = "合成A"
    one["insights"]["document"]["items"][1]["meal_group"] = "合成B"
    two = example(
        key="other-receipt",
        amounts=(30,),
        purposes=("self",),
        categories=("food",),
        meals=("dinner",),
        meal_group="合成A",
    )
    group = next(
        group for group in project([one, two])["groups"]["meals"] if group["key"] == "dinner"
    )
    assert group["meal_count"] == 2


def test_fully_discounted_personal_food_is_a_zero_cost_meal_but_advance_only_is_not():
    discount = {
        "raw_label": "合成全額折扣",
        "direction": "subtract",
        "amount_yuan": 100,
        "amount_effect": "separate_from_line_totals",
        "scope": "receipt",
        "item_positions": [],
    }
    free = example(
        amounts=(100,),
        purposes=("self",),
        categories=("food",),
        meals=("dinner",),
        adjustments=[discount],
    )
    advance = example(
        key="advance-only",
        amounts=(30,),
        purposes=("advance",),
        categories=("food",),
        meals=("dinner",),
    )
    group = next(
        group for group in project([free, advance])["groups"]["meals"] if group["key"] == "dinner"
    )
    assert group["personal_yuan"] == 0
    assert group["meal_count"] == 1
    assert group["average_yuan"] == {"numerator": 0, "denominator": 1}
