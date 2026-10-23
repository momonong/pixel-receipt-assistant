"""Human identity, separate price allocation and exact full-scope analysis."""

from concurrent.futures import ThreadPoolExecutor
from copy import deepcopy

import pytest

from pixelreceipt_backend.analysis_store import AnalysisStore
from pixelreceipt_backend.pricing_models import ReviewPrices
from pixelreceipt_backend.store import Store, StoreError

from .conftest import TOKEN
from .test_drafts import create


def product(client, key="tea", **overrides):
    body = {
        "display_name": "合成茶",
        "brand": "合成品牌",
        "specification": "500mL",
        "purchase_unit": "瓶",
        "content_quantity": {"value_decimal": "500", "unit": "ml"},
    }
    body.update(overrides)
    response = client.post("/v1/products", json=body, headers={"Idempotency-Key": key})
    assert response.status_code in {200, 201}, response.text
    return response.json()


def receipt(
    client,
    body,
    *,
    key="receipt",
    amounts=(100,),
    quantities=None,
    adjustments=None,
    merchant="合成甲店",
    receipt_date="2026-10-20",
    total=None,
    decisions=None,
    expense_decisions=None,
):
    body = deepcopy(body)
    candidate = body["candidate"]
    seed = candidate["items"][0]
    candidate["items"] = [
        {**seed, "quantity": quantities[i] if quantities else 1, "printed_line_total_yuan": amount}
        for i, amount in enumerate(amounts)
    ]
    candidate["adjustments"] = adjustments or []
    computed = sum(amounts) + sum(
        a["amount_yuan"] * (1 if a["direction"] == "add" else -1)
        for a in candidate["adjustments"]
        if a["amount_effect"] == "separate_from_line_totals"
    )
    candidate.update(
        merchant=merchant,
        receipt_date=receipt_date,
        printed_total_yuan=computed if total is None else total,
    )
    draft = create(client, body, key).json()
    review = {
        "expected_revision": 1,
        "candidate": candidate,
        "assembly_status": "complete_by_user",
        "line_decisions": decisions
        or [
            {"item_position": i, "method": "whole_line", "whole_purpose": "self"}
            for i in range(len(amounts))
        ],
        "adjustment_decisions": expense_decisions
        if expense_decisions is not None
        else [
            {"adjustment_position": i, "method": "proportional_amount"}
            for i, a in enumerate(candidate["adjustments"])
            if a["amount_effect"] == "separate_from_line_totals"
        ],
        "tolerance_acknowledgement": "accept_within_tolerance"
        if total is not None and total != computed
        else "none",
    }
    reviewed = client.put(f"/v2/drafts/{draft['id']}/review", json=review)
    assert reviewed.status_code == 200, reviewed.text
    assert reviewed.json()["calculation"]["ready"], reviewed.text
    response = client.post(
        f"/v2/drafts/{draft['id']}/confirm",
        json={"expected_revision": 2},
        headers={"Idempotency-Key": "confirm-" + key},
    )
    assert response.status_code == 201, response.text
    return response.json()


def adjustment(
    amount,
    *,
    scope="receipt",
    positions=None,
    effect="separate_from_line_totals",
    direction="subtract",
):
    return {
        "raw_label": "合成活動",
        "amount_yuan": amount,
        "scope": scope,
        "item_positions": positions or [],
        "amount_effect": effect,
        "direction": direction,
    }


def review_prices(client, ledger, product_id=None, *, positions=(0,), adjustments=None, revision=0):
    body = {
        "expected_revision": revision,
        "item_mappings": [
            {
                "item_position": position,
                "product_id": product_id,
                "unit_match": "confirmed_same_product_and_unit",
            }
            for position in positions
        ]
        if product_id
        else [],
        "adjustment_decisions": adjustments or [],
    }
    return client.put(f"/v1/ledger/{ledger['id']}/price-review", json=body)


def test_product_identity_is_immutable_idempotent_literal_search_and_reviewer_only(client):
    first = product(client)
    assert product(client) == first
    other = product(
        client,
        "two-litre",
        specification="2L",
        content_quantity={"value_decimal": "2", "unit": "l"},
    )
    assert other["id"] != first["id"]
    assert client.get("/v1/products?limit=1").json()["total_count"] == 2
    assert client.get("/v1/products", params={"query": "500mL"}).json()["products"] == [first]
    assert client.get("/v1/products", params={"query": "%"}).json()["products"] == []
    body = {key: value for key, value in first.items() if key not in {"id", "created_at"}}
    body["specification"] = "DIFFERENT"
    assert (
        client.post("/v1/products", json=body, headers={"Idempotency-Key": "tea"}).status_code
        == 409
    )
    assert client.put(f"/v1/products/{first['id']}", json=body).status_code == 404
    client.headers["Authorization"] = "Bearer " + TOKEN
    assert (
        client.post("/v1/products", json=body, headers={"Idempotency-Key": "forbidden"}).status_code
        == 403
    )
    assert client.get("/v1/products").status_code == 200


def test_unmapped_names_never_auto_merge_exact_189_over_2_and_revoke_history(client, body, store):
    p = product(client)
    ledger = receipt(client, body, amounts=(189,), quantities=(2,))
    original = deepcopy(ledger["snapshot"])
    unreviewed = client.get(f"/v1/ledger/{ledger['id']}/price-review").json()
    assert unreviewed["revision"] == 0
    assert unreviewed["line_prices"][0]["paid_unit_price"] == {
        "numerator_yuan": 189,
        "denominator": 2,
        "display_yuan": "189 / 2",
    }
    assert not unreviewed["line_prices"][0]["comparison_eligible"]
    assert client.get(f"/v1/products/{p['id']}/prices").json()["observations"] == []
    reviewed = review_prices(client, ledger, p["id"])
    assert reviewed.status_code == 200, reviewed.text
    assert reviewed.json()["revision"] == 1
    prices = client.get(f"/v1/products/{p['id']}/prices").json()
    assert prices["total_count"] == 1
    assert prices["lowest_paid_observation"]["paid_line_total_yuan"] == 189
    revoked = review_prices(client, ledger, revision=1)
    assert revoked.status_code == 200
    assert revoked.json()["line_prices"][0]["product_id"] is None
    assert client.get(f"/v1/products/{p['id']}/prices").json()["observations"] == []
    history = client.get(f"/v1/ledger/{ledger['id']}/price-review/history").json()["revisions"]
    assert [r["revision"] for r in history] == [1, 2]
    assert history[0]["item_mappings"][0]["product_id"] == p["id"]
    assert history[1]["item_mappings"] == []
    assert client.get(f"/v1/ledger/{ledger['id']}").json()["snapshot"] == original
    assert AnalysisStore(Store(store.data_dir)).get_price_review(ledger["id"]) == revoked.json()


def test_shared_coupon_needs_independent_product_allocation_and_remainder_is_stable(client, body):
    p = product(client)
    ledger = receipt(client, body, amounts=(10, 10), adjustments=[adjustment(3)])
    reviewed = review_prices(client, ledger, p["id"], positions=(0, 1)).json()
    assert all(line["paid_line_total_yuan"] is None for line in reviewed["line_prices"])
    assert all(
        "shared_adjustment_unallocated" in line["unknown_reasons"]
        for line in reviewed["line_prices"]
    )
    assert client.get(f"/v1/products/{p['id']}/prices").json()["lowest_paid_observation"] is None
    reviewed = review_prices(
        client,
        ledger,
        p["id"],
        positions=(0, 1),
        revision=1,
        adjustments=[{"adjustment_position": 0, "method": "proportional_line_amount"}],
    ).json()
    assert [line["paid_line_total_yuan"] for line in reviewed["line_prices"]] == [8, 9]
    assert sum(line["allocations"][0]["amount_yuan"] for line in reviewed["line_prices"]) == 3
    assert client.get(f"/v1/ledger/{ledger['id']}/price-review").json() == reviewed
    # Purpose costs were assigned; this did not imply product-price allocation.
    assert ledger["snapshot"]["calculation"]["personal_yuan"] == 17


def test_included_discount_is_counted_once_and_never_subtracted_twice(client, body):
    p = product(client)
    ledger = receipt(
        client,
        body,
        amounts=(149,),
        adjustments=[
            adjustment(100, scope="items", positions=[0], effect="included_in_line_totals")
        ],
    )
    reviewed = review_prices(client, ledger, p["id"]).json()
    assert reviewed["line_prices"][0]["paid_line_total_yuan"] == 149
    assert client.get("/v1/overview").json()["explicit_discount_yuan"] == 100
    response = review_prices(
        client,
        ledger,
        p["id"],
        revision=1,
        adjustments=[{"adjustment_position": 0, "method": "proportional_line_amount"}],
    )
    assert response.status_code == 422


def test_single_line_scope_is_direct_and_other_lines_remain_known(client, body):
    p = product(client)
    ledger = receipt(
        client,
        body,
        amounts=(80, 20, 50),
        adjustments=[
            adjustment(3, scope="items", positions=[0, 1]),
            adjustment(5, scope="items", positions=[2]),
        ],
    )
    reviewed = review_prices(client, ledger, p["id"], positions=(0, 1, 2)).json()
    assert [line["paid_line_total_yuan"] for line in reviewed["line_prices"]] == [None, None, 45]
    assert reviewed["line_prices"][2]["allocations"][0]["method"] == "single_line_scope"


@pytest.mark.parametrize(
    "allocations",
    [
        [{"item_position": 0, "amount_yuan": 3}],
        [{"item_position": 0, "amount_yuan": 2}, {"item_position": 1, "amount_yuan": 2}],
        [{"item_position": 0, "amount_yuan": 3}, {"item_position": 2, "amount_yuan": 0}],
        [{"item_position": 0, "amount_yuan": 3}, {"item_position": 0, "amount_yuan": 0}],
    ],
)
def test_explicit_allocations_require_exact_scope_and_conservation(client, body, allocations):
    p = product(client)
    ledger = receipt(
        client,
        body,
        amounts=(10, 10, 10),
        adjustments=[adjustment(3, scope="items", positions=[0, 1])],
    )
    response = review_prices(
        client,
        ledger,
        p["id"],
        positions=(0, 1),
        adjustments=[
            {"adjustment_position": 0, "method": "explicit_amounts", "amounts_by_item": allocations}
        ],
    )
    assert response.status_code == 422
    assert client.get(f"/v1/ledger/{ledger['id']}/price-review/history").json()["revisions"] == []


def test_zero_proportion_requires_explicit_amounts_and_negative_prices_never_rank(client, body):
    p = product(client)
    # Two free items can bear a fee, but zero monetary weights cannot select their relative prices.
    ledger = receipt(
        client,
        body,
        amounts=(0, 0),
        adjustments=[adjustment(5, direction="add")],
        decisions=[
            {"item_position": 0, "method": "whole_line", "whole_purpose": "self"},
            {"item_position": 1, "method": "whole_line", "whole_purpose": "self"},
        ],
        expense_decisions=[
            {
                "adjustment_position": 0,
                "method": "explicit_amounts",
                "amounts_yuan": {"self": 5, "advance": 0, "gift": 0},
            }
        ],
    )
    assert (
        review_prices(
            client,
            ledger,
            p["id"],
            positions=(0, 1),
            adjustments=[{"adjustment_position": 0, "method": "proportional_line_amount"}],
        ).status_code
        == 422
    )
    reviewed = review_prices(
        client,
        ledger,
        p["id"],
        positions=(0, 1),
        adjustments=[
            {
                "adjustment_position": 0,
                "method": "explicit_amounts",
                "amounts_by_item": [
                    {"item_position": 0, "amount_yuan": 5},
                    {"item_position": 1, "amount_yuan": 0},
                ],
            }
        ],
    ).json()
    assert [line["paid_line_total_yuan"] for line in reviewed["line_prices"]] == [5, 0]
    negative = receipt(
        client,
        body,
        key="negative-line",
        amounts=(1, 99),
        adjustments=[adjustment(10, scope="items", positions=[0])],
    )
    response = client.get(f"/v1/ledger/{negative['id']}/price-review")
    assert response.status_code == 200
    assert "negative_paid_line_total" in response.json()["line_prices"][0]["unknown_reasons"]
    assert response.json()["line_prices"][0]["paid_line_total_yuan"] is None
    assert review_prices(client, negative, p["id"]).status_code == 422


def test_exact_lowest_uses_full_filtered_scope_not_current_page_and_void_exits(client, body):
    p = product(client)
    low = receipt(
        client,
        body,
        key="low",
        amounts=(189,),
        quantities=(2,),
        merchant="合成乙店",
        receipt_date="2026-10-19",
    )
    high = receipt(
        client,
        body,
        key="high",
        amounts=(190,),
        quantities=(2,),
        merchant="合成甲店",
        receipt_date="2026-10-20",
    )
    review_prices(client, low, p["id"])
    review_prices(client, high, p["id"])
    response = client.get(f"/v1/products/{p['id']}/prices?limit=1").json()
    assert response["total_count"] == 2
    assert response["observations"][0]["ledger_id"] == high["id"]
    assert response["lowest_paid_observation"]["ledger_id"] == low["id"]
    filtered = client.get(f"/v1/products/{p['id']}/prices?date_from=2026-10-20").json()
    assert (
        filtered["total_count"] == 1
        and filtered["lowest_paid_observation"]["ledger_id"] == high["id"]
    )
    snapshot = deepcopy(low["snapshot"])
    response = client.post(
        f"/v1/ledger/{low['id']}/void",
        json={"expected_revision": 1, "reason": "合成訂正"},
        headers={"Idempotency-Key": "void-low"},
    )
    assert response.status_code == 200
    assert (
        client.get(f"/v1/products/{p['id']}/prices").json()["lowest_paid_observation"]["ledger_id"]
        == high["id"]
    )
    assert client.get("/v1/overview").json()["printed_total_yuan"] == 190
    assert client.get(f"/v1/ledger/{low['id']}").json()["snapshot"] == snapshot
    assert not client.get(f"/v1/ledger/{low['id']}/price-review").json()["line_prices"][0][
        "comparison_eligible"
    ]
    assert review_prices(client, low, p["id"], revision=1).status_code == 409
    assert len(client.get(f"/v1/ledger/{low['id']}/price-review/history").json()["revisions"]) == 1


def test_long_fraction_order_is_exact_and_summary_overflow_is_explicit(client, body):
    p = product(client)
    low = receipt(client, body, key="long-low", amounts=(2**63 - 2,), quantities=(2,))
    high = receipt(client, body, key="long-high", amounts=(2**63 - 1,), quantities=(2,))
    review_prices(client, low, p["id"])
    review_prices(client, high, p["id"])
    response = client.get(f"/v1/products/{p['id']}/prices").json()
    assert response["lowest_paid_observation"]["ledger_id"] == low["id"]
    overflow = client.get("/v1/overview")
    assert (
        overflow.status_code == 422 and overflow.json()["detail"] == "summary_amount_out_of_range"
    )


def test_overview_full_scope_date_query_and_pending_drafts_are_distinct(client, body):
    receipt(client, body, key="first", amounts=(100,), receipt_date="2026-10-19")
    second = receipt(
        client,
        body,
        key="second",
        amounts=(80, 20),
        receipt_date="2026-10-20",
        merchant="明確查詢店",
    )
    create(client, body, "pending-only")
    page = client.get("/v1/ledger?limit=1").json()
    assert len(page["entries"]) == 1 and page["total_count"] == 2
    overview = client.get("/v1/overview").json()
    assert overview["receipt_count"] == 2 and overview["printed_total_yuan"] == 200
    assert overview["personal_yuan"] == 200 and overview["explicit_discount_yuan"] == 0
    assert (
        client.get("/v1/overview?date_from=2026-10-20&date_to=2026-10-20").json()["receipt_count"]
        == 1
    )
    filtered = client.get("/v1/ledger", params={"q": "明確查詢"}).json()
    assert filtered["total_count"] == 1 and filtered["entries"][0]["id"] == second["id"]
    assert client.get("/v1/ledger", params={"q": "%' OR 1=1 --"}).json()["entries"] == []
    assert client.get("/v1/overview?date_to=2026-02-30").status_code == 422
    assert client.get("/v1/overview?date_from=2026-10-21&date_to=2026-10-20").status_code == 422
    assert client.get("/v1/ledger?status=unknown").status_code == 422


def test_price_cas_parallel_and_ingest_cannot_approve(client, body, store):
    p = product(client)
    ledger = receipt(client, body)
    request = ReviewPrices.model_validate(
        {
            "expected_revision": 0,
            "item_mappings": [
                {
                    "item_position": 0,
                    "product_id": p["id"],
                    "unit_match": "confirmed_same_product_and_unit",
                }
            ],
            "adjustment_decisions": [],
        }
    )
    analysis = AnalysisStore(store)

    def writer(_):
        try:
            return analysis.review_prices(ledger["id"], request)["revision"]
        except StoreError as error:
            return error.code

    with ThreadPoolExecutor(max_workers=2) as executor:
        outcomes = list(executor.map(writer, range(2)))
    assert sorted(map(str, outcomes)) == ["1", "revision_conflict"]
    assert len(analysis.price_history(ledger["id"])["revisions"]) == 1
    client.headers["Authorization"] = "Bearer " + TOKEN
    assert review_prices(client, ledger, p["id"], revision=1).status_code == 403
    assert client.get(f"/v1/ledger/{ledger['id']}/price-review").status_code == 200


def test_unknown_product_extra_identity_and_unsupported_method_fail_closed(client, body):
    ledger = receipt(client, body)
    unknown = "ffffffff-ffff-4fff-8fff-ffffffffffff"
    response = review_prices(client, ledger, unknown)
    assert response.status_code == 422 and response.json()["detail"] == "invalid_product_reference"
    assert client.get(f"/v1/products/{unknown}/prices").status_code == 404
    bad = {"display_name": "合成", "purchase_unit": "瓶"}
    assert (
        client.post(
            "/v1/products", json=bad, headers={"Idempotency-Key": "missing-spec"}
        ).status_code
        == 422
    )
    bad.update(specification="500ml", confirmed=True)
    assert (
        client.post("/v1/products", json=bad, headers={"Idempotency-Key": "ai-confirm"}).status_code
        == 422
    )


def test_pricing_contract_fixtures_match_models_and_domain():
    import json
    from pathlib import Path

    from pixelreceipt_backend.models import Candidate
    from pixelreceipt_backend.pricing import price_lines
    from pixelreceipt_backend.pricing_models import CreateProduct, PriceDocument

    contract = json.loads(
        (Path(__file__).parents[1] / "price-api-contract-examples.json").read_text()
    )
    CreateProduct.model_validate(contract["product_create_request"])
    request = ReviewPrices.model_validate(contract["price_review_put_request"])
    products = {p["id"]: p for p in contract["product_list_response"]["products"]}
    lines, basis = price_lines(
        Candidate.model_validate(contract["price_source_candidate"]),
        PriceDocument.model_validate(request.document()),
        products,
    )
    assert lines == contract["price_review_response"]["line_prices"]
    assert basis == contract["price_review_response"]["basis_sha256"]


def test_large_scope_streams_count_summary_and_minimum_beyond_page(client, body, store):
    import json
    import tracemalloc

    from pixelreceipt_backend.store import canonical

    p = product(client)
    ledger = receipt(client, body, amounts=(10,))
    reviewed = review_prices(client, ledger, p["id"]).json()
    # Synthetic bulk rows use valid immutable snapshots and annotations, with large source text.
    # 240 snapshots at ~40KiB would exceed the tested peak if retained before pagination.
    with store.connection(write=True) as connection:
        draft_row = dict(
            connection.execute(
                "SELECT * FROM drafts WHERE id = ?", (ledger["draft_id"],)
            ).fetchone()
        )
        for index in range(240):
            ledger_id = f"bulk-ledger-{index:04d}"
            draft_id = f"bulk-draft-{index:04d}"
            snapshot = deepcopy(ledger["snapshot"])
            snapshot.update(id=draft_id, ledger_id=ledger_id)
            snapshot["original_candidate"]["items"] = [
                deepcopy(snapshot["original_candidate"]["items"][0]) for _ in range(80)
            ]
            for item in snapshot["original_candidate"]["items"]:
                item["raw_name"] = "S" * 500
            amount = 1 if index == 239 else 10
            snapshot["candidate"]["items"][0]["printed_line_total_yuan"] = amount
            snapshot["candidate"]["printed_total_yuan"] = amount
            for key in (
                "printed_total_yuan",
                "allocated_total_yuan",
                "computed_total_yuan",
                "self_yuan",
                "personal_yuan",
            ):
                snapshot["calculation"][key] = amount
            from pixelreceipt_backend.domain import calculate
            from pixelreceipt_backend.ledger_models import ReviewDocument
            from pixelreceipt_backend.models import Candidate

            snapshot["calculation"] = calculate(
                Candidate.model_validate(snapshot["candidate"]),
                ReviewDocument.model_validate(snapshot["review_document"]),
            )
            values = {
                **draft_row,
                "original": canonical(snapshot["original_candidate"]),
                "candidate": canonical(snapshot["candidate"]),
                "id": draft_id,
                "idempotency_key": draft_id,
                "ledger_id": ledger_id,
            }
            connection.execute(
                f"INSERT INTO drafts ({','.join(values)}) VALUES ({','.join('?' for _ in values)})",
                tuple(values.values()),
            )
            connection.execute(
                "INSERT INTO ledger VALUES (?, ?, ?, ?, 'reviewer')",
                (
                    ledger_id,
                    draft_id,
                    canonical(snapshot),
                    f"2026-10-19T00:{index // 60:02d}:{index % 60:02d}+00:00",
                ),
            )
            connection.execute(
                "INSERT INTO ledger_events VALUES "
                "(?, 1, 'confirmed', 'reviewer', NULL, 'synthetic')",
                (ledger_id,),
            )
            document = json.dumps(
                {"item_mappings": reviewed["item_mappings"], "adjustment_decisions": []}
            )
            connection.execute(
                "INSERT INTO price_reviews VALUES (?, 1, ?, 'synthetic', 'reviewer')",
                (ledger_id, document),
            )
    analysis = AnalysisStore(store)
    tracemalloc.start()
    try:
        page = analysis.list_ledger(2, 0)
        summary = analysis.overview()
        prices = analysis.product_prices(p["id"], 2, 0)
        _, peak = tracemalloc.get_traced_memory()
    finally:
        tracemalloc.stop()
    assert page["total_count"] == summary["receipt_count"] == prices["total_count"] == 241
    assert len(page["entries"]) == len(prices["observations"]) == 2
    assert summary["printed_total_yuan"] == 2401
    assert prices["lowest_paid_observation"]["ledger_id"] == "bulk-ledger-0239"
    assert peak < 6 * 1024 * 1024


def test_read_transaction_remains_consistent_while_wal_writer_commits(client, body, store):
    from pixelreceipt_backend.ledger_models import VoidReceipt

    ledger = receipt(client, body)
    with store.connection() as reader:
        assert store._ledger(reader, ledger["id"])["status"] == "active"
        with ThreadPoolExecutor(max_workers=1) as executor:
            result = executor.submit(
                store.void,
                ledger["id"],
                VoidReceipt(expected_revision=1, reason="synthetic WAL test"),
                "wal-void",
            ).result(timeout=5)
        assert result[0]["status"] == "voided"
        assert store._ledger(reader, ledger["id"])["status"] == "active"
    assert store.get_ledger(ledger["id"])["status"] == "voided"
