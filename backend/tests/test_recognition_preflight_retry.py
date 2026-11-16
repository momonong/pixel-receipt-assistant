"""A proven countTokens 429 may wait; an uncertain generation must never replay."""

import asyncio
import copy
import json
from datetime import UTC, datetime
from types import SimpleNamespace

import pytest

from pixelreceipt_backend import intake_store, recognition
from pixelreceipt_backend.intake_store import IntakeStore
from pixelreceipt_backend.recognition import BrokerConfig, RecognitionWorker

from .test_receipt_agent import AgentBroker, request


@pytest.fixture
def clock(monkeypatch):
    value = SimpleNamespace(now=int(datetime.now(UTC).timestamp()))

    class ClockDatetime(datetime):
        @classmethod
        def now(cls, tz=None):
            return cls.fromtimestamp(value.now, tz)

    monkeypatch.setattr(recognition, "time", SimpleNamespace(time=lambda: value.now))
    monkeypatch.setattr(intake_store, "time", SimpleNamespace(time=lambda: value.now))
    monkeypatch.setattr(recognition, "datetime", ClockDatetime)
    return value


def rejection(key):
    return {
        "request_key": "exec-" + key,
        "state": "rejected",
        "error_code": "token_preflight_rejected",
        "provider": None,
        "model": None,
        "dispatched_at": None,
        "ledger_dispatched_at": None,
        "reservation_id": None,
        "ledger_state": None,
        "ledger_charges": [],
        "attempts": [],
        "provider_request_id": None,
        "reported_input_tokens": None,
        "reported_output_tokens": None,
        "reported_neurons": None,
        "finish_reason": None,
        "response_truncated": None,
        "token_preflight": {
            "state": "rejected",
            "http_status": 429,
            "model": "vision-fixture",
            "target_id": "synthetic-vision",
        },
    }


class LimitedBroker(AgentBroker):
    def __init__(self, candidate, failures=1, mutate=None):
        super().__init__(candidate)
        self.failures, self.mutate, self.rejected = failures, mutate, {}

    async def request(self, method, path, payload=None):
        result = await super().request(method, path, payload)
        if method == "POST" and path == "/v1/queue":
            prompt = payload["input"]["messages"][0]["content"][0]["text"]
            if '"purpose":"advance"' in prompt and self.failures:
                self.failures -= 1
                value = rejection(payload["request_key"])
                if self.mutate:
                    self.mutate(value)
                self.rejected[payload["request_key"]] = value
        if method == "GET":
            key = path.split("/")[3]
            if key in self.rejected:
                if path.endswith("/result"):
                    return copy.deepcopy(self.rejected[key])
                return {
                    "request_key": key,
                    "execution_key": "exec-" + key,
                    "state": "failed",
                    "gateway_state": "rejected",
                    "result_available": True,
                }
        return result


def run(store, broker, count=10):
    config = BrokerConfig(
        "http://127.0.0.1:18081",
        "/unused/token",
        receipt_agent=True,
        provider="synthetic",
        model="vision-fixture",
    )
    # A fresh worker each tick exercises restart recovery from durable state.
    for _ in range(count):
        asyncio.run(RecognitionWorker(store, config, transport=broker).run_once())


def test_left_429_waits_and_recovers_without_replaying_successful_right(store, body, clock):
    journal = IntakeStore(store)
    parent, _ = journal.create(request(body), "preflight-limited-pair")
    broker = LimitedBroker(body["candidate"])
    run(store, broker)
    group = journal.get(parent["id"])
    left, right = group["receipts"]
    assert group["status"] == "processing"
    assert left["status"] == "queued"
    assert right["status"] == "succeeded"
    assert len(broker.posts) == 3
    saved = journal.raw(left["intake_id"])
    assert saved["next_poll_at"] > clock.now
    history = json.loads(saved["config_snapshot"])["preflight_rejections"]
    assert len(history) == 1
    assert history[0]["raw_outcome"] == next(iter(broker.rejected.values()))
    assert history[0]["job_key"] != saved["job_key"]
    right_before = journal.raw(right["intake_id"])
    clock.now = saved["next_poll_at"]
    run(store, broker)
    group = journal.get(parent["id"])
    assert group["status"] == "succeeded"
    left, right = group["receipts"]
    assert journal.raw(right["intake_id"]) == right_before
    assert len(broker.posts) == 4
    assert len(journal.list(100, 0)) == 1
    assert store.note_review(left["draft_id"])["advance_yuan"] == 265
    assert store.note_review(left["draft_id"])["personal_yuan"] == 0
    assert store.note_review(right["draft_id"])["personal_yuan"] == 465
    assert (
        len(json.loads(journal.raw(left["intake_id"])["config_snapshot"])["preflight_rejections"])
        == 1
    )
    assert store.list_ledger(100, 0) == []


def test_repeated_429_is_bounded_and_retains_every_failed_outcome(store, body, clock):
    journal = IntakeStore(store)
    parent, _ = journal.create(request(body), "bounded-preflight")
    broker = LimitedBroker(body["candidate"], failures=100)
    for _ in range(6):
        run(store, broker)
        clock.now += 600
    group = journal.get(parent["id"])
    left, right = group["receipts"]
    assert group["status"] == "failed"
    assert left["error_code"] == "broker_preflight_rate_limit_exhausted"
    assert right["status"] == "succeeded"
    assert len(broker.posts) == 5  # layout + right + at most three left preflights
    row = journal.raw(left["intake_id"])
    assert len(json.loads(row["config_snapshot"])["preflight_rejections"]) == 2
    assert json.loads(row["result"])["raw_outcome"]["state"] == "rejected"


@pytest.mark.parametrize(
    "mutation",
    [
        lambda o: o.update(state="unknown"),
        lambda o: o.update(dispatched_at="2026-11-16T00:00:00Z"),
        lambda o: o.pop("dispatched_at"),
        lambda o: o.update(ledger_dispatched_at="2026-11-16T00:00:00Z"),
        lambda o: o.update(reservation_id="reservation"),
        lambda o: o.update(attempts=[{"state": "unknown"}]),
        lambda o: o.update(ledger_charges=[{"tokens": 1}]),
        lambda o: o.update(result={"text": "partial result"}),
        lambda o: o.update(reported_output_tokens=1),
        lambda o: o["token_preflight"].update(http_status=403),
        lambda o: o["token_preflight"].update(state="unknown"),
        lambda o: o["token_preflight"].update(model="other-model"),
    ],
)
def test_incomplete_or_dispatched_rejection_never_creates_another_job(store, body, clock, mutation):
    journal = IntakeStore(store)
    parent, _ = journal.create(request(body), "no-replay-preflight")
    broker = LimitedBroker(body["candidate"], mutate=mutation)
    run(store, broker)
    clock.now += 600
    run(store, broker)
    left, right = journal.get(parent["id"])["receipts"]
    assert left["status"] == "failed"
    assert left["error_code"] == "broker_outcome_not_completed"
    assert right["status"] == "succeeded"
    assert len(broker.posts) == 3
    assert json.loads(journal.raw(left["intake_id"])["result"])["raw_outcome"] == next(
        iter(broker.rejected.values())
    )


def test_late_rejected_outcome_cannot_overwrite_successor(store, body, clock):
    journal = IntakeStore(store)
    parent, _ = journal.create(request(body), "late-preflight-result")
    broker = LimitedBroker(body["candidate"])
    run(store, broker)
    left = journal.get(parent["id"])["receipts"][0]
    row = journal.raw(left["intake_id"])
    history = json.loads(row["config_snapshot"])["preflight_rejections"][0]
    stale = dict(
        row,
        job_key=history["job_key"],
        execution_key=history["execution_key"],
        payload=json.dumps(history["payload"]),
        config_snapshot=json.dumps(history["config_snapshot"]),
        result=None,
    )
    RecognitionWorker(store, transport=object()).accept_result(stale, history["raw_outcome"])
    assert journal.raw(row["id"]) == row


def test_changed_broker_config_cannot_redirect_a_delayed_retry(store, body, clock):
    journal = IntakeStore(store)
    parent, _ = journal.create(request(body), "config-change-preflight")
    broker = LimitedBroker(body["candidate"])
    run(store, broker)
    left = journal.get(parent["id"])["receipts"][0]
    row = journal.raw(left["intake_id"])
    clock.now = row["next_poll_at"]
    changed = BrokerConfig(
        "http://127.0.0.1:18082",
        "/unused/token",
        receipt_agent=True,
        provider="synthetic",
        model="vision-fixture",
    )
    asyncio.run(RecognitionWorker(store, changed, transport=broker).run_once())
    assert len(broker.posts) == 3
    assert journal.get(row["id"])["error_code"] == "broker_config_changed"
    assert journal.get(row["id"])["status"] == "unknown"


def test_slow_get_cannot_attach_old_outcome_to_a_successor(store, body, clock):
    journal = IntakeStore(store)
    parent, _ = journal.create(request(body), "slow-result-preflight")
    config = BrokerConfig(
        "http://127.0.0.1:18081",
        "/unused/token",
        receipt_agent=True,
        provider="synthetic",
        model="vision-fixture",
    )

    class SlowResult(LimitedBroker):
        async def request(self, method, path, payload=None):
            outcome = await super().request(method, path, payload)
            if path.endswith("/result") and outcome.get("state") == "rejected":
                key = path.split("/")[3]
                with store.connection() as connection:
                    identifier = connection.execute(
                        "SELECT id FROM intakes WHERE job_key=?", (key,)
                    ).fetchone()[0]
                row = journal.raw(identifier)
                RecognitionWorker(store, config, transport=self).accept_result(row, outcome)
            return outcome

    broker = SlowResult(body["candidate"])
    run(store, broker)
    left, right = journal.get(parent["id"])["receipts"]
    row = journal.raw(left["intake_id"])
    assert left["status"] == "queued"
    assert row["result"] is None and row["execution_key"] is None
    assert len(json.loads(row["config_snapshot"])["preflight_rejections"]) == 1
    assert right["status"] == "succeeded"
    assert len(broker.posts) == 3


def test_slow_metadata_cannot_pollute_successor_identity(store, body, clock):
    journal = IntakeStore(store)
    parent, _ = journal.create(request(body), "slow-metadata-preflight")
    config = BrokerConfig(
        "http://127.0.0.1:18081",
        "/unused/token",
        receipt_agent=True,
        provider="synthetic",
        model="vision-fixture",
    )

    class SlowMetadata(LimitedBroker):
        async def request(self, method, path, payload=None):
            metadata = await super().request(method, path, payload)
            if (
                method == "GET"
                and not path.endswith("/result")
                and metadata.get("state") == "failed"
            ):
                key = path.split("/")[3]
                with store.connection() as connection:
                    identifier = connection.execute(
                        "SELECT id FROM intakes WHERE job_key=?", (key,)
                    ).fetchone()[0]
                journal.update(identifier, execution_key="exec-" + key)
                RecognitionWorker(store, config, transport=self).accept_result(
                    journal.raw(identifier), self.rejected[key]
                )
            return metadata

    broker = SlowMetadata(body["candidate"])
    run(store, broker)
    left = journal.get(parent["id"])["receipts"][0]
    row = journal.raw(left["intake_id"])
    assert row["status"] == "queued"
    assert row["result"] is None and row["execution_key"] is None
    assert row["next_poll_at"] > clock.now
    clock.now = row["next_poll_at"]
    run(store, broker)
    assert journal.get(parent["id"])["status"] == "succeeded"
    assert len(broker.posts) == 4
