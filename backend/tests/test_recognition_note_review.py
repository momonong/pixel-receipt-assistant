"""A single recognition outcome yields receipt facts and bounded purpose proposals."""

import asyncio
import hashlib
import json

import pytest

from pixelreceipt_backend.intake_store import IntakeStore
from pixelreceipt_backend.recognition import BrokerConfig, RecognitionError, RecognitionWorker
from pixelreceipt_backend.store import canonical

from .test_mobile_ingest import intake

NEW_PROMPT = "pixelreceipt-vision-note-review-2"
LEGACY_PROMPT = "pixelreceipt-vision-candidate-1"
NOTE = "今天訂餐，合成茶飲是幫小美買的，請在結算前再看一次。"


class NoNetwork:
    async def request(self, *args):
        pytest.fail("interpreting an existing outcome must not call Broker")


def queued(store, body, prompt_version):
    journal = IntakeStore(store)
    request = intake(body).model_copy(update={"user_notes": NOTE})
    record, _ = journal.create(request, "semantic-queue")
    payload = {"request_key": record["job_key"], "input": {"messages": []}}
    journal.update(
        record["id"],
        status="processing",
        execution_key="q-semantic",
        payload=canonical(payload),
        config_snapshot=canonical(
            {
                "adapter_version": "pixelreceipt-broker-queue-1",
                "prompt_version": prompt_version,
                "derivatives": [],
                "payload_sha256": hashlib.sha256(canonical(payload).encode()).hexdigest(),
            }
        ),
    )
    return journal, journal.raw(record["id"])


def outcome(candidate, *, wrapper=True, malformed=False):
    value = candidate
    if wrapper:
        value = {
            "candidate": candidate,
            "note_intents": [
                {
                    "item_position": 0 if not malformed else 9,
                    "item_name": "合成茶飲",
                    "purpose": "advance",
                    "note_quote": NOTE,
                    "whole_line": True,
                }
            ],
            "unresolved_note_quotes": [],
        }
    return {
        "request_key": "q-semantic",
        "state": "completed",
        "provider": "synthetic",
        "model": "fixture",
        "finish_reason": "stop",
        "response_truncated": False,
        "result": {"text": canonical(value)},
    }


def test_new_response_persists_raw_then_review_without_model_call(store, body, monkeypatch):
    import pixelreceipt_backend.recognition as recognition
    from pixelreceipt_backend.note_review import build_note_review

    journal, row = queued(store, body, NEW_PROMPT)
    response = outcome(body["candidate"])

    def checked(candidate, notes, model_review=None):
        assert journal.get(row["id"])["result"]["raw_outcome"] == response
        return build_note_review(candidate, notes, model_review)

    monkeypatch.setattr(recognition, "build_note_review", checked)
    runner = RecognitionWorker(store, transport=NoNetwork())
    runner.accept_result(row, response)
    saved = journal.get(row["id"])
    assert saved["status"] == "succeeded"
    assert saved["result"]["note_review"] == {
        "status": "matched",
        "decisions": [{"item_position": 0, "purpose": "advance", "note_quote": NOTE}],
        "unresolved_notes": [],
    }
    assert (
        store.get_draft(saved["draft_id"])["candidate"]["items"][0]["printed_line_total_yuan"]
        == 100
    )
    assert store.list_ledger(100, 0) == []


def test_invalid_intent_preserves_candidate_but_visibly_requires_review(store, body):
    journal, row = queued(store, body, NEW_PROMPT)
    RecognitionWorker(store, transport=NoNetwork()).accept_result(
        row, outcome(body["candidate"], malformed=True)
    )
    saved = journal.get(row["id"])
    assert saved["status"] == "succeeded"
    assert saved["result"]["note_review"]["status"] == "needs_review"
    assert saved["result"]["note_review"]["decisions"] == []


def test_legacy_queued_payload_is_frozen_and_candidate_only_result_remains_readable(store, body):
    journal, row = queued(store, body, LEGACY_PROMPT)
    payload, snapshot, key = row["payload"], row["config_snapshot"], row["job_key"]
    RecognitionWorker(store, transport=NoNetwork()).accept_result(
        row, outcome(body["candidate"], wrapper=False)
    )
    saved = journal.get(row["id"])
    assert saved["status"] == "succeeded"
    assert saved["result"]["note_review"]["status"] == "needs_review"
    current = journal.raw(row["id"])
    assert (current["payload"], current["config_snapshot"], current["job_key"]) == (
        payload,
        snapshot,
        key,
    )


def test_new_prompt_does_not_silently_accept_candidate_only_result(store, body):
    journal, row = queued(store, body, NEW_PROMPT)
    response = outcome(body["candidate"], wrapper=False)
    with pytest.raises(RecognitionError, match="invalid_model_candidate"):
        RecognitionWorker(store, transport=NoNetwork()).accept_result(row, response)
    assert journal.get(row["id"])["result"]["raw_outcome"] == response
    assert journal.get(row["id"])["draft_id"] is None


def test_prepare_requests_both_in_existing_payload_and_keeps_roles(store, body):
    journal = IntakeStore(store)
    request = intake(body).model_copy(update={"user_notes": NOTE})
    record, _ = journal.create(request, "semantic-prepare")
    config = BrokerConfig("http://127.0.0.1:18081", "/unused/fixture-token")
    payload = asyncio.run(
        RecognitionWorker(store, config, transport=NoNetwork()).prepare(journal.raw(record["id"]))
    )
    prompt = payload["input"]["messages"][0]["content"][0]["text"]
    schema, context = prompt.split("Schema: ", 1)[1].split("\nInput context: ", 1)
    document = json.loads(schema)
    assert set(document["properties"]) == {"candidate", "note_intents", "unresolved_note_quotes"}
    assert json.loads(context)["images"] == [{"position": 1, "role": "receipt", "notes": "第一張"}]
    assert json.loads(context)["user_notes"] == NOTE
    assert json.loads(journal.raw(record["id"])["config_snapshot"])["prompt_version"] == NEW_PROMPT
