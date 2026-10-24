"""Removal preserves evidence and prevents recognition and ledger resurrection."""

import asyncio
import json
import sqlite3
from concurrent.futures import ThreadPoolExecutor
from copy import deepcopy
from threading import Barrier

import pytest

from pixelreceipt_backend.intake_store import IntakeStore
from pixelreceipt_backend.ledger_models import ConfirmReceipt, ReviewReceipt
from pixelreceipt_backend.models import CreateDraft
from pixelreceipt_backend.store import Store, StoreError

from .conftest import REVIEWER_TOKEN, TOKEN
from .test_ledger import review_body
from .test_mobile_ingest import intake, worker


def create_intake(store, body, *, linked=False, purpose="purchase"):
    journal = IntakeStore(store)
    record, _ = journal.create(intake(body, purpose), "synthetic-intake")
    if linked:
        journal.complete(record["id"], body["candidate"], {"provider": "synthetic"})
    return journal, journal.get(record["id"])


def delete(client, kind, identifier, revision, key="synthetic-delete"):
    field = "expected_draft_revision" if kind == "intakes" else "expected_revision"
    return client.post(
        f"/v1/{kind}/{identifier}/delete",
        json={field: revision, "reason": "未註明"},
        headers={"Idempotency-Key": key},
    )


@pytest.mark.parametrize(
    "linked,purpose", [(False, "purchase"), (True, "purchase"), (True, "reference")]
)
def test_delete_intake_replay_reopen_backup_retains_evidence(
    client, store, body, png, tmp_path, linked, purpose
):
    journal, record = create_intake(store, body, linked=linked, purpose=purpose)
    revision = 1 if record["draft_id"] else None
    response = delete(client, "intakes", record["id"], revision)
    assert response.status_code == 200, response.text
    tombstone = response.json()
    assert tombstone["status"] == "deleted" and tombstone["polling_finished"] is True
    assert tombstone["input"] == record["input"]
    assert tombstone["evidence_snapshot"] == record["evidence_snapshot"]
    if record["draft_id"]:
        assert tombstone["draft_status"] == "deleted"
        draft = store.get_draft(record["draft_id"])
        assert draft["status"] == "deleted" and draft["revision"] == 2
        assert store.history(draft["id"])[-1]["action"] == "deleted"
        assert store.history(draft["id"])[-1]["review_note"] == "未註明"
    assert client.get("/v1/intakes").json()["intakes"] == []
    assert client.get("/v1/drafts").json()["drafts"] == []
    assert journal.pending() is None
    assert delete(client, "intakes", record["id"], revision).json() == tombstone
    assert delete(client, "intakes", record["id"], revision, "different").status_code == 409
    assert delete(client, "intakes", record["id"], 9).status_code == 409
    assert journal.create(intake(body, purpose), "synthetic-intake")[0] == tombstone
    assert IntakeStore(Store(store.data_dir)).get(record["id"]) == tombstone
    backup = tmp_path / "backup.sqlite3"
    with sqlite3.connect(store.database) as source, sqlite3.connect(backup) as destination:
        source.backup(destination)
    with sqlite3.connect(backup) as connection:
        assert connection.execute("PRAGMA user_version").fetchone()[0] == 4
        assert connection.execute("SELECT status,polling_finished FROM intakes").fetchone() == (
            "deleted",
            1,
        )
        assert connection.execute("SELECT COUNT(*) FROM mutations").fetchone()[0] == 1
        operation = json.loads(connection.execute("SELECT response FROM mutations").fetchone()[0])
        assert operation["request"]["reason"] == "未註明"
        assert operation["response"] == tombstone
    assert store.get_evidence(body["evidence"][0]["sha256"], content=True)[1] == png


@pytest.mark.parametrize("kind", ["intakes", "drafts"])
def test_delete_requires_reviewer_and_strict_idempotent_body(client, store, body, kind):
    journal, record = create_intake(store, body, linked=True)
    identifier = record["id"] if kind == "intakes" else record["draft_id"]
    field = "expected_draft_revision" if kind == "intakes" else "expected_revision"
    path = f"/v1/{kind}/{identifier}/delete"
    client.headers["Authorization"] = "Bearer " + TOKEN
    assert delete(client, kind, identifier, 1).status_code == 403
    client.headers.pop("Authorization")
    assert delete(client, kind, identifier, 1).status_code == 401
    client.headers["Authorization"] = "Bearer " + REVIEWER_TOKEN
    assert client.post(path, json={field: 1, "reason": "test"}).status_code == 422
    for value in [
        {"reason": "test"},
        {field: True, "reason": "test"},
        {field: "1", "reason": "test"},
        {field: 1, "reason": " "},
        {field: 1, "reason": "x", "extra": 1},
    ]:
        assert (
            client.post(path, json=value, headers={"Idempotency-Key": "invalid"}).status_code == 422
        )
    assert journal.get(record["id"])["status"] == "succeeded"
    assert delete(client, kind, identifier, 2).status_code == 409
    assert delete(client, kind, "absent", 1).status_code == 404


def test_delete_draft_uses_authoritative_relation_and_key(client, store, body):
    journal, record = create_intake(store, body, linked=True)
    forged = deepcopy(body)
    forged["source"]["label"] = "mobile-intake:" + record["id"]
    draft, _ = store.create_draft(CreateDraft.model_validate(forged), "unrelated")
    deleted = delete(client, "drafts", draft["id"], 1)
    assert deleted.status_code == 200 and deleted.json()["status"] == "deleted"
    assert journal.get(record["id"])["status"] == "succeeded"
    assert delete(client, "drafts", record["draft_id"], 1, "linked-delete").status_code == 200
    assert journal.get(record["id"])["status"] == "deleted"
    assert delete(client, "drafts", draft["id"], 1).json() == deleted.json()
    assert (
        store.create_draft(CreateDraft.model_validate(forged), "unrelated")[0]["status"]
        == "deleted"
    )
    assert delete(client, "intakes", record["id"], None, "linked-delete").status_code == 409


def test_expected_null_only_without_linked_draft(client, store, body):
    journal, record = create_intake(store, body)
    assert delete(client, "intakes", record["id"], 1).status_code == 409
    journal.complete(record["id"], body["candidate"], {})
    assert delete(client, "intakes", record["id"], None).status_code == 409
    assert journal.get(record["id"])["status"] == "succeeded"


@pytest.mark.parametrize("kind", ["intakes", "drafts"])
@pytest.mark.parametrize("voided", [False, True])
def test_posted_and_voided_cannot_be_deleted(client, store, body, kind, voided):
    journal, record = create_intake(store, body, linked=True)
    store.review_receipt(record["draft_id"], ReviewReceipt.model_validate(review_body(body)))
    ledger, _ = store.confirm(record["draft_id"], ConfirmReceipt(expected_revision=2), "confirm")
    if voided:
        assert (
            client.post(
                f"/v1/ledger/{ledger['id']}/void",
                json={"expected_revision": 1, "reason": "synthetic"},
                headers={"Idempotency-Key": "void"},
            ).status_code
            == 200
        )
    identifier = record["id"] if kind == "intakes" else record["draft_id"]
    assert (
        delete(
            client, kind, identifier, store.get_draft(record["draft_id"])["revision"]
        ).status_code
        == 409
    )
    assert store.get_ledger(ledger["id"])["status"] == ("voided" if voided else "active")
    assert journal.get(record["id"])["status"] == "succeeded"


def test_stale_updates_and_completion_cannot_resurrect(client, store, body):
    journal, record = create_intake(store, body)
    tombstone = delete(client, "intakes", record["id"], None).json()
    journal.update(record["id"], status="failed", error_code="late-error", polling_finished=0)
    journal.complete(record["id"], body["candidate"], {"provider": "late"})
    assert journal.get(record["id"]) == tombstone
    with store.connection() as connection:
        assert connection.execute("SELECT COUNT(*) FROM drafts").fetchone()[0] == 0


def test_delete_vs_complete_has_no_orphan(client, store, body):
    journal, record = create_intake(store, body)
    barrier = Barrier(2)

    def complete():
        barrier.wait()
        journal.complete(record["id"], body["candidate"], {})

    def remove():
        barrier.wait()
        return delete(client, "intakes", record["id"], None).status_code

    with ThreadPoolExecutor(max_workers=2) as pool:
        completion = pool.submit(complete)
        removal = pool.submit(remove)
        status = removal.result()
        completion.result()
    current = journal.get(record["id"])
    assert status in {200, 409}
    assert current["status"] == ("deleted" if status == 200 else "succeeded")
    with store.connection() as connection:
        assert connection.execute("SELECT COUNT(*) FROM drafts").fetchone()[0] == (
            0 if status == 200 else 1
        )


def test_delete_vs_confirm_has_one_winner(client, store, body):
    journal, record = create_intake(store, body, linked=True)
    store.review_receipt(record["draft_id"], ReviewReceipt.model_validate(review_body(body)))
    barrier = Barrier(2)

    def confirm():
        barrier.wait()
        try:
            store.confirm(record["draft_id"], ConfirmReceipt(expected_revision=2), "confirm-race")
            return 201
        except StoreError as error:
            return error.status

    def remove():
        barrier.wait()
        return delete(client, "intakes", record["id"], 2).status_code

    with ThreadPoolExecutor(max_workers=2) as pool:
        confirmation = pool.submit(confirm)
        deletion = pool.submit(remove)
        outcomes = (confirmation.result(), deletion.result())
    assert outcomes in {(201, 409), (409, 200)}
    assert journal.get(record["id"])["draft_status"] == (
        "confirmed" if outcomes[0] == 201 else "deleted"
    )


def test_same_key_concurrent_delete_increments_once(client, store, body):
    journal, record = create_intake(store, body, linked=True)
    with ThreadPoolExecutor(max_workers=2) as pool:
        responses = list(
            pool.map(lambda _: delete(client, "drafts", record["draft_id"], 1), range(2))
        )
    assert [response.status_code for response in responses] == [200, 200]
    assert responses[0].json() == responses[1].json()
    assert responses[0].json()["revision"] == 2
    assert len(store.history(record["draft_id"])) == 2
    assert journal.get(record["id"])["status"] == "deleted"


@pytest.mark.parametrize("boundary", ["explain", "submit", "result", "error"])
def test_worker_late_network_responses_cannot_resurrect(client, store, body, boundary):
    journal, record = create_intake(store, body)
    runner, transport = worker(store, body)
    original = transport.request
    calls = []

    async def request(method, path, payload=None):
        calls.append((method, path))
        if (
            (boundary == "explain" and path.endswith("explain"))
            or (boundary == "submit" and path == "/v1/queue")
            or (boundary in {"result", "error"} and path.endswith("/result"))
        ):
            assert delete(client, "intakes", record["id"], None).status_code == 200
            if boundary == "error":
                from pixelreceipt_backend.recognition import RecognitionError

                raise RecognitionError("broker_transport_unknown")
        if path == "/v1/routes/explain":
            return {"selected_target_id": "synthetic"}
        if path == "/v1/queue":
            transport.job = payload["request_key"]
            return {"request_key": transport.job, "state": "queued"}
        return await original(method, path, payload)

    transport.request = request
    asyncio.run(runner.run_once())
    assert journal.get(record["id"])["status"] == "deleted"
    assert journal.get(record["id"])["polling_finished"] is True
    assert journal.pending() is None
    if boundary == "explain":
        assert ("POST", "/v1/queue") not in calls
    with store.connection() as connection:
        assert connection.execute("SELECT COUNT(*) FROM drafts").fetchone()[0] == 0


def test_atomic_complete_preserves_derived_key_and_rolls_back_link_failure(store, body):
    journal, record = create_intake(store, body)
    historical = CreateDraft.model_validate(
        {
            "candidate": body["candidate"],
            "evidence": body["evidence"],
            "source": {
                "kind": "ai_candidate",
                "label": "mobile-intake:" + record["id"],
                "provider": None,
                "model": None,
            },
        }
    )
    existing, _ = store.create_draft(historical, "intake:" + record["id"])
    journal.complete(record["id"], body["candidate"], {})
    assert journal.get(record["id"])["draft_id"] == existing["id"]
    second, _ = journal.create(intake(body), "second")
    with store.connection(write=True) as connection:
        connection.execute(
            "CREATE TRIGGER fail_link BEFORE UPDATE OF draft_id ON intakes "
            "WHEN NEW.draft_id IS NOT NULL AND OLD.id='" + second["id"] + "' "
            "BEGIN SELECT RAISE(ABORT, 'synthetic-link-failure'); END"
        )
    with pytest.raises(sqlite3.IntegrityError, match="synthetic-link-failure"):
        journal.complete(second["id"], body["candidate"], {})
    with store.connection() as connection:
        assert connection.execute("SELECT COUNT(*) FROM drafts").fetchone()[0] == 1
    assert journal.get(second["id"])["status"] == "queued"


@pytest.mark.parametrize("version", [1, 2])
def test_delete_vs_review_preserves_exact_revision_and_history(client, store, body, version):
    journal, record = create_intake(store, body, linked=True)
    barrier = Barrier(2)

    def review():
        barrier.wait()
        payload = (
            review_body(body)
            if version == 2
            else {"expected_revision": 1, "candidate": body["candidate"]}
        )
        return client.put(
            f"/v{version}/drafts/{record['draft_id']}/review", json=payload
        ).status_code

    def remove():
        barrier.wait()
        return delete(client, "intakes", record["id"], 1).status_code

    with ThreadPoolExecutor(max_workers=2) as pool:
        reviewed = pool.submit(review)
        deleted = pool.submit(remove)
        outcomes = (reviewed.result(), deleted.result())
    assert outcomes in {(200, 409), (409, 200)}
    current = store.get_draft(record["draft_id"])
    assert current["revision"] == 2
    assert current["status"] == ("needs_review" if outcomes[0] == 200 else "deleted")
    assert len(store.history(current["id"])) == 2
    assert journal.get(record["id"])["status"] == ("succeeded" if outcomes[0] == 200 else "deleted")


def test_tombstones_are_filtered_before_pagination(client, store, body):
    journal = IntakeStore(store)
    records = []
    for index in range(3):
        record, _ = journal.create(intake(body), f"page-{index}")
        journal.complete(record["id"], body["candidate"], {})
        records.append(journal.get(record["id"]))
    assert delete(client, "intakes", records[1]["id"], 1).status_code == 200
    assert [row["id"] for row in journal.list(1, 0)] == [records[2]["id"]]
    assert [row["id"] for row in journal.list(1, 1)] == [records[0]["id"]]
    assert [row["id"] for row in store.list_drafts(1, 0)] == [records[0]["draft_id"]]
    assert [row["id"] for row in store.list_drafts(1, 1)] == [records[2]["draft_id"]]


def test_worker_cannot_dispatch_stale_deleted_row(client, store, body):
    journal, record = create_intake(store, body)
    stale = journal.pending()
    assert delete(client, "intakes", record["id"], None).status_code == 200
    runner, transport = worker(store, body)
    asyncio.run(runner.step(stale))
    assert transport.calls == []
    assert journal.get(record["id"])["status"] == "deleted"


@pytest.mark.parametrize("forged", [False, True])
def test_legacy_unlinked_derived_key_requires_reconciliation_before_deletion(
    client, store, body, forged
):
    journal, record = create_intake(store, body)
    original = deepcopy(body)
    original["source"] = {
        "kind": "ai_candidate",
        "label": "mobile-intake:" + record["id"],
        "provider": None,
        "model": None,
    }
    if forged:
        original["candidate"]["merchant"] = "forged source label cannot establish ownership"
    draft, _ = store.create_draft(CreateDraft.model_validate(original), "intake:" + record["id"])
    for kind, identifier, revision in [("intakes", record["id"], None), ("drafts", draft["id"], 1)]:
        response = delete(client, kind, identifier, revision)
        assert response.status_code == 409
        assert response.json()["detail"] == "intake_draft_reconciliation_required"
    assert journal.get(record["id"])["status"] == "queued"
    assert store.get_draft(draft["id"])["status"] == "needs_review"
    if forged:
        with pytest.raises(StoreError, match="idempotency_key_content_conflict"):
            journal.complete(record["id"], body["candidate"], {})
        assert journal.get(record["id"])["draft_id"] is None
    else:
        journal.complete(record["id"], body["candidate"], {})
        assert journal.get(record["id"])["draft_id"] == draft["id"]
        assert delete(client, "intakes", record["id"], 1).status_code == 200
        assert store.get_draft(draft["id"])["status"] == "deleted"


def test_delete_intake_journal_replays_legacy_plain_response(client, store, body):
    journal, record = create_intake(store, body)
    tombstone = delete(client, "intakes", record["id"], None).json()
    with store.connection(write=True) as connection:
        connection.execute("UPDATE mutations SET response=?", (json.dumps(tombstone),))
    assert delete(client, "intakes", record["id"], None).json() == tombstone
    assert IntakeStore(Store(store.data_dir)).get(record["id"]) == tombstone
