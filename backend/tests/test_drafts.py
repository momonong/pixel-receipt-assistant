from concurrent.futures import ThreadPoolExecutor
from copy import deepcopy

from fastapi.testclient import TestClient

from pixelreceipt_backend.api import create_app
from pixelreceipt_backend.models import ReviewDraft
from pixelreceipt_backend.store import Store, StoreError

from .conftest import TOKEN


def create(client, body, key="synthetic-1"):
    return client.post("/v1/drafts", json=body, headers={"Idempotency-Key": key})


def test_durability_idempotency_cas_original_and_history(client, store, body):
    first = create(client, body)
    assert first.status_code == 201
    draft = first.json()
    assert draft["status"] == "needs_review"
    assert draft["revision"] == 1
    assert draft["candidate"]["printed_total_yuan"] == 100  # No ×100 conversion.
    assert draft["candidate"]["items"][0]["quantity"] is None
    edited = deepcopy(draft["candidate"])
    edited["printed_total_yuan"] = 0
    review = {"expected_revision": 1, "candidate": edited, "review_note": "synthetic correction"}
    response = client.put(f"/v1/drafts/{draft['id']}/review", json=review)
    assert response.status_code == 200
    updated = response.json()
    assert updated["revision"] == 2
    assert updated["candidate"]["printed_total_yuan"] == 0
    assert updated["original_candidate"] == draft["original_candidate"]
    assert updated["evidence"] == draft["evidence"]
    assert client.put(f"/v1/drafts/{draft['id']}/review", json=review).status_code == 409
    replay = create(client, body)
    assert replay.status_code == 200
    assert replay.json() == updated
    changed = deepcopy(body)
    changed["candidate"]["merchant"] = "different synthetic store"
    assert create(client, changed).status_code == 409
    assert len(client.get("/v1/drafts").json()["drafts"]) == 1
    history = client.get(f"/v1/drafts/{draft['id']}/history").json()["revisions"]
    assert [version["revision"] for version in history] == [1, 2]
    assert [version["actor"] for version in history] == ["initial_import", "reviewer"]
    assert history[0]["candidate"]["printed_total_yuan"] == 100
    assert history[1]["candidate"]["printed_total_yuan"] == 0

    # Recreate the repository and HTTP app, with no shared in-memory state.
    reopened = Store(store.data_dir)
    with TestClient(create_app(reopened, TOKEN)) as restarted:
        restarted.headers["Authorization"] = "Bearer " + TOKEN
        assert restarted.get(f"/v1/drafts/{draft['id']}").json() == updated
        assert create(restarted, body).json() == updated
        assert restarted.get(f"/v1/drafts/{draft['id']}/history").json()["revisions"] == history


def test_missing_reference_and_page_number_do_not_write(client, body):
    missing = deepcopy(body)
    missing["evidence"][0]["sha256"] = "0" * 64
    response = create(client, missing)
    assert response.status_code == 422
    assert response.json()["detail"] == "unknown_evidence_reference"
    invalid_page = deepcopy(body)
    invalid_page["evidence"][0]["pages"] = [2]
    assert create(client, invalid_page).status_code == 422
    assert client.get("/v1/drafts").json()["drafts"] == []


def test_parallel_review_accepts_exactly_one_writer(client, store, body):
    draft = create(client, body).json()
    request = ReviewDraft.model_validate({"expected_revision": 1, "candidate": body["candidate"]})

    def writer(_):
        try:
            return store.review(draft["id"], request)["revision"]
        except StoreError as error:
            return error.code

    with ThreadPoolExecutor(max_workers=2) as executor:
        result = list(executor.map(writer, range(2)))
    assert sorted(map(str, result)) == ["2", "revision_conflict"]
    assert len(store.history(draft["id"])) == 2


def test_explicit_page_grouping_never_merges_transactions(client, body):
    first = create(client, body, "first-transaction").json()
    second = create(client, body, "separate-transaction").json()
    assert first["id"] != second["id"]
    assert first["evidence"] == second["evidence"]
    assert len(client.get("/v1/drafts").json()["drafts"]) == 2


def test_no_confirm_or_arithmetic_routes(client, body):
    draft = create(client, body).json()
    assert client.post(f"/v1/drafts/{draft['id']}/confirm").status_code == 404
    # Deliberately inconsistent printed values are preserved as evidence, never reconciled by AI.
    body["candidate"]["printed_total_yuan"] = 999
    assert (
        create(client, body, "inconsistent-print").json()["candidate"]["printed_total_yuan"] == 999
    )


def test_list_bounds_and_missing_records(client):
    assert client.get("/v1/drafts?limit=101").status_code == 422
    assert client.get("/v1/drafts?offset=-1").status_code == 422
    assert client.get("/v1/drafts/absent").status_code == 404
    assert client.get("/v1/drafts/absent/history").status_code == 404


def test_included_discounts_are_preserved_without_double_subtraction(client, body):
    body["candidate"]["printed_total_yuan"] = 149
    body["candidate"]["items"][0]["printed_line_total_yuan"] = 149
    body["candidate"]["adjustments"] = [
        {
            "raw_label": "合成活動：原價249，折100，行金額已扣",
            "direction": "subtract",
            "amount_yuan": 100,
            "amount_effect": "included_in_line_totals",
            "scope": "items",
            "item_positions": [0],
        }
    ]
    candidate = create(client, body).json()["candidate"]
    assert candidate["printed_total_yuan"] == 149
    assert candidate["items"][0]["printed_line_total_yuan"] == 149
    assert candidate["adjustments"][0]["amount_yuan"] == 100
    assert candidate["adjustments"][0]["amount_effect"] == "included_in_line_totals"


def test_canonical_idempotency_and_parallel_creation(client, store, body):
    from pixelreceipt_backend.models import CreateDraft

    model = CreateDraft.model_validate(body)
    with ThreadPoolExecutor(max_workers=2) as executor:
        result = list(executor.map(lambda _: store.create_draft(model, "same-key"), range(2)))
    assert sorted(created for _, created in result) == [False, True]
    assert result[0][0]["id"] == result[1][0]["id"]
    # Explicit defaults and omitted defaults have the same canonical content digest.
    body["candidate"]["adjustments"][0]["amount_effect"] = None
    assert create(client, body, "same-key").status_code == 200
