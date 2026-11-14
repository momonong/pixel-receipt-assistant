"""Multi-receipt behavior: no lost receipt, no cross-bound purpose, no replay."""

import asyncio
import copy
import json

import pytest

from pixelreceipt_backend.intake_store import IntakeStore
from pixelreceipt_backend.recognition import BrokerConfig, RecognitionWorker
from pixelreceipt_backend.store import Store, canonical

from .test_mobile_ingest import intake

NOTE = "左邊是代買 右邊是我自己的"


def layout():
    return {
        "coverage_complete": True,
        "unresolved_note_quotes": [],
        "receipts": [
            {
                "receipt_id": "r1",
                "label": "左側收據",
                "regions": [{"image_position": 1, "box": [0, 0, 500, 1000]}],
                "note_quotes": ["左邊是代買"],
                "purpose": "advance",
            },
            {
                "receipt_id": "r2",
                "label": "右側收據",
                "regions": [{"image_position": 1, "box": [500, 0, 1000, 1000]}],
                "note_quotes": ["右邊是我自己的"],
                "purpose": "self",
            },
        ],
    }


def request(body):
    value = intake(body).model_copy(
        update={"user_notes": NOTE, "capabilities": ["receipt_groups_v1"]}
    )
    return value.model_copy(
        update={"evidence": [value.evidence[0].model_copy(update={"notes": None})]}
    )


def test_receipt_agent_requires_capable_client_even_when_server_enabled(store, body):
    journal = IntakeStore(store)
    legacy = intake(body)
    old, _ = journal.create(legacy, "legacy-with-agent-enabled")
    worker = RecognitionWorker(
        store,
        BrokerConfig("http://127.0.0.1:18081", "/unused/token", receipt_agent=True),
        transport=object(),
    )
    asyncio.run(worker.prepare(journal.raw(old["id"])))
    snapshot = json.loads(journal.raw(old["id"])["config_snapshot"])
    assert snapshot["prompt_version"] != "pixelreceipt-layout-1"


def test_receipt_capability_keeps_old_request_idempotency(store, body):
    from pixelreceipt_backend.intake_models import CreateIntake

    journal = IntakeStore(store)
    legacy = intake(body).model_dump(mode="json", by_alias=True)
    legacy.pop("capabilities", None)
    first, _ = journal.create(CreateIntake.model_validate(legacy), "legacy-stable")
    assert first["input"] == legacy
    capable = {**legacy, "capabilities": ["receipt_groups_v1"]}
    new, created = journal.create(CreateIntake.model_validate(capable), "new-capable")
    assert created and new["input"]["capabilities"] == ["receipt_groups_v1"]
    again, created = journal.create(CreateIntake.model_validate(legacy), "legacy-stable")
    assert not created and again["id"] == first["id"]


def test_layout_preserves_both_receipts_and_binds_verbatim_spatial_notes(body):
    from pixelreceipt_backend.receipt_agent import validate_layout

    result = validate_layout(layout(), request(body).model_dump(mode="json", by_alias=True))
    assert [(r.receipt_id, r.purpose) for r in result.receipts] == [
        ("r1", "advance"),
        ("r2", "self"),
    ]


def test_layout_wire_accepts_only_lossless_decimal_coordinate_strings(body):
    from pixelreceipt_backend.receipt_agent import LayoutResponse, validate_layout

    value = layout()
    for receipt in value["receipts"]:
        for region in receipt["regions"]:
            region.update(
                zip(("left", "top", "right", "bottom"), map(str, region.pop("box")), strict=True)
            )
    parsed = LayoutResponse.model_validate(value).canonical_layout()
    assert validate_layout(parsed, request(body).model_dump(mode="json", by_alias=True))
    for invalid in ("1.0", "1e2", " 1", "-1", "1001", True, 1.0):
        malformed = copy.deepcopy(value)
        malformed["receipts"][0]["regions"][0]["left"] = invalid
        with pytest.raises(ValueError):
            LayoutResponse.model_validate(malformed)


@pytest.mark.parametrize("header", [None, "單價"])
def test_region_read_keeps_unit_price_only_with_a_printed_column(store, body, header):
    class UnitPriceReader(AgentBroker):
        async def request(self, method, path, payload=None):
            result = await super().request(method, path, payload)
            if method == "POST" and path == "/v1/queue":
                answer = self.jobs[payload["request_key"]]
                if "document" in answer:
                    answer["printed_unit_price_header"] = header
                    item = answer["document"]["candidate"]["items"][0]
                    item["printed_unit_price_yuan"] = item["printed_line_total_yuan"]
            return result

    journal = IntakeStore(store)
    parent, _ = journal.create(request(body), "printed-unit-column")
    drive(store, UnitPriceReader(body["candidate"]))
    children = journal.get(parent["id"])["receipts"]
    assert all(child["status"] == "succeeded" for child in children)
    for child in children:
        candidate = store.get_draft(child["draft_id"])["candidate"]
        item = candidate["items"][0]
        expected = item["printed_line_total_yuan"] if header else None
        assert item["printed_unit_price_yuan"] == expected


def test_item_note_cannot_authorize_two_receipts_from_the_same_source(body):
    from pixelreceipt_backend.receipt_agent import validate_layout

    source = request(body).model_dump(mode="json", by_alias=True)
    source["user_notes"] = "合成茶飲是代買"
    value = layout()
    for receipt in value["receipts"]:
        receipt.update(note_quotes=["合成茶飲是代買"], purpose=None)
    with pytest.raises(ValueError, match="shared note"):
        validate_layout(value, source)

    # An explicit all-batch instruction can cover both physical receipts.
    source["user_notes"] = "全部是代買"
    for receipt in value["receipts"]:
        receipt.update(note_quotes=["全部是代買"], purpose="advance")
    assert validate_layout(value, source)

    # Two independent per-photo notes are not the same authority.
    source["user_notes"] = None
    source["evidence"][0]["notes"] = "合成茶飲是代買"
    source["evidence"].append({**source["evidence"][0], "sha256": "a" * 64})
    value["receipts"][1]["regions"][0]["image_position"] = 2
    for receipt in value["receipts"]:
        receipt.update(note_quotes=["合成茶飲是代買"], purpose=None)
    assert validate_layout(value, source)


@pytest.mark.parametrize("mutation", ["missing", "swapped", "outside", "duplicate", "invented"])
def test_layout_rejects_lost_or_invented_spatial_associations(body, mutation):
    from pixelreceipt_backend.receipt_agent import validate_layout

    value = layout()
    if mutation == "missing":
        value["receipts"].pop()
    elif mutation == "swapped":
        value["receipts"][0]["regions"], value["receipts"][1]["regions"] = (
            value["receipts"][1]["regions"],
            value["receipts"][0]["regions"],
        )
    elif mutation == "outside":
        value["receipts"][0]["regions"][0]["box"][0] = -1
    elif mutation == "duplicate":
        value["receipts"][1]["regions"] = value["receipts"][0]["regions"]
    else:
        value["receipts"][0]["note_quotes"] = ["全部送人"]
    with pytest.raises(ValueError):
        validate_layout(value, request(body).model_dump(mode="json", by_alias=True))


def test_group_materialization_is_atomic_idempotent_and_keeps_originals(store, body):
    from pixelreceipt_backend.receipt_agent import AgentStore, validate_layout

    journal = IntakeStore(store)
    parent, _ = journal.create(request(body), "two-receipt-group")
    original = journal.raw(parent["id"])
    plan = validate_layout(layout(), parent["input"])
    # The storage contract accepts already-inspected content-addressed crop evidence.
    crops = {r.receipt_id: parent["input"]["evidence"] for r in plan.receipts}
    agent = AgentStore(store)
    agent.materialize(parent["id"], plan, crops)
    agent.materialize(parent["id"], plan, crops)
    current = journal.get(parent["id"])
    assert len(current["receipts"]) == 2
    assert current["draft_id"] is None and current["status"] == "processing"
    assert len(journal.list(100, 0)) == 1
    assert journal.raw(parent["id"])["input"] == original["input"]
    assert journal.raw(parent["id"])["job_key"] == original["job_key"]
    for child in current["receipts"]:
        saved = journal.get(child["intake_id"])
        assert saved["input"]["user_notes"] in {"左邊是代買", "右邊是我自己的"}
    assert store.list_ledger(100, 0) == []
    assert len(IntakeStore(Store(store.data_dir)).get(parent["id"])["receipts"]) == 2


class AgentBroker:
    def __init__(self, candidate):
        self.candidate = candidate
        self.jobs = {}
        self.posts = []

    async def request(self, method, path, payload=None):
        if path == "/v1/routes/explain":
            return {"selected_target_id": "synthetic-vision"}
        if method == "POST":
            key = payload["request_key"]
            assert key not in self.jobs, "the same work must not be posted twice"
            self.posts.append(copy.deepcopy(payload))
            prompt = payload["input"]["messages"][0]["content"][0]["text"]
            if "pixelreceipt-layout-1" in prompt:
                answer = layout()
                for receipt in answer["receipts"]:
                    for region in receipt["regions"]:
                        left, top, right, bottom = region.pop("box")
                        region.update(left=left, top=top, right=right, bottom=bottom)
            else:
                candidate = copy.deepcopy(self.candidate)
                left = '"purpose":"advance"' in prompt
                candidate["printed_total_yuan"] = 265 if left else 465
                candidate["adjustments"] = []
                candidate["items"][0].update(
                    quantity=1, printed_line_total_yuan=candidate["printed_total_yuan"]
                )
                answer = {
                    "action": "finish",
                    "regions": [],
                    "issue": None,
                    "document": {
                        "candidate": candidate,
                        "note_intents": [],
                        "unresolved_note_quotes": [],
                    },
                }
            self.jobs[key] = answer
            return {"request_key": key, "state": "queued"}
        key = path.split("/")[3]
        if path.endswith("/result"):
            return {
                "request_key": "exec-" + key,
                "state": "completed",
                "provider": "synthetic",
                "model": "vision-fixture",
                "finish_reason": "stop",
                "response_truncated": False,
                "result": {"text": canonical(self.jobs[key])},
            }
        return {
            "request_key": key,
            "execution_key": "exec-" + key,
            "state": "completed",
            "result_available": True,
        }


def test_worker_creates_two_independent_drafts_with_correct_purpose(store, body):
    journal = IntakeStore(store)
    parent, _ = journal.create(request(body), "two-receipt-worker")
    broker = AgentBroker(body["candidate"])
    config = BrokerConfig("http://127.0.0.1:18081", "/unused/token", receipt_agent=True)
    for _ in range(8):
        asyncio.run(RecognitionWorker(store, config, transport=broker).run_once())
    result = journal.get(parent["id"])
    assert result["status"] == "succeeded"
    assert len(result["receipts"]) == 2
    assert len(broker.posts) == 3
    assert all("provider" not in p and "model" not in p for p in broker.posts)
    for child, total, purpose in zip(
        result["receipts"], [265, 465], ["advance", "self"], strict=True
    ):
        draft = store.get_draft(child["draft_id"])
        assert draft["candidate"]["printed_total_yuan"] == total
        review = store.note_review(child["draft_id"])
        assert review["decisions"][0]["purpose"] == purpose
        assert review["personal_yuan"] == (0 if purpose == "advance" else 465)
    assert store.list_ledger(100, 0) == []
    assert len(journal.list(100, 0)) == 1
    assert journal.get(parent["id"])["input"]["user_notes"] == NOTE


@pytest.mark.parametrize("conflict", [False, True])
def test_whole_receipt_binding_resolves_crop_uncertainty_but_not_conflict(store, body, conflict):
    class CropReader(AgentBroker):
        async def request(self, method, path, payload=None):
            result = await super().request(method, path, payload)
            if method == "POST" and path == "/v1/queue":
                answer = self.jobs[payload["request_key"]]
                if "document" in answer:
                    document = answer["document"]
                    left = document["candidate"]["printed_total_yuan"] == 265
                    quote = "左邊是代買" if left else "右邊是我自己的"
                    document["unresolved_note_quotes"] = [quote]
                    if left:
                        document["note_intents"] = [
                            {
                                "item_position": 0,
                                "item_name": document["candidate"]["items"][0]["raw_name"],
                                "purpose": "self" if conflict else "advance",
                                "note_quote": quote,
                                "whole_line": conflict,
                            }
                        ]
            return result

    journal = IntakeStore(store)
    parent, _ = journal.create(request(body), "spatial-crop-uncertainty")
    broker = CropReader(body["candidate"])
    drive(store, broker)
    first, second = journal.get(parent["id"])["receipts"]
    left = store.note_review(first["draft_id"])
    right = store.note_review(second["draft_id"])
    assert right["status"] == "matched" and right["personal_yuan"] == 465
    if conflict:
        assert left["status"] == "needs_review"
        assert left["decisions"] == []
    else:
        assert left["status"] == "matched" and left["advance_yuan"] == 265
        assert left["personal_yuan"] == 0
    assert len(broker.posts) == 3


def test_per_image_note_cannot_cross_into_another_photo(body):
    from pixelreceipt_backend.receipt_agent import validate_layout

    source = request(body).model_dump(mode="json", by_alias=True)
    source["user_notes"] = None
    source["evidence"][0]["notes"] = "整張代買"
    source["evidence"].append({**source["evidence"][0], "sha256": "a" * 64, "notes": None})
    value = layout()
    value["receipts"][0].update(note_quotes=[], purpose=None)
    value["receipts"][1].update(note_quotes=["整張代買"], purpose="advance")
    value["receipts"][1]["regions"][0]["image_position"] = 2
    with pytest.raises(ValueError):
        validate_layout(value, source)


def test_unresolved_group_note_is_not_broadcast_as_scoped_authority(store, body):
    from pixelreceipt_backend.receipt_agent import AgentStore, validate_layout

    journal = IntakeStore(store)
    source = request(body).model_copy(update={"user_notes": "合成茶飲是代買"})
    parent, _ = journal.create(source, "ambiguous-name")
    value = layout()
    for receipt in value["receipts"]:
        receipt.update(note_quotes=[], purpose=None)
    value["unresolved_note_quotes"] = ["合成茶飲是代買"]
    plan = validate_layout(value, parent["input"])
    AgentStore(store).materialize(
        parent["id"], plan, {r.receipt_id: parent["input"]["evidence"] for r in plan.receipts}
    )
    for child in journal.get(parent["id"])["receipts"]:
        assert journal.get(child["intake_id"])["input"]["user_notes"] is None
        journal.complete(
            child["intake_id"],
            body["candidate"],
            {"model_note_review": {"note_intents": [], "unresolved_note_quotes": []}},
        )
        draft_id = journal.get(child["intake_id"])["draft_id"]
        review = store.note_review(draft_id)
        assert review["status"] == "needs_review"
        assert review["unresolved_notes"] == ["合成茶飲是代買"]
        assert review["decisions"] == []


@pytest.mark.parametrize("same_name", [True, False])
def test_item_scoping_is_rechecked_against_all_extracted_receipts(store, body, same_name):
    from pixelreceipt_backend.receipt_agent import AgentStore, validate_layout

    journal = IntakeStore(store)
    source = request(body).model_copy(update={"user_notes": "合成茶飲是代買"})
    parent, _ = journal.create(source, "group-final-note-check")
    value = layout()
    for receipt in value["receipts"]:
        receipt.update(note_quotes=[], purpose=None)
    value["receipts"][0]["note_quotes"] = ["合成茶飲是代買"]
    plan = validate_layout(value, parent["input"])
    AgentStore(store).materialize(
        parent["id"], plan, {r.receipt_id: parent["input"]["evidence"] for r in plan.receipts}
    )
    first, second = journal.get(parent["id"])["receipts"]
    proposal = {
        "note_intents": [
            {
                "item_position": 0,
                "item_name": "合成茶飲",
                "purpose": "advance",
                "note_quote": "合成茶飲是代買",
                "whole_line": True,
            }
        ],
        "unresolved_note_quotes": [],
    }
    journal.complete(first["intake_id"], body["candidate"], {"model_note_review": proposal})
    draft_id = journal.get(first["intake_id"])["draft_id"]
    assert store.note_review(draft_id)["status"] == "needs_review"
    other = copy.deepcopy(body["candidate"])
    if not same_name:
        other["items"][0]["raw_name"] = "合成餅乾"
    journal.complete(
        second["intake_id"],
        other,
        {"model_note_review": {"note_intents": [], "unresolved_note_quotes": []}},
    )
    review = store.note_review(draft_id)
    if same_name:
        assert review["status"] == "needs_review"
        assert review["decisions"] == []
        assert review["unresolved_notes"] == ["合成茶飲是代買"]
    else:
        assert review["status"] == "matched"
        assert review["decisions"][0]["purpose"] == "advance"


class RepairBroker(AgentBroker):
    def __init__(self, candidate, *, repeat=False, lost=False):
        super().__init__(candidate)
        self.repeat, self.lost = repeat, lost
        self.rereads = 0

    async def request(self, method, path, payload=None):
        answer = await super().request(method, path, payload)
        if method == "POST" and path == "/v1/queue":
            prompt = payload["input"]["messages"][0]["content"][0]["text"]
            if '"purpose":"advance"' in prompt:
                self.rereads += 1
                if self.rereads == 1 or self.repeat:
                    self.jobs[payload["request_key"]] = {
                        "action": "read_regions",
                        "document": None,
                        "issue": "需要放大左側下半部",
                        "regions": [{"image_position": 1, "box": [0, 500, 500, 1000]}],
                    }
                if self.lost and self.rereads == 1:
                    from pixelreceipt_backend.recognition import RecognitionError

                    raise RecognitionError("broker_transport_unknown")
        return answer


def drive(store, broker, steps=12):
    config = BrokerConfig("http://127.0.0.1:18081", "/unused/token", receipt_agent=True)
    journal = IntakeStore(store)
    for _ in range(steps):
        # Simulate the passage of the normal polling interval, not a new request.
        with store.connection(write=True) as connection:
            connection.execute("UPDATE intakes SET next_poll_at=0")
        asyncio.run(RecognitionWorker(store, config, transport=broker).run_once())
    return journal


def test_one_bounded_reread_keeps_first_journal_and_both_drafts(store, body):
    parent, _ = IntakeStore(store).create(request(body), "repair")
    broker = RepairBroker(body["candidate"])
    journal = drive(store, broker)
    result = journal.get(parent["id"])
    assert result["status"] == "succeeded"
    assert len(broker.posts) == 4 and len(journal.list(100, 0)) == 1
    child = journal.get(result["receipts"][0]["intake_id"])
    assert child["result"]["initial_read"]["regional_read_request"]["action"] == "read_regions"
    assert child["result"]["repair_intake_id"] != child["id"]
    assert store.note_review(child["draft_id"])["advance_yuan"] == 265
    assert store.list_ledger(100, 0) == []


def test_reread_budget_does_not_drop_successful_other_receipt(store, body):
    parent, _ = IntakeStore(store).create(request(body), "repair-limit")
    broker = RepairBroker(body["candidate"], repeat=True)
    result = drive(store, broker).get(parent["id"])
    assert len(broker.posts) == 4
    assert result["status"] == "failed" and result["error_code"] == "multi_receipt_partial"
    assert result["receipts"][0]["draft_id"] is None
    assert result["receipts"][0]["error_code"] == "receipt_repair_budget_exhausted"
    assert result["receipts"][1]["draft_id"] is not None


def test_lost_submit_reconciles_same_key_before_any_reread(store, body):
    parent, _ = IntakeStore(store).create(request(body), "lost-child-submit")
    broker = RepairBroker(body["candidate"], lost=True)
    result = drive(store, broker).get(parent["id"])
    assert result["status"] == "succeeded" and len(broker.posts) == 4
    assert len({p["request_key"] for p in broker.posts}) == 4


def test_deleting_child_cancels_its_repair_and_preserves_originals(store, body):
    from pixelreceipt_backend.intake_models import DeleteIntake
    from pixelreceipt_backend.store import StoreError

    journal = IntakeStore(store)
    parent, _ = journal.create(request(body), "delete-child")
    broker = RepairBroker(body["candidate"])
    drive(store, broker, steps=4)  # layout, materialize, first read, materialize repair
    grouped = journal.get(parent["id"])
    assert len(grouped["receipts"]) == 2
    with pytest.raises(StoreError, match="multi_receipt_delete_individually"):
        journal.delete(
            parent["id"], DeleteIntake(expected_draft_revision=None, reason="測試"), "pdel"
        )
    child = grouped["receipts"][0]
    journal.delete(
        child["intake_id"], DeleteIntake(expected_draft_revision=None, reason="測試"), "cdel"
    )
    drive(store, broker)
    saved = journal.get(parent["id"])
    assert saved["receipts"][0]["status"] == "deleted"
    assert saved["receipts"][1]["draft_id"] is not None
    assert len(broker.posts) == 3
    assert journal.get(parent["id"])["input"] == parent["input"]


def test_cropping_uses_oriented_original_and_does_not_modify_it():
    import base64
    import io

    from PIL import Image

    from pixelreceipt_backend.recognition import derive_media

    image = Image.new("RGB", (400, 200), "red")
    image.paste(Image.new("RGB", (200, 200), "blue"), (200, 0))
    source = io.BytesIO()
    image.save(source, format="PNG")
    content = source.getvalue()
    derived, metadata = asyncio.run(
        derive_media(
            canonical(
                {
                    "media_type": "image/png",
                    "data": base64.b64encode(content).decode(),
                    "box": [500, 0, 1000, 1000],
                }
            ).encode(),
            "region_image",
        )
    )
    assert metadata["width"] == metadata["height"] == 200
    with Image.open(io.BytesIO(derived)) as crop:
        assert crop.getpixel((100, 100))[2] > 240
        assert crop.getpixel((100, 100))[0] < 10
    assert content == source.getvalue()


class InvalidLayoutBroker(AgentBroker):
    def __init__(self, candidate, *, always=False):
        super().__init__(candidate)
        self.layouts = 0
        self.always = always

    async def request(self, method, path, payload=None):
        result = await super().request(method, path, payload)
        if method == "POST" and path == "/v1/queue":
            prompt = payload["input"]["messages"][0]["content"][0]["text"]
            if "pixelreceipt-layout-1" in prompt:
                self.layouts += 1
                if self.layouts == 1 or self.always:
                    self.jobs[payload["request_key"]] = {"receipts": []}
        return result


@pytest.mark.parametrize("always", [False, True])
def test_invalid_completed_layout_gets_only_one_persisted_correction(store, body, always):
    parent, _ = IntakeStore(store).create(request(body), "invalid-layout")
    broker = InvalidLayoutBroker(body["candidate"], always=always)
    journal = drive(store, broker)
    saved = journal.get(parent["id"])
    assert broker.layouts == 2
    assert len(journal.list(100, 0)) == 1
    assert saved["input"] == parent["input"]
    assert saved["result"]["layout_retry"] is True
    if always:
        assert saved["status"] == "failed"
        assert saved["error_code"] == "receipt_layout_needs_review"
        assert len(broker.posts) == 2
    else:
        assert saved["status"] == "succeeded" and len(saved["receipts"]) == 2
        assert len(broker.posts) == 4


def test_v5_migration_preserves_every_old_row_and_blob(client, store, body, png):
    from .test_prices import receipt

    receipt(client, body, key="legacy-ledger")
    IntakeStore(store).create(request(body), "legacy-waiting")
    store.create_session("kept-session", "kept-csrf")
    with store.connection(write=True) as connection:
        for table in ("receipt_attempts", "receipt_regions", "receipt_groups"):
            connection.execute(f"DROP TABLE {table}")
        connection.execute("PRAGMA user_version=5")
        tables = [
            row[0]
            for row in connection.execute(
                "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'"
            )
        ]
        before = {
            table: [tuple(row) for row in connection.execute(f"SELECT * FROM {table}")]
            for table in tables
        }
    reopened = Store(store.data_dir)
    with reopened.connection() as connection:
        assert connection.execute("PRAGMA user_version").fetchone()[0] == 6
        assert before == {
            table: [tuple(row) for row in connection.execute(f"SELECT * FROM {table}")]
            for table in tables
        }
        assert not connection.execute("PRAGMA foreign_key_check").fetchall()
    assert reopened.get_evidence(body["evidence"][0]["sha256"], content=True)[1] == png


def test_group_accounting_projection_tracks_each_confirmation_and_void(client, store, body):
    parent, _ = IntakeStore(store).create(request(body), "group-accounting")
    journal = drive(store, AgentBroker(body["candidate"]))
    children = journal.get(parent["id"])["receipts"]
    ledgers = []
    for index, child in enumerate(children):
        draft = store.get_draft(child["draft_id"])
        candidate = draft["candidate"]
        candidate["receipt_date"] = "2026-11-15"
        review = {
            "expected_revision": 1,
            "candidate": candidate,
            "assembly_status": "complete_by_user",
            "line_decisions": [
                {"item_position": 0, "method": "whole_line", "whole_purpose": child["purpose"]}
            ],
            "adjustment_decisions": [],
        }
        response = client.put("/v2/drafts/" + draft["id"] + "/review", json=review)
        assert response.status_code == 200, response.text
        response = client.post(
            "/v2/drafts/" + draft["id"] + "/confirm",
            json={"expected_revision": 2, "notes_acknowledgement": "checked"},
            headers={"Idempotency-Key": "group-confirm-" + str(index)},
        )
        assert response.status_code == 201, response.text
        ledgers.append(response.json()["id"])
        projection = client.get("/v1/intakes/" + parent["id"]).json()["receipts"]
        assert sum(r["ledger_status"] == "active" for r in projection) == index + 1
    response = client.post(
        "/v1/ledger/" + ledgers[0] + "/void",
        json={"expected_revision": 1, "reason": "合成測試"},
        headers={"Idempotency-Key": "group-void"},
    )
    assert response.status_code == 200, response.text
    projection = client.get("/v1/intakes").json()["intakes"][0]["receipts"]
    assert [r["ledger_status"] for r in projection] == ["voided", "active"]
