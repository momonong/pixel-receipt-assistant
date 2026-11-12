"""Synthetic contracts for revisioned labels and evidence-free manual accounting."""

import json
from copy import deepcopy

import pytest

from .conftest import TOKEN
from .test_drafts import create
from .test_prices import receipt


def analysis():
    return {
        "brand": None,
        "branch": None,
        "address": None,
        "city": None,
        "district": None,
        "channel": "unknown",
        "transaction_time": None,
        "date_source": "user",
        "items": [
            {
                "item_position": 0,
                "category": "food",
                "subcategory": None,
                "meal": "dinner",
                "meal_group": None,
            }
        ],
    }


def manual(**changes):
    return {
        "receipt_date": "2026-11-12",
        "merchant": None,
        "description": None,
        "amount_yuan": 300,
        "purpose": "self",
        "notes": None,
        "analysis": analysis(),
        **changes,
    }


def test_manual_atomic_durable_replay_and_purposes(client, store):
    response = client.post(
        "/v1/manual-entries", json=manual(), headers={"Idempotency-Key": "manual-1"}
    )
    assert response.status_code == 201, response.text
    entry = response.json()
    assert entry["snapshot"]["source"]["kind"] == "manual"
    assert entry["snapshot"]["evidence"] == []
    assert entry["snapshot"]["calculation"]["personal_yuan"] == 300
    assert entry["snapshot"]["calculation"]["advance_yuan"] == 0
    assert (
        client.post(
            "/v1/manual-entries", json=manual(), headers={"Idempotency-Key": "manual-1"}
        ).json()
        == entry
    )
    assert (
        client.post(
            "/v1/manual-entries",
            json=manual(amount_yuan=301),
            headers={"Idempotency-Key": "manual-1"},
        ).status_code
        == 409
    )
    advance = client.post(
        "/v1/manual-entries",
        json=manual(amount_yuan=30, purpose="advance"),
        headers={"Idempotency-Key": "manual-2"},
    ).json()
    assert advance["snapshot"]["calculation"]["advance_yuan"] == 30
    assert client.get("/v1/overview").json()["personal_yuan"] == 300
    operation = client.get("/v1/analysis-operations/manual-1").json()
    assert operation == {
        "operation_id": "manual-1",
        "kind": "manual_entry",
        "target_id": entry["id"],
        "result": entry,
    }
    with store.connection() as connection:
        assert connection.execute("SELECT COUNT(*) FROM evidence").fetchone()[0] == 0
        assert connection.execute("SELECT COUNT(*) FROM reviews").fetchone()[0] == 4


def test_manual_failure_rolls_back_every_row(client, store):
    with store.connection(write=True) as connection:
        connection.execute(
            "CREATE TRIGGER fail_labels BEFORE INSERT ON insight_annotations "
            "BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END"
        )
    from pixelreceipt_backend.insight_models import ManualEntry
    from pixelreceipt_backend.insight_store import InsightStore

    with pytest.raises(Exception, match="synthetic failure"):
        InsightStore(store).create_manual(ManualEntry.model_validate(manual()), "rollback-1")
    with store.connection() as connection:
        for table in (
            "drafts",
            "reviews",
            "ledger",
            "ledger_events",
            "mutations",
            "insight_history",
        ):
            assert connection.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0] == 0


def test_metadata_cas_history_complete_positions_and_immutable_snapshot(client, body, store):
    ledger = receipt(client, body)
    url = f"/v1/ledger/{ledger['id']}/insights"
    baseline = client.get(url).json()
    assert baseline["revision"] == 0
    assert baseline["document"]["city"] is None
    request = {"expected_revision": 0, "document": analysis()}
    request["document"]["brand"] = " 合成品牌 "
    saved = client.put(url, json=request, headers={"Idempotency-Key": "labels-1"})
    assert saved.status_code == 200, saved.text
    assert saved.json()["revision"] == 1
    assert saved.json()["document"]["brand"] == "合成品牌"
    assert saved.json()["sources"]["brand"] == "user"
    assert (
        client.put(url, json=request, headers={"Idempotency-Key": "labels-1"}).json()
        == saved.json()
    )
    assert client.put(url, json=request, headers={"Idempotency-Key": "labels-2"}).status_code == 409
    request["expected_revision"] = 1
    request["document"]["items"] = []
    assert client.put(url, json=request, headers={"Idempotency-Key": "labels-3"}).status_code == 422
    assert store.get_ledger(ledger["id"]) == ledger
    with store.connection() as connection:
        assert connection.execute("SELECT COUNT(*) FROM insight_history").fetchone()[0] == 1
    client.post(
        f"/v1/ledger/{ledger['id']}/void",
        json={"expected_revision": 1, "reason": "test"},
        headers={"Idempotency-Key": "void-test"},
    )
    request["document"] = analysis()
    assert client.put(url, json=request, headers={"Idempotency-Key": "labels-4"}).status_code == 409
    assert client.get(url).json() == saved.json()


def test_metadata_read_has_no_write_or_inferred_location_and_explicit_notes_win(
    client, body, store
):
    body = deepcopy(body)
    body["candidate"]["merchant"] = "台南合成店"
    draft = create(client, body).json()
    url = f"/v1/drafts/{draft['id']}/insights"
    response = client.get(url)
    assert response.status_code == 200
    value = response.json()
    assert value["document"]["city"] is None
    assert value["document"]["transaction_time"] is None
    assert value["document"]["items"][0]["meal"] in ("unknown", "not_applicable")
    with store.connection() as connection:
        assert connection.execute("SELECT COUNT(*) FROM insight_annotations").fetchone()[0] == 0
    from pixelreceipt_backend.insight_store import InsightStore

    with store.connection() as connection:
        suggested = InsightStore(store).draft_document(connection, draft, notes=["今天晚餐"])
    assert suggested["document"]["items"][0]["meal"] == "dinner"
    assert suggested["sources"]["items"][0]["meal"] == "notes"


def test_analysis_auth_unknown_lookup_and_validation(client):
    assert client.get("/v1/analysis-operations/not-sent").status_code == 404
    for body in (
        manual(amount_yuan=True),
        manual(amount_yuan=0),
        manual(amount_yuan=2**63),
        manual(analysis={**analysis(), "city": "x" * 501}),
    ):
        assert (
            client.post(
                "/v1/manual-entries", json=body, headers={"Idempotency-Key": "invalid"}
            ).status_code
            == 422
        )
    client.headers["Authorization"] = "Bearer " + TOKEN
    assert (
        client.post(
            "/v1/manual-entries", json=manual(), headers={"Idempotency-Key": "forbidden"}
        ).status_code
        == 403
    )
    assert client.get("/v1/analysis-operations/not-sent").status_code == 403
    del client.headers["Authorization"]
    assert client.get("/v1/analysis-operations/not-sent").status_code == 401


def test_v4_migration_preserves_legacy_rows_auth_and_blobs(client, body, store, png):
    from pixelreceipt_backend.store import Store

    ledger = receipt(client, body)
    store.create_session("synthetic-session", "synthetic-csrf")
    with store.connection(write=True) as connection:
        for table in ("ledger_metadata", "insight_history", "insight_annotations"):
            connection.execute(f"DROP TABLE IF EXISTS {table}")
        connection.execute("PRAGMA user_version=4")
        tables = [
            r[0] for r in connection.execute("SELECT name FROM sqlite_master WHERE type='table'")
        ]
        previous = {
            table: [tuple(r) for r in connection.execute(f"SELECT * FROM {table}")]
            for table in tables
        }
    reopened = Store(store.data_dir)
    with reopened.connection() as connection:
        assert connection.execute("PRAGMA user_version").fetchone()[0] == 5
        assert previous == {
            table: [tuple(r) for r in connection.execute(f"SELECT * FROM {table}")]
            for table in tables
        }
    assert reopened.get_ledger(ledger["id"]) == ledger
    assert reopened.get_evidence(body["evidence"][0]["sha256"], content=True)[1] == png
    assert reopened.get_session("synthetic-session")["csrf_token"] == "synthetic-csrf"


def test_price_operations_preserve_original_results_and_hide_unrelated_mutations(client, body):
    from .test_prices import product

    ledger = receipt(client, body)
    created = product(client, "journal-product")
    assert client.get("/v1/analysis-operations/journal-product").json() == {
        "operation_id": "journal-product",
        "kind": "product_create",
        "target_id": created["id"],
        "result": created,
    }
    url = f"/v1/ledger/{ledger['id']}/price-review"
    request = {"expected_revision": 0, "item_mappings": [], "adjustment_decisions": []}
    saved = client.put(url, json=request, headers={"Idempotency-Key": "journal-price"})
    assert saved.status_code == 200
    assert (
        client.put(url, json=request, headers={"Idempotency-Key": "journal-price"}).json()
        == saved.json()
    )
    assert client.get("/v1/analysis-operations/journal-price").json() == {
        "operation_id": "journal-price",
        "kind": "price_review",
        "target_id": ledger["id"],
        "result": saved.json(),
    }
    assert client.get("/v1/analysis-operations/confirm-receipt").status_code == 404


def test_optional_recognition_context_preserves_candidate_and_note_review(store, body):
    from pixelreceipt_backend.insight_store import InsightStore
    from pixelreceipt_backend.recognition import RecognitionWorker

    from .test_recognition_note_review import NEW_PROMPT, NoNetwork, outcome, queued

    journal, row = queued(store, body, NEW_PROMPT)
    response = outcome(body["candidate"])
    document = json.loads(response["result"]["text"])
    document["analysis_context"] = {
        "printed_address": "台南市中西區合成路1號",
        "printed_city": "台南市",
        "printed_district": "中西區",
        "printed_transaction_time": "18:25",
        "items": [{"item_position": 0, "category": "food", "meal": "lunch"}],
    }
    response["result"]["text"] = json.dumps(document)
    RecognitionWorker(store, transport=NoNetwork()).accept_result(row, response)
    saved = journal.get(row["id"])
    assert saved["result"]["note_review"]["status"] == "matched"
    assert saved["result"]["analysis_context"]["printed_city"] == "台南市"
    draft = store.get_draft(saved["draft_id"])
    assert draft["candidate"]["schema"] == "pixelreceipt-central-candidate-1"
    with store.connection() as connection:
        metadata = InsightStore(store).draft_document(connection, draft)
    assert metadata["document"]["city"] == "台南市"
    assert metadata["document"]["items"][0]["meal"] == "dinner"
    assert metadata["sources"]["city"] == "receipt"
    assert metadata["sources"]["items"][0]["meal"] == "rule"


def test_backup_v5_roundtrip_preserves_labels_manual_and_operations(client, store, tmp_path):
    import importlib.util
    from pathlib import Path

    from pixelreceipt_backend.store import Store

    spec = importlib.util.spec_from_file_location(
        "backup_v5", Path(__file__).resolve().parents[2] / "deploy/receipt_backup.py"
    )
    backup = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(backup)
    entry = client.post(
        "/v1/manual-entries", json=manual(), headers={"Idempotency-Key": "backup-manual"}
    ).json()
    store.create_session("synthetic-backup-session", "synthetic-csrf")
    with store.connection() as connection:
        expected = {
            table: [tuple(row) for row in connection.execute(f"SELECT * FROM {table}")]
            for table in (
                "drafts",
                "reviews",
                "ledger",
                "ledger_events",
                "mutations",
                "ledger_metadata",
                "insight_annotations",
                "insight_history",
            )
        }
    assert backup.snapshot(store.data_dir, tmp_path / "snapshot")["database_version"] == 5
    assert backup.verify(tmp_path / "snapshot")["database_version"] == 5
    assert backup.restore(tmp_path / "snapshot", tmp_path / "restored")["database_version"] == 5
    reopened = Store(tmp_path / "restored")
    assert reopened.get_ledger(entry["id"]) == entry
    assert reopened.ledger_id() == store.ledger_id()
    assert reopened.get_session("synthetic-backup-session") is None
    with reopened.connection() as connection:
        assert expected == {
            table: [tuple(row) for row in connection.execute(f"SELECT * FROM {table}")]
            for table in expected
        }


def test_meal_note_does_not_reclassify_unrelated_household_line(client, body, store):
    from pixelreceipt_backend.insight_store import InsightStore

    body = deepcopy(body)
    body["candidate"]["items"] = [
        {"raw_name": "合成便當", "quantity": 1, "printed_line_total_yuan": 100},
        {"raw_name": "合成衛生紙", "quantity": 1, "printed_line_total_yuan": 50},
    ]
    body["candidate"]["printed_total_yuan"] = 150
    draft = create(client, body).json()
    with store.connection() as connection:
        value = InsightStore(store).draft_document(connection, draft, notes=["合成便當是晚餐"])
    assert value["document"]["items"][0]["meal"] == "dinner"
    assert value["document"]["items"][1]["category"] == "household"
    assert value["document"]["items"][1]["meal"] == "not_applicable"


def test_manual_cannot_claim_receipt_date_provenance(client):
    document = analysis()
    document["date_source"] = "receipt"
    response = client.post(
        "/v1/manual-entries",
        json=manual(analysis=document),
        headers={"Idempotency-Key": "false-receipt"},
    )
    assert response.status_code == 422


def test_draft_and_ledger_share_annotation_cas_and_sources_cannot_be_forged(client, body):
    ledger = receipt(client, body)
    draft_url = f"/v1/drafts/{ledger['draft_id']}/insights"
    ledger_url = f"/v1/ledger/{ledger['id']}/insights"
    request = {"expected_revision": 0, "document": analysis()}
    saved = client.put(draft_url, json=request, headers={"Idempotency-Key": "shared-labels"})
    assert saved.status_code == 200
    assert client.get(ledger_url).json() == saved.json()
    assert (
        client.put(
            ledger_url, json=request, headers={"Idempotency-Key": "conflicting-labels"}
        ).status_code
        == 409
    )
    request.update(expected_revision=1, sources={"brand": "receipt"})
    assert (
        client.put(
            ledger_url, json=request, headers={"Idempotency-Key": "forged-source"}
        ).status_code
        == 422
    )


@pytest.mark.parametrize("ledger_route", [False, True])
def test_evidence_free_manual_metadata_cannot_claim_receipt_after_creation(
    client, store, ledger_route
):
    entry = client.post(
        "/v1/manual-entries", json=manual(), headers={"Idempotency-Key": "manual-provenance"}
    ).json()
    target = f"ledger/{entry['id']}" if ledger_route else f"drafts/{entry['draft_id']}"
    url = f"/v1/{target}/insights"
    original = client.get(url).json()
    document = deepcopy(original["document"])
    document["date_source"] = "receipt"
    response = client.put(
        url,
        json={"expected_revision": original["revision"], "document": document},
        headers={"Idempotency-Key": "manual-invalid-receipt"},
    )
    assert response.status_code == 422, response.text
    assert client.get(url).json() == original
    assert store.get_ledger(entry["id"]) == entry


@pytest.mark.parametrize("ledger_route", [False, True])
def test_legacy_manual_draft_with_receipt_evidence_can_save_receipt_date_source(
    client, body, ledger_route
):
    body = deepcopy(body)
    body["source"]["kind"] = "manual"
    if ledger_route:
        entry = receipt(client, body, key="legacy-manual-evidence")
        target = f"ledger/{entry['id']}"
    else:
        draft = create(client, body).json()
        target = f"drafts/{draft['id']}"
    document = analysis()
    document["date_source"] = "receipt"
    response = client.put(
        f"/v1/{target}/insights",
        json={"expected_revision": 0, "document": document},
        headers={"Idempotency-Key": "legacy-manual-with-evidence"},
    )
    assert response.status_code == 200
    assert response.json()["document"]["date_source"] == "receipt"


def labeled_two_item_draft(client, body):
    body = deepcopy(body)
    body["candidate"]["items"] = [
        {"raw_name": "合成便當", "quantity": 1, "printed_line_total_yuan": 100},
        {"raw_name": "合成衛生紙", "quantity": 1, "printed_line_total_yuan": 50},
    ]
    body["candidate"]["printed_total_yuan"] = 150
    body["candidate"]["adjustments"] = []
    draft = create(client, body).json()
    document = analysis()
    document["brand"] = "人工品牌"
    document["items"].append(
        {
            "item_position": 1,
            "category": "leisure",
            "subcategory": "人工舊標籤",
            "meal": "not_applicable",
            "meal_group": None,
        }
    )
    saved = client.put(
        f"/v1/drafts/{draft['id']}/insights",
        json={"expected_revision": 0, "document": document},
        headers={"Idempotency-Key": "bound-item-labels"},
    )
    assert saved.status_code == 200
    return draft, saved.json()


@pytest.mark.parametrize("positions", [[1], [1, 0]])
def test_review_item_delete_or_reorder_does_not_transfer_labels_and_invalidates_cas(
    client, body, store, positions
):
    draft, saved = labeled_two_item_draft(client, body)
    candidate = deepcopy(draft["candidate"])
    candidate["items"] = [candidate["items"][position] for position in positions]
    candidate["printed_total_yuan"] = sum(
        item["printed_line_total_yuan"] for item in candidate["items"]
    )
    reviewed = client.put(
        f"/v1/drafts/{draft['id']}/review",
        json={"expected_revision": 1, "candidate": candidate},
    )
    assert reviewed.status_code == 200
    url = f"/v1/drafts/{draft['id']}/insights"
    current = client.get(url).json()
    assert current["document"]["items"][0]["category"] == "household"
    assert current["sources"]["items"][0]["category"] == "rule"
    assert current["document"]["items"][0]["subcategory"] is None
    assert current["document"]["brand"] == "人工品牌"
    assert current["revision"] > saved["revision"]
    with store.connection() as connection:
        assert connection.execute("SELECT COUNT(*) FROM insight_history").fetchone()[0] == 1
    stale = client.put(
        url,
        json={"expected_revision": saved["revision"], "document": current["document"]},
        headers={"Idempotency-Key": "stale-item-labels"},
    )
    assert stale.status_code == 409
    fresh = client.put(
        url,
        json={"expected_revision": current["revision"], "document": current["document"]},
        headers={"Idempotency-Key": "fresh-item-labels"},
    )
    assert fresh.status_code == 200
    assert fresh.json()["revision"] == current["revision"] + 1


def test_item_changes_then_revert_still_invalidate_old_label_revision(client, body):
    draft, saved = labeled_two_item_draft(client, body)
    candidate = deepcopy(draft["candidate"])
    candidate["items"].reverse()
    for revision, value in ((1, candidate), (2, draft["candidate"])):
        assert (
            client.put(
                f"/v1/drafts/{draft['id']}/review",
                json={"expected_revision": revision, "candidate": value},
            ).status_code
            == 200
        )
    current = client.get(f"/v1/drafts/{draft['id']}/insights").json()
    assert current["revision"] >= saved["revision"] + 2
    assert current["sources"]["items"][0]["category"] != "user"


def test_non_item_review_changes_preserve_saved_labels_and_confirmation_keeps_binding(client, body):
    draft, saved = labeled_two_item_draft(client, body)
    candidate = deepcopy(draft["candidate"])
    candidate["merchant"] = "修正店名"
    assert (
        client.put(
            f"/v1/drafts/{draft['id']}/review",
            json={"expected_revision": 1, "candidate": candidate},
        ).status_code
        == 200
    )
    url = f"/v1/drafts/{draft['id']}/insights"
    assert client.get(url).json() == saved
    review = {
        "expected_revision": 2,
        "candidate": candidate,
        "assembly_status": "complete_by_user",
        "line_decisions": [
            {"item_position": position, "method": "whole_line", "whole_purpose": "self"}
            for position in range(2)
        ],
        "adjustment_decisions": [],
    }
    assert client.put(f"/v2/drafts/{draft['id']}/review", json=review).status_code == 200
    review["expected_revision"] = 3
    review["line_decisions"][0]["whole_purpose"] = "gift"
    assert client.put(f"/v2/drafts/{draft['id']}/review", json=review).status_code == 200
    assert client.get(url).json() == saved
    confirmed = client.post(
        f"/v2/drafts/{draft['id']}/confirm",
        json={"expected_revision": 4},
        headers={"Idempotency-Key": "binding-confirm"},
    )
    assert confirmed.status_code == 201
    metadata = client.get(f"/v1/ledger/{confirmed.json()['id']}/insights").json()
    assert metadata["document"] == saved["document"]
    assert metadata["revision"] == saved["revision"]


def test_changed_item_basis_drops_original_recognition_position_suggestions(client, body, store):
    from pixelreceipt_backend.recognition import RecognitionWorker

    from .test_recognition_note_review import NEW_PROMPT, NoNetwork, outcome, queued

    candidate = deepcopy(body["candidate"])
    candidate["items"] = [
        {"raw_name": "合成便當", "quantity": 1, "printed_line_total_yuan": 100},
        {"raw_name": "合成衛生紙", "quantity": 1, "printed_line_total_yuan": 50},
    ]
    candidate["printed_total_yuan"] = 150
    candidate["adjustments"] = []
    journal, row = queued(store, body, NEW_PROMPT)
    response = outcome(candidate)
    wrapped = json.loads(response["result"]["text"])
    wrapped["analysis_context"] = {
        "items": [
            {"item_position": 0, "category": "food"},
            {"item_position": 1, "category": "household"},
        ]
    }
    response["result"]["text"] = json.dumps(wrapped)
    RecognitionWorker(store, transport=NoNetwork()).accept_result(row, response)
    draft_id = journal.get(row["id"])["draft_id"]
    original = client.get(f"/v1/drafts/{draft_id}/insights").json()
    assert (
        client.put(
            f"/v1/drafts/{draft_id}/insights",
            json={"expected_revision": 0, "document": original["document"]},
            headers={"Idempotency-Key": "recognition-bound-labels"},
        ).status_code
        == 200
    )
    candidate["items"] = candidate["items"][1:]
    candidate["printed_total_yuan"] = 50
    assert (
        client.put(
            f"/v1/drafts/{draft_id}/review",
            json={"expected_revision": 1, "candidate": candidate},
        ).status_code
        == 200
    )
    current = client.get(f"/v1/drafts/{draft_id}/insights").json()
    assert current["document"]["items"][0]["category"] == "household"


def test_changed_item_binding_survives_confirmation_and_projection_reads_do_not_write(
    client, body, store
):
    draft, saved = labeled_two_item_draft(client, body)
    candidate = deepcopy(draft["candidate"])
    candidate["items"] = candidate["items"][1:]
    candidate["printed_total_yuan"] = 50
    assert (
        client.put(
            f"/v1/drafts/{draft['id']}/review",
            json={"expected_revision": 1, "candidate": candidate},
        ).status_code
        == 200
    )
    review = {
        "expected_revision": 2,
        "candidate": candidate,
        "assembly_status": "complete_by_user",
        "line_decisions": [{"item_position": 0, "method": "whole_line", "whole_purpose": "self"}],
        "adjustment_decisions": [],
    }
    assert client.put(f"/v2/drafts/{draft['id']}/review", json=review).status_code == 200
    confirmed = client.post(
        f"/v2/drafts/{draft['id']}/confirm",
        json={"expected_revision": 3},
        headers={"Idempotency-Key": "changed-binding-confirm"},
    ).json()
    tables = (
        "drafts",
        "reviews",
        "ledger",
        "ledger_events",
        "insight_annotations",
        "insight_history",
        "mutations",
    )
    with store.connection() as connection:
        before = {
            table: [tuple(row) for row in connection.execute(f"SELECT * FROM {table}")]
            for table in tables
        }
    draft_metadata = client.get(f"/v1/drafts/{draft['id']}/insights").json()
    ledger_metadata = client.get(f"/v1/ledger/{confirmed['id']}/insights").json()
    assert draft_metadata == ledger_metadata
    assert ledger_metadata["revision"] == saved["revision"] + 1
    assert ledger_metadata["document"]["items"][0]["category"] == "household"
    result = client.get("/v1/insights").json()
    assert (
        next(group for group in result["groups"]["categories"] if group["key"] == "household")[
            "personal_yuan"
        ]
        == 50
    )
    with store.connection() as connection:
        assert before == {
            table: [tuple(row) for row in connection.execute(f"SELECT * FROM {table}")]
            for table in tables
        }


@pytest.mark.parametrize("source_kind", ["ai_candidate", "imported"])
@pytest.mark.parametrize(
    ("original_date", "reviewed_date", "expected_source"),
    [
        (None, "2026-11-12", "user"),
        ("2026-11-01", "2026-11-12", "user"),
        ("2026-11-01", "2026-11-01", "receipt"),
    ],
)
def test_reviewed_date_provenance_uses_original_receipt_facts(
    client, body, source_kind, original_date, reviewed_date, expected_source
):
    body = deepcopy(body)
    body["source"]["kind"] = source_kind
    body["candidate"]["receipt_date"] = original_date
    draft = create(client, body).json()
    candidate = deepcopy(draft["candidate"])
    candidate["receipt_date"] = reviewed_date
    candidate["items"][0]["quantity"] = 1
    candidate["adjustments"] = []
    review = {
        "expected_revision": 1,
        "candidate": candidate,
        "assembly_status": "complete_by_user",
        "line_decisions": [{"item_position": 0, "method": "whole_line", "whole_purpose": "self"}],
        "adjustment_decisions": [],
    }
    assert client.put(f"/v2/drafts/{draft['id']}/review", json=review).status_code == 200
    draft_metadata = client.get(f"/v1/drafts/{draft['id']}/insights").json()
    assert draft_metadata["document"]["date_source"] == expected_source
    assert draft_metadata["sources"]["date_source"] == expected_source
    confirmed = client.post(
        f"/v2/drafts/{draft['id']}/confirm",
        json={"expected_revision": 2},
        headers={"Idempotency-Key": "reviewed-date-confirm"},
    )
    assert confirmed.status_code == 201
    entry = confirmed.json()
    assert entry["snapshot"]["candidate"]["receipt_date"] == reviewed_date
    assert entry["snapshot"]["original_candidate"]["receipt_date"] == original_date
    ledger_metadata = client.get(f"/v1/ledger/{entry['id']}/insights").json()
    assert ledger_metadata["document"]["date_source"] == expected_source
    assert ledger_metadata["sources"]["date_source"] == expected_source
