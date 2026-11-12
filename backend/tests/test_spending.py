"""Complete read-only spending from confirmed ledger snapshots."""

import json

import pytest

from pixelreceipt_backend.analysis_store import AnalysisStore
from pixelreceipt_backend.ledger_models import VoidReceipt
from pixelreceipt_backend.models import LONG_MAX
from pixelreceipt_backend.store import canonical

from .conftest import TOKEN
from .test_drafts import create
from .test_prices import adjustment, receipt


def spending(client, **params):
    response = client.get("/v1/spending", params=params)
    assert response.status_code == 200, response.text
    return response.json()


def historical_receipt(client, body, store, **params):
    # Current confirmation correctly requires a date and merchant. Only this
    # isolated test DB is seeded with a legacy snapshot lacking those fields.
    original = {key: params[key] for key in ("receipt_date", "merchant") if key in params}
    params.update(receipt_date="2026-10-20", merchant="synthetic legacy placeholder")
    entry = receipt(client, body, **params)
    entry["snapshot"]["candidate"].update(original)
    with store.connection(write=True) as connection:
        connection.execute(
            "UPDATE ledger SET snapshot = ? WHERE id = ?",
            (canonical(entry["snapshot"]), entry["id"]),
        )
    return entry


def test_full_scope_purposes_discount_void_and_immutable_snapshots(client, body, store):
    ledger = receipt(
        client,
        body,
        amounts=(100, 40, 60),
        decisions=[
            {"item_position": 0, "method": "whole_line", "whole_purpose": "self"},
            {"item_position": 1, "method": "whole_line", "whole_purpose": "gift"},
            {"item_position": 2, "method": "whole_line", "whole_purpose": "advance"},
        ],
        adjustments=[adjustment(25, effect="included_in_line_totals")],
    )
    voided = receipt(client, body, key="voided", amounts=(999,))
    assert (
        client.post(
            f"/v1/ledger/{voided['id']}/void",
            json={"expected_revision": 1, "reason": "synthetic"},
            headers={"Idempotency-Key": "void-spending"},
        ).status_code
        == 200
    )
    create(client, body, "draft-only")
    before = store.database.read_bytes()
    result = spending(client)
    assert result["scope"] == "active_confirmed_ledger"
    assert result["currency"] == "TWD" and result["amount_unit"] == "TWD_YUAN"
    assert result["summary"] == {
        "receipt_count": 1,
        "missing_item_name_count": 0,
        "receipts_missing_item_names": 0,
        "printed_total_yuan": 200,
        "allocated_total_yuan": 200,
        "self_yuan": 100,
        "gift_yuan": 40,
        "advance_yuan": 60,
        "personal_yuan": 140,
        "difference_yuan": 0,
        "explicit_discount_yuan": 25,
    }
    assert result["series"][0]["personal_yuan"] == 140
    assert result["merchants"][0]["personal_yuan"] == 140
    assert result["comparison"] is None
    assert store.database.read_bytes() == before
    assert client.get(f"/v1/ledger/{ledger['id']}").json()["snapshot"] == ledger["snapshot"]


def test_full_scope_is_independent_of_ledger_pages(client, body):
    for index in range(23):
        receipt(client, body, key=f"page-{index}", amounts=(index + 1,))
    assert len(client.get("/v1/ledger", params={"limit": 1}).json()["entries"]) == 1
    result = spending(client)
    assert result["summary"]["receipt_count"] == 23
    assert result["summary"]["personal_yuan"] == 276
    assert result["series"][0]["receipt_count"] == 23


def test_null_dates_are_retained_only_in_all_time_and_drill_down(client, body, store):
    receipt(client, body, key="dated", amounts=(30,))
    undated = historical_receipt(
        client, body, store, key="undated", amounts=(70,), receipt_date=None
    )
    result = spending(client)
    assert result["summary"]["personal_yuan"] == 100
    assert result["undated_receipt_count"] == 1 and result["undated_personal_yuan"] == 70
    assert result["excluded_undated_receipt_count"] == 0
    assert sum(point["personal_yuan"] for point in result["series"]) == 30
    result = spending(client, date_from="2026-10-20", date_to="2026-10-20")
    assert result["summary"]["personal_yuan"] == 30
    assert result["undated_receipt_count"] == 0 and result["undated_personal_yuan"] == 0
    assert result["excluded_undated_receipt_count"] == 1
    page = client.get("/v1/ledger", params={"undated_only": True, "limit": 1}).json()
    assert page["total_count"] == 1 and page["entries"][0]["id"] == undated["id"]


def test_merchant_identity_is_exact_and_filtered_before_pagination(client, body, store):
    for index, name in enumerate(["x%' OR 1=1 --", "X", "x", None, "  ", "x 分店"]):
        historical_receipt(
            client, body, store, key=f"merchant-{index}", merchant=name, amounts=(10 + index,)
        )
    result = spending(client)
    assert len(result["merchants"]) == 5
    assert len({merchant["key"] for merchant in result["merchants"]}) == 5
    for merchant in result["merchants"]:
        assert len(merchant["key"]) == 64
        page = client.get(
            "/v1/ledger",
            params={
                "merchant_key": merchant["key"],
                "status": "active",
                "limit": 1,
            },
        ).json()
        assert page["total_count"] == merchant["receipt_count"]
        assert page["entries"][0]["merchant_key"] == merchant["key"]
    missing = next(m for m in result["merchants"] if m["name"] is None)
    assert missing["receipt_count"] == 2 and missing["personal_yuan"] == 27
    assert client.get("/v1/ledger", params={"merchant_key": "0" * 64}).json()["total_count"] == 0


def test_comparison_explicit_dates_zero_baseline_no_fabricated_percent(client, body):
    receipt(client, body, key="current", amounts=(30,))
    receipt(client, body, key="previous", amounts=(0,), receipt_date="2026-09-20")
    result = spending(
        client,
        date_from="2026-10-01",
        date_to="2026-10-31",
        compare_from="2026-09-01",
        compare_to="2026-09-30",
    )
    assert result["comparison"] == {
        "date_from": "2026-09-01",
        "date_to": "2026-09-30",
        "personal_yuan": 0,
        "receipt_count": 1,
        "delta_personal_yuan": 30,
    }


@pytest.mark.parametrize(
    ("params", "expected"),
    [
        ({"date_from": "2026-10-01", "date_to": "2026-10-31"}, "day"),
        ({"date_from": "2026-01-01", "date_to": "2026-12-31"}, "month"),
        ({"date_from": "2026-01-01", "date_to": "2026-03-31"}, "day"),
        ({"date_from": "2026-01-01", "date_to": "2026-04-01"}, "month"),
        ({"date_from": "2017-01-01", "date_to": "2026-12-31"}, "month"),
        ({"date_from": "2016-12-31", "date_to": "2026-12-31"}, "year"),
        ({"date_from": "2000-01-01", "date_to": "2026-12-31"}, "year"),
    ],
)
def test_auto_bucket_requested_span_even_if_empty(client, params, expected):
    result = spending(client, **params)
    assert result["bucket"] == expected and result["series"] == []


def test_series_order_calendar_boundaries_and_sparse_buckets(client, body):
    receipt(client, body, key="leap", amounts=(10,), receipt_date="2024-02-29")
    receipt(client, body, key="april", amounts=(20,), receipt_date="2024-04-01")
    result = spending(client, date_from="2024-02-15", date_to="2024-04-15", bucket="month")
    assert result["series"] == [
        {
            "date_from": "2024-02-15",
            "date_to": "2024-02-29",
            "label": "2024-02",
            "personal_yuan": 10,
            "receipt_count": 1,
        },
        {
            "date_from": "2024-04-01",
            "date_to": "2024-04-15",
            "label": "2024-04",
            "personal_yuan": 20,
            "receipt_count": 1,
        },
    ]


@pytest.mark.parametrize(
    "params",
    [
        {"date_from": "2026-02-30"},
        {"date_from": "2026-11-01", "date_to": "2026-01-01"},
        {"compare_from": "2026-01-01"},
        {"compare_to": "2026-01-01"},
        {"compare_from": "2026-02-01", "compare_to": "2026-01-01"},
        {"bucket": "week"},
        {"date_from": "0001-01-01", "date_to": "9999-12-31", "bucket": "day"},
        {"date_from": "0001-01-01", "date_to": "9999-12-31", "bucket": "year"},
    ],
)
def test_invalid_ranges_or_excess_buckets_explicit(client, params):
    assert client.get("/v1/spending", params=params).status_code == 422


@pytest.mark.parametrize(
    "params",
    [
        {"merchant_key": "not-a-hash"},
        {"merchant_key": "a" * 63},
        {"undated_only": True, "date_from": "2026-01-01"},
        {"undated_only": True, "date_to": "2026-01-01"},
    ],
)
def test_invalid_ledger_drill_down_rejected(client, params):
    assert client.get("/v1/ledger", params=params).status_code == 422


def test_overflow_explicit_and_large_integer_exact(client, body):
    receipt(client, body, key="max", amounts=(LONG_MAX,))
    assert spending(client)["summary"]["personal_yuan"] == LONG_MAX
    receipt(client, body, key="one-more", amounts=(1,))
    response = client.get("/v1/spending")
    assert response.status_code == 422
    assert response.json()["detail"] == "summary_amount_out_of_range"


def test_spending_auth_and_authenticated_reader(client):
    client.headers["Authorization"] = "Bearer " + TOKEN
    assert client.get("/v1/spending").status_code == 200
    del client.headers["Authorization"]
    assert client.get("/v1/spending").status_code == 401


def test_all_undated_and_empty_scope_no_fabricated_time(client, body, store):
    result = spending(client)
    assert result["summary"]["personal_yuan"] == 0
    assert result["merchants"] == [] and result["series"] == []
    historical_receipt(client, body, store, receipt_date=None)
    result = spending(client)
    assert result["series"] == [] and result["undated_personal_yuan"] == 100


def test_comparison_and_summary_share_snapshot_during_concurrent_void(
    client, body, store, monkeypatch
):
    first = receipt(client, body, key="snapshot-first", amounts=(10,))
    receipt(client, body, key="snapshot-second", amounts=(20,))
    analysis = AnalysisStore(store)
    original = analysis._filtered_ledger

    def interleave(connection, **params):
        for index, entry in enumerate(original(connection, **params)):
            yield entry
            if index == 0:
                store.void(
                    first["id"],
                    VoidReceipt(expected_revision=1, reason="synthetic"),
                    "concurrent-void",
                )

    monkeypatch.setattr(analysis, "_filtered_ledger", interleave)
    result = analysis.spending(
        date_from="2026-10-01",
        date_to="2026-10-31",
        compare_from="2026-10-01",
        compare_to="2026-10-31",
    )
    assert result["summary"]["personal_yuan"] == 30
    assert result["comparison"]["personal_yuan"] == 30
    assert result["comparison"]["delta_personal_yuan"] == 0
    assert client.get("/v1/spending").json()["summary"]["personal_yuan"] == 20


def test_name_completeness_and_long_max_year_date(client, body, store):
    entry = historical_receipt(client, body, store, receipt_date="9999-12-31")
    snapshot = entry["snapshot"]
    snapshot["candidate"]["items"][0]["raw_name"] = None
    with store.connection(write=True) as connection:
        connection.execute(
            "UPDATE ledger SET snapshot = ? WHERE id = ?", (json.dumps(snapshot), entry["id"])
        )
    result = spending(client, date_from="9999-01-01", date_to="9999-12-31", bucket="year")
    assert result["summary"]["missing_item_name_count"] == 1
    assert result["summary"]["receipts_missing_item_names"] == 1
    assert result["series"][0]["date_to"] == "9999-12-31"


def test_exact_thousand_bucket_limit_does_not_truncate(client):
    assert (
        spending(client, date_from="2020-01-01", date_to="2022-09-26", bucket="day")["series"] == []
    )
    response = client.get(
        "/v1/spending", params={"date_from": "2020-01-01", "date_to": "2022-09-27", "bucket": "day"}
    )
    assert response.status_code == 422
    assert response.json()["detail"] == "spending_bucket_limit_exceeded"


def test_comparison_overflow_is_not_hidden_by_small_primary_scope(client, body):
    receipt(client, body, key="old-max", amounts=(LONG_MAX,), receipt_date="2026-09-01")
    receipt(client, body, key="old-extra", amounts=(1,), receipt_date="2026-09-02")
    response = client.get(
        "/v1/spending",
        params={
            "date_from": "2026-10-01",
            "date_to": "2026-10-31",
            "compare_from": "2026-09-01",
            "compare_to": "2026-09-30",
        },
    )
    assert response.status_code == 422
    assert response.json()["detail"] == "summary_amount_out_of_range"
