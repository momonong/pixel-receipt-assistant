"""Central-ledger regression cases ported from the Android domain tests."""

from concurrent.futures import ThreadPoolExecutor
from copy import deepcopy

import pytest

from pixelreceipt_backend.domain import calculate
from pixelreceipt_backend.ledger_models import (
    ConfirmReceipt,
    ReviewDocument,
    ReviewReceipt,
    VoidReceipt,
)
from pixelreceipt_backend.store import Store, StoreError

from .conftest import TOKEN
from .test_drafts import create


def review_body(body, *, purpose="self"):
    candidate = deepcopy(body["candidate"])
    candidate["items"][0]["quantity"] = 1
    candidate["adjustments"] = []
    return {
        "expected_revision": 1,
        "candidate": candidate,
        "assembly_status": "complete_by_user",
        "line_decisions": [{"item_position": 0, "method": "whole_line", "whole_purpose": purpose}],
        "adjustment_decisions": [],
        "tolerance_acknowledgement": "none",
        "review_note": "SYNTHETIC human review",
    }


def result(review):
    request = ReviewReceipt.model_validate(review)
    return calculate(request.candidate, ReviewDocument.model_validate(request.document()))


def prepare(client, body):
    draft = create(client, body).json()
    review = review_body(body)
    response = client.put(f"/v2/drafts/{draft['id']}/review", json=review)
    assert response.status_code == 200, response.text
    return response.json(), review


def test_confirm_void_are_durable_immutable_cas_and_idempotent(client, store, body):
    draft, review = prepare(client, body)
    assert draft["calculation"]["ready"]
    assert draft["calculation"]["personal_yuan"] == 100
    url = f"/v2/drafts/{draft['id']}/confirm"
    request = {"expected_revision": 2}
    response = client.post(url, json=request, headers={"Idempotency-Key": "confirm-1"})
    assert response.status_code == 201
    ledger = response.json()
    assert ledger["revision"] == 1 and ledger["status"] == "active"
    assert ledger["snapshot"]["revision"] == 3
    assert ledger["snapshot"]["review_document"]["line_decisions"][0]["whole_purpose"] == "self"
    assert ledger["snapshot"]["calculation"]["basis_sha256"] == draft["calculation"]["basis_sha256"]
    assert client.post(url, json=request, headers={"Idempotency-Key": "confirm-1"}).json() == ledger
    assert (
        client.post(url, json=request, headers={"Idempotency-Key": "new-confirm"}).status_code
        == 409
    )
    review["expected_revision"] = 3
    assert client.put(f"/v2/drafts/{draft['id']}/review", json=review).status_code == 409
    assert (
        client.put(
            f"/v1/drafts/{draft['id']}/review",
            json={"expected_revision": 3, "candidate": review["candidate"]},
        ).status_code
        == 409
    )
    assert len(client.get("/v1/ledger").json()["entries"]) == 1
    void_url = f"/v1/ledger/{ledger['id']}/void"
    void_body = {"expected_revision": 1, "reason": "SYNTHETIC correction"}
    voided = client.post(void_url, json=void_body, headers={"Idempotency-Key": "void-1"}).json()
    assert voided["revision"] == 2 and voided["status"] == "voided"
    assert voided["snapshot"] == ledger["snapshot"]
    assert [event["action"] for event in voided["events"]] == ["confirmed", "voided"]
    assert (
        client.post(void_url, json=void_body, headers={"Idempotency-Key": "void-1"}).json()
        == voided
    )
    assert (
        client.post(void_url, json=void_body, headers={"Idempotency-Key": "different"}).status_code
        == 409
    )
    assert client.get(f"/v1/drafts/{draft['id']}").json()["status"] == "voided"
    assert [
        r["action"] for r in client.get(f"/v1/drafts/{draft['id']}/history").json()["revisions"]
    ] == ["initial_import", "reviewer", "confirmed", "voided"]
    reopened = Store(store.data_dir)
    assert reopened.get_ledger(ledger["id"]) == voided
    assert reopened.get_draft(draft["id"])["revision"] == 4


def test_ingest_token_cannot_review_confirm_void_or_obtain_reviewer_session(client, body):
    draft, review = prepare(client, body)
    client.headers["Authorization"] = "Bearer " + TOKEN
    assert client.get(f"/v1/drafts/{draft['id']}").status_code == 200
    assert client.get("/v1/session").status_code == 403
    for method, url, payload in [
        (
            "put",
            f"/v1/drafts/{draft['id']}/review",
            {"expected_revision": 2, "candidate": review["candidate"]},
        ),
        ("put", f"/v2/drafts/{draft['id']}/review", review),
        ("post", f"/v2/drafts/{draft['id']}/confirm", {"expected_revision": 2}),
        ("post", "/v1/ledger/absent/void", {"expected_revision": 1, "reason": "attempt"}),
    ]:
        response = getattr(client, method)(
            url, json=payload, headers={"Idempotency-Key": "forbidden"}
        )
        assert response.status_code == 403
        assert response.json() == {"detail": "reviewer_required"}


def test_v1_edit_invalidates_all_previous_ownership_and_basis(client, body):
    draft, review = prepare(client, body)
    previous_basis = draft["calculation"]["basis_sha256"]
    response = client.put(
        f"/v1/drafts/{draft['id']}/review",
        json={"expected_revision": 2, "candidate": review["candidate"]},
    )
    assert response.json()["review_document"] is None
    assert response.json()["calculation"]["basis_sha256"] != previous_basis
    assert not response.json()["calculation"]["ready"]


@pytest.mark.parametrize("field", ["merchant", "receipt_date", "printed_total_yuan"])
def test_required_receipt_unknown_never_confirms(client, body, field):
    draft = create(client, body).json()
    review = review_body(body)
    review["candidate"][field] = None
    edited = client.put(f"/v2/drafts/{draft['id']}/review", json=review).json()
    assert not edited["calculation"]["ready"]
    response = client.post(
        f"/v2/drafts/{draft['id']}/confirm",
        json={"expected_revision": 2},
        headers={"Idempotency-Key": "blocked"},
    )
    assert response.status_code == 409
    assert response.json()["detail"] == "confirmation_blocked"
    assert response.json()["calculation"] == edited["calculation"]
    assert client.get("/v1/ledger").json()["entries"] == []


@pytest.mark.parametrize("payment", [99, 101])
def test_tolerance_requires_explicit_ack_and_never_changes_purpose_costs(body, payment):
    review = review_body(body)
    review["candidate"]["printed_total_yuan"] = payment
    calculation = result(review)
    assert not calculation["ready"]
    assert calculation["difference_yuan"] == 100 - payment
    assert calculation["allocated_total_yuan"] == 100
    assert calculation["personal_yuan"] == 100
    review["tolerance_acknowledgement"] = "accept_within_tolerance"
    calculation = result(review)
    assert calculation["ready"] and calculation["reconciliation_status"] == "within_tolerance"
    assert (
        calculation["difference_yuan"]
        == calculation["allocated_total_yuan"] - calculation["printed_total_yuan"]
    )
    assert calculation["personal_yuan"] == 100


def test_included_discount_and_quantity_never_double_apply(body):
    review = review_body(body)
    review["candidate"]["printed_total_yuan"] = 149
    review["candidate"]["items"][0].update(quantity=3, printed_line_total_yuan=149)
    review["candidate"]["adjustments"] = [
        {
            "raw_label": "原249折100已含",
            "direction": "subtract",
            "amount_yuan": 100,
            "amount_effect": "included_in_line_totals",
            "scope": "items",
            "item_positions": [0],
        }
    ]
    calculation = result(review)
    assert calculation["ready"]
    assert calculation["computed_total_yuan"] == calculation["personal_yuan"] == 149
    review["adjustment_decisions"] = [{"adjustment_position": 0, "method": "proportional_amount"}]
    with pytest.raises(ValueError):
        ReviewReceipt.model_validate(review)


def test_quantity_largest_remainder_explicit_ownership_and_gifts(body):
    review = review_body(body)
    review["candidate"]["items"][0]["quantity"] = 3
    review["line_decisions"] = [
        {
            "item_position": 0,
            "method": "by_quantity",
            "quantities": {"self": 1, "advance": 2, "gift": 0},
        }
    ]
    calculation = result(review)
    assert calculation["ready"]
    assert (calculation["self_yuan"], calculation["advance_yuan"]) == (33, 67)
    review["candidate"]["items"][0].update(quantity=2, printed_line_total_yuan=1)
    review["candidate"]["printed_total_yuan"] = 1
    review["line_decisions"][0]["quantities"].update(advance=1)
    assert result(review)["self_yuan"] == 1
    assert result(review)["advance_yuan"] == 0
    review = review_body(body, purpose="gift")
    assert result(review)["gift_yuan"] == result(review)["personal_yuan"] == 100
    assert result(review)["self_yuan"] == 0


def test_explicit_allocations_conserve_quantity_money_and_scope(body):
    review = review_body(body)
    review["candidate"]["items"][0]["quantity"] = 2
    review["line_decisions"] = [
        {
            "item_position": 0,
            "method": "explicit_amounts",
            "quantities": {"self": 1, "advance": 0, "gift": 1},
            "amounts_yuan": {"self": 100, "advance": 0, "gift": 0},
        }
    ]
    assert result(review)["ready"]
    review["line_decisions"][0]["amounts_yuan"].update(self=90, advance=10)
    assert not result(review)["ready"]
    review["line_decisions"][0]["amounts_yuan"].update(self=100, advance=0)
    review["line_decisions"][0]["quantities"]["gift"] = 0
    assert not result(review)["ready"]


def test_separate_adjustments_zero_basis_and_negative_burdens(body):
    review = review_body(body)
    review["candidate"]["items"] += [
        {**review["candidate"]["items"][0], "printed_line_total_yuan": 20}
    ]
    review["candidate"]["items"][0]["printed_line_total_yuan"] = 80
    review["line_decisions"] += [
        {"item_position": 1, "method": "whole_line", "whole_purpose": "advance"}
    ]
    review["candidate"]["printed_total_yuan"] = 97
    review["candidate"]["adjustments"] = [
        {
            "raw_label": "合成折扣",
            "direction": "subtract",
            "amount_yuan": 3,
            "amount_effect": "separate_from_line_totals",
            "scope": "receipt",
            "item_positions": [],
        }
    ]
    assert not result(review)["ready"]
    review["adjustment_decisions"] = [{"adjustment_position": 0, "method": "proportional_amount"}]
    calculation = result(review)
    assert calculation["ready"]
    assert (calculation["personal_yuan"], calculation["advance_yuan"]) == (78, 19)
    review["candidate"]["adjustments"][0].update(scope="items", item_positions=[1])
    review["adjustment_decisions"][0] = {
        "adjustment_position": 0,
        "method": "explicit_amounts",
        "amounts_yuan": {"self": 3, "advance": 0, "gift": 0},
    }
    assert not result(review)["ready"]
    review = review_body(body)
    review["candidate"]["items"][0]["printed_line_total_yuan"] = 0
    review["candidate"]["printed_total_yuan"] = 5
    review["candidate"]["adjustments"] = [
        {
            "raw_label": "合成費用",
            "direction": "add",
            "amount_yuan": 5,
            "amount_effect": "separate_from_line_totals",
            "scope": "receipt",
            "item_positions": [],
        }
    ]
    review["adjustment_decisions"] = [{"adjustment_position": 0, "method": "proportional_amount"}]
    assert not result(review)["ready"]
    review["adjustment_decisions"][0].update(
        method="explicit_amounts", amounts_yuan={"self": 5, "advance": 0, "gift": 0}
    )
    assert result(review)["personal_yuan"] == 5


def test_long_arithmetic_uses_exact_products_and_blocks_overflow(body):
    review = review_body(body)
    review["candidate"]["items"][0].update(quantity=3, printed_line_total_yuan=2**63 - 1)
    review["candidate"]["printed_total_yuan"] = 2**63 - 1
    review["line_decisions"] = [
        {
            "item_position": 0,
            "method": "by_quantity",
            "quantities": {"self": 1, "advance": 2, "gift": 0},
        }
    ]
    calculation = result(review)
    assert calculation["ready"]
    assert calculation["self_yuan"] + calculation["advance_yuan"] == 2**63 - 1
    review["candidate"]["items"].append(
        {**review["candidate"]["items"][0], "quantity": 1, "printed_line_total_yuan": 1}
    )
    review["line_decisions"].append(
        {"item_position": 1, "method": "whole_line", "whole_purpose": "self"}
    )
    calculation = result(review)
    assert not calculation["ready"]
    assert calculation["allocated_total_yuan"] is None
    assert calculation["computed_total_yuan"] is None


def test_parallel_confirmation_cannot_duplicate_ledger(client, store, body):
    draft, _ = prepare(client, body)
    request = ConfirmReceipt(expected_revision=2)

    def writer(index):
        try:
            return store.confirm(draft["id"], request, f"parallel-{index}")[0]["id"]
        except StoreError as error:
            return error.code

    with ThreadPoolExecutor(max_workers=2) as executor:
        outcomes = list(executor.map(writer, range(2)))
    assert outcomes.count("revision_conflict") == 1
    assert len(store.list_ledger(100, 0)) == 1
    ledger = store.list_ledger(100, 0)[0]
    request = VoidReceipt(expected_revision=1, reason="parallel synthetic")

    def voider(index):
        try:
            return store.void(ledger["id"], request, f"parallel-void-{index}")[0]["revision"]
        except StoreError as error:
            return error.code

    with ThreadPoolExecutor(max_workers=2) as executor:
        outcomes = list(executor.map(voider, range(2)))
    assert sorted(map(str, outcomes)) == ["2", "revision_conflict"]


@pytest.mark.parametrize(
    "mutate,code",
    [
        (lambda r: r.update(assembly_status="unknown"), "receipt_completeness_unconfirmed"),
        (lambda r: r.update(line_decisions=[]), "item_purpose_unconfirmed"),
        (lambda r: r["candidate"]["items"][0].update(raw_name=None), "item_name_unknown"),
        (lambda r: r["candidate"]["items"][0].update(quantity=None), "item_quantity_unknown"),
        (
            lambda r: r["candidate"]["items"][0].update(printed_line_total_yuan=None),
            "item_amount_unknown",
        ),
    ],
)
def test_unknowns_and_missing_human_approval_block_without_false_zero(body, mutate, code):
    review = review_body(body)
    mutate(review)
    calculation = result(review)
    assert not calculation["ready"]
    assert code in {b["code"] for b in calculation["blockers"]}
    if code in {"item_purpose_unconfirmed", "item_quantity_unknown", "item_amount_unknown"}:
        assert calculation["allocated_total_yuan"] is None
        assert calculation["personal_yuan"] is None


@pytest.mark.parametrize(
    "field,code",
    [
        ("scope", "adjustment_scope_unknown"),
        ("amount_effect", "adjustment_effect_unknown"),
        ("direction", "adjustment_direction_unknown"),
        ("amount_yuan", "adjustment_amount_unknown"),
    ],
)
def test_unknown_adjustment_facts_never_become_zero_or_global_scope(body, field, code):
    review = review_body(body)
    adjustment = {
        "raw_label": "SYNTHETIC",
        "direction": "subtract",
        "amount_yuan": 0,
        "amount_effect": "separate_from_line_totals",
        "scope": "receipt",
        "item_positions": [],
    }
    adjustment[field] = None
    review["candidate"]["adjustments"] = [adjustment]
    review["adjustment_decisions"] = [{"adjustment_position": 0, "method": "proportional_amount"}]
    calculation = result(review)
    assert not calculation["ready"]
    assert calculation["allocated_total_yuan"] is None
    assert code in {b["code"] for b in calculation["blockers"]}


def test_contract_fixtures_match_production_models_and_calculator():
    import json
    from pathlib import Path

    contract = json.loads((Path(__file__).parents[1] / "api-contract-examples.json").read_text())
    request = ReviewReceipt.model_validate(contract["review_request"])
    assert (
        calculate(request.candidate, ReviewDocument.model_validate(request.document()))
        == contract["draft_response"]["calculation"]
    )
    blocked = contract["confirmation_blocked_response"]["calculation"]
    assert blocked["difference_yuan"] == 1
    assert not blocked["ready"] and blocked["expenses_ready"]


def test_pending_queue_filter_applies_before_pagination(client, body):
    draft, _ = prepare(client, body)
    assert len(client.get("/v1/drafts?status=needs_review&limit=1").json()["drafts"]) == 1
    client.post(
        f"/v2/drafts/{draft['id']}/confirm",
        json={"expected_revision": 2},
        headers={"Idempotency-Key": "filter-confirm"},
    )
    assert client.get("/v1/drafts?status=needs_review").json()["drafts"] == []
    assert client.get("/v1/drafts?status=confirmed").json()["drafts"][0]["id"] == draft["id"]
    assert client.get("/v1/drafts?status=untrusted").status_code == 422
