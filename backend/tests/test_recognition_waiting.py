"""Durable unsent admission waiting; sent work never re-enters submission."""

import asyncio
import json
from datetime import UTC, datetime
from types import SimpleNamespace

import pytest
from fastapi.testclient import TestClient

from pixelreceipt_backend import intake_store, recognition
from pixelreceipt_backend.api import create_app
from pixelreceipt_backend.intake_store import IntakeStore
from pixelreceipt_backend.recognition import BrokerConfig, RecognitionError, RecognitionWorker
from pixelreceipt_backend.store import Store

from .conftest import TOKEN
from .test_mobile_ingest import BrokerFixture, intake


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


class WaitingBroker(BrokerFixture):
    def __init__(self, store, candidate):
        super().__init__(store, candidate)
        self.plan = {"selected_target_id": "fixture"}
        self.explain_error = None
        self.expired = False
        self.diagnostics = {
            "ready_targets": 1,
            "queue_worker": {"enabled": True, "running": True},
            "targets": [
                {
                    "provider": "google",
                    "model": "fixture-model",
                    "capability": "vision",
                    "state": "ready",
                    "reasons": [],
                    "next_retry_at": None,
                }
            ],
        }

    async def request(self, method, path, payload=None):
        if path == "/v1/diagnostics":
            self.calls.append((method, path, payload))
            return self.diagnostics
        if path == "/v1/routes/explain":
            self.calls.append((method, path, payload))
            assert "deadline" not in payload
            if self.explain_error:
                raise self.explain_error
            return self.plan
        if self.expired and method == "GET":
            self.calls.append((method, path, payload))
            return {"request_key": self.job, "state": "expired", "result_available": False}
        return await super().request(method, path, payload)


def waiting_worker(store, body):
    fixture = WaitingBroker(store, body["candidate"])
    config = BrokerConfig(
        "http://127.0.0.1:18081",
        "/unused/fixture-token",
        provider="google",
        model="fixture-model",
    )
    return RecognitionWorker(store, config, transport=fixture), fixture


def queue_posts(fixture):
    return [c for c in fixture.calls if c[0] == "POST" and c[1] == "/v1/queue"]


@pytest.mark.parametrize(
    "reason", ["free_evidence_not_current", "free_evidence_missing", "free_eligibility_unverified"]
)
def test_qualification_pause_resumes_same_intake_after_worker_restart(store, body, clock, reason):
    journal = IntakeStore(store)
    record, _ = journal.create(intake(body), "qualification-pause")
    runner, fixture = waiting_worker(store, body)
    fixture.plan = {
        "selected_target_id": None,
        "permanent_rejection": True,
        "candidates": [{"reasons": ["eligibility_or_official_capacity", reason]}],
    }
    asyncio.run(runner.run_once())
    saved = journal.raw(record["id"])
    assert saved["status"] == "queued" and saved["error_code"] == "broker_admission_waiting"
    assert saved["next_poll_at"] == clock.now + 60
    assert saved["monitor_until"] == 0 and not queue_posts(fixture)
    assert "deadline" not in json.loads(saved["payload"])
    assert journal.pending() is None

    clock.now += 86400
    fixture.plan = {"selected_target_id": "fixture"}
    restarted = RecognitionWorker(Store(store.data_dir), runner.config, transport=fixture)
    asyncio.run(restarted.run_once())
    complete = journal.get(record["id"])
    assert complete["status"] == "succeeded" and complete["job_key"] == record["job_key"]
    assert len(queue_posts(fixture)) == 1
    submitted = queue_posts(fixture)[0][2]
    assert int(datetime.fromisoformat(submitted["deadline"]).timestamp()) == clock.now + 900
    assert store.get_draft(complete["draft_id"])["status"] == "needs_review"


def test_daily_capacity_wait_is_persistent_and_deadline_begins_at_first_submit(store, body, clock):
    journal = IntakeStore(store)
    record, _ = journal.create(intake(body), "daily-reset")
    runner, fixture = waiting_worker(store, body)
    reset = clock.now + 86400
    fixture.plan = {
        "selected_target_id": None,
        "temporary": True,
        "permanent_rejection": False,
        "next_retry_at": datetime.fromtimestamp(reset, UTC).isoformat(),
        "candidates": [{"reasons": ["local_cap:requests-day"]}],
    }
    asyncio.run(runner.run_once())
    assert journal.raw(record["id"])["next_poll_at"] == reset
    clock.now += 1800
    restarted = RecognitionWorker(Store(store.data_dir), runner.config, transport=fixture)
    asyncio.run(restarted.run_once())
    assert not queue_posts(fixture) and journal.pending() is None
    clock.now = reset + 1
    fixture.plan = {"selected_target_id": "fixture"}
    asyncio.run(runner.run_once())
    assert journal.get(record["id"])["status"] == "succeeded"
    assert queue_posts(fixture)[0][2]["request_key"] == record["job_key"]
    assert journal.raw(record["id"])["monitor_until"] == clock.now + 900


@pytest.mark.parametrize(
    "reasons", [["context_limit"], ["free_evidence_not_current", "output_limit"]]
)
def test_nonrecoverable_input_rejection_does_not_loop_or_submit(store, body, clock, reasons):
    journal = IntakeStore(store)
    record, _ = journal.create(intake(body), "invalid-route")
    runner, fixture = waiting_worker(store, body)
    fixture.plan = {
        "selected_target_id": None,
        "permanent_rejection": True,
        "candidates": [{"reasons": reasons}],
    }
    asyncio.run(runner.run_once())
    saved = journal.get(record["id"])
    assert saved["status"] == "unavailable" and saved["error_code"] == "broker_route_rejected"
    clock.now += 86400
    assert journal.pending() is None and not queue_posts(fixture)


@pytest.mark.parametrize("code", ["broker_http_error", "broker_transport_unknown"])
def test_unsent_explain_outage_waits_and_recovers_without_queue_retry(store, body, clock, code):
    journal = IntakeStore(store)
    record, _ = journal.create(intake(body), "explain-outage")
    runner, fixture = waiting_worker(store, body)
    fixture.explain_error = RecognitionError(code)
    asyncio.run(runner.run_once())
    assert journal.get(record["id"])["status"] == "queued"
    assert not queue_posts(fixture)
    fixture.explain_error = None
    clock.now += 61
    asyncio.run(runner.run_once())
    assert journal.get(record["id"])["status"] == "succeeded"
    assert len(queue_posts(fixture)) == 1


def test_explain_http_invalid_request_is_terminal_instead_of_repeated(store, body, clock):
    journal = IntakeStore(store)
    record, _ = journal.create(intake(body), "invalid-explain-request")
    runner, fixture = waiting_worker(store, body)
    fixture.explain_error = RecognitionError("broker_http_error", status_code=400)
    asyncio.run(runner.run_once())
    assert journal.get(record["id"])["status"] == "failed"
    assert journal.pending() is None and not queue_posts(fixture)


def test_due_intakes_are_not_blocked_by_future_admission_or_original_job_poll(store, body, clock):
    journal = IntakeStore(store)
    first, _ = journal.create(intake(body), "future-admission")
    journal.update(first["id"], next_poll_at=clock.now + 86400)
    second, _ = journal.create(intake(body), "future-original-poll")
    journal.update(second["id"], status="processing", next_poll_at=clock.now + 60)
    third, _ = journal.create(intake(body), "due-independent")
    assert journal.pending()["id"] == third["id"]
    journal.update(third["id"], status="queued", next_poll_at=clock.now + 10)
    assert journal.pending() is None
    clock.now += 61
    assert journal.pending()["id"] == second["id"]
    journal.update(second["id"], next_poll_at=clock.now + 60)
    assert journal.pending()["id"] == third["id"]


def test_due_original_result_poll_precedes_older_unsent_backlog(store, body, clock):
    journal = IntakeStore(store)
    older, _ = journal.create(intake(body), "older-unsent")
    sent, _ = journal.create(intake(body), "sent-result")
    journal.update(sent["id"], status="processing", next_poll_at=clock.now)
    assert journal.pending()["id"] == sent["id"]
    journal.update(sent["id"], next_poll_at=clock.now + 10)
    assert journal.pending()["id"] == older["id"]


def test_legacy_unsent_no_route_can_resume_but_parked_unknown_cannot(store, body, clock):
    journal = IntakeStore(store)
    legacy, _ = journal.create(intake(body), "legacy-no-route")
    journal.update(legacy["id"], status="unavailable", error_code="broker_route_unavailable")
    unknown, _ = journal.create(intake(body), "parked-original")
    journal.update(
        unknown["id"],
        status="unknown",
        error_code="broker_route_unavailable",
        polling_finished=1,
        monitor_until=clock.now - 1,
    )
    runner, fixture = waiting_worker(store, body)
    asyncio.run(runner.run_once())
    assert journal.get(legacy["id"])["status"] == "succeeded"
    assert journal.get(unknown["id"])["status"] == "unknown"
    assert len(queue_posts(fixture)) == 1
    assert queue_posts(fixture)[0][2]["request_key"] == legacy["job_key"]
    assert journal.pending() is None


def test_sent_unknown_restart_only_queries_original_job(store, body, clock):
    journal = IntakeStore(store)
    record, _ = journal.create(intake(body), "lost-original")
    runner, fixture = waiting_worker(store, body)
    fixture.lost_submit = True
    asyncio.run(runner.run_once())
    assert journal.get(record["id"])["status"] == "unknown"
    assert len(queue_posts(fixture)) == 1
    restarted = RecognitionWorker(Store(store.data_dir), runner.config, transport=fixture)
    asyncio.run(restarted.run_once())
    assert journal.get(record["id"])["status"] == "succeeded"
    assert len(queue_posts(fixture)) == 1
    assert all(record["job_key"] in path for method, path, _ in fixture.calls if method == "GET")


def test_expired_submitted_job_retains_original_key_without_auto_resubmit(store, body, clock):
    journal = IntakeStore(store)
    record, _ = journal.create(intake(body), "expired-original")
    runner, fixture = waiting_worker(store, body)
    fixture.expired = True
    asyncio.run(runner.run_once())
    saved = journal.get(record["id"])
    assert saved["status"] == "failed" and saved["error_code"] == "broker_expired"
    assert saved["job_key"] == record["job_key"] and len(queue_posts(fixture)) == 1
    clock.now += 86400
    asyncio.run(runner.run_once())
    assert len(queue_posts(fixture)) == 1 and journal.pending() is None


@pytest.mark.parametrize(
    ("state", "reasons", "expected_ready", "reason"),
    [
        ("blocked", ["free_evidence_not_current"], False, "broker_qualification_unavailable"),
        ("blocked", ["local_cap:requests-day"], False, "broker_capacity_unavailable"),
        ("unknown", ["credential_inventory_unknown"], None, "broker_readiness_unknown"),
        ("ready", [], True, None),
    ],
)
def test_capabilities_separate_configured_and_current_readiness(
    store, body, clock, state, reasons, expected_ready, reason
):
    runner, fixture = waiting_worker(store, body)
    fixture.diagnostics["targets"][0].update(state=state, reasons=reasons)
    fixture.diagnostics["ready_targets"] = int(state == "ready")
    asyncio.run(runner.refresh_readiness())
    broker = runner.capabilities()["engines"]["broker"]
    assert broker["configured"] is True
    assert broker["currently_ready"] is expected_ready
    assert broker["available"] is (expected_ready is True) and broker["reason"] == reason
    assert not queue_posts(fixture)
    clock.now += 31
    assert runner.capabilities()["engines"]["broker"]["reason"] == "broker_readiness_stale"
    fixture.diagnostics["targets"][0].update(state="ready", reasons=[])
    asyncio.run(runner.refresh_readiness())
    assert runner.capabilities()["engines"]["broker"]["available"] is True


def test_capabilities_require_matching_target_and_running_queue_worker(store, body, clock):
    runner, fixture = waiting_worker(store, body)
    fixture.diagnostics["targets"][0]["model"] = "unrelated-model"
    asyncio.run(runner.refresh_readiness())
    assert runner.capabilities()["engines"]["broker"]["reason"] == "broker_route_unavailable"
    clock.now += 31
    fixture.diagnostics["targets"][0]["model"] = "fixture-model"
    fixture.diagnostics["queue_worker"]["running"] = False
    asyncio.run(runner.refresh_readiness())
    assert runner.capabilities()["engines"]["broker"]["reason"] == "broker_worker_unavailable"


def test_malformed_readiness_metadata_keeps_readiness_unknown(store, body, clock):
    runner, fixture = waiting_worker(store, body)
    fixture.diagnostics["queue_worker"] = None
    asyncio.run(runner.refresh_readiness())
    broker = runner.capabilities()["engines"]["broker"]
    assert broker["currently_ready"] is None and broker["available"] is False
    assert broker["reason"] == "broker_readiness_unknown"


def test_readiness_api_refresh_does_not_block_saving_intake_while_qualification_paused(store, body):
    runner, fixture = waiting_worker(store, body)
    fixture.diagnostics["ready_targets"] = 0
    fixture.diagnostics["targets"][0].update(state="blocked", reasons=["free_evidence_not_current"])
    app = create_app(store, TOKEN, recognition_config=runner.config, recognition_transport=fixture)
    with TestClient(app) as client:
        client.headers["Authorization"] = "Bearer " + TOKEN
        response = client.get("/v1/recognition")
        assert response.status_code == 200
        broker = response.json()["engines"]["broker"]
        assert broker["configured"] is True and broker["available"] is False
        assert broker["reason"] == "broker_qualification_unavailable"
        response = client.post(
            "/v1/intakes",
            json=intake(body).model_dump(mode="json", by_alias=True),
            headers={"Idempotency-Key": "save-while-paused"},
        )
        assert response.status_code == 201 and response.json()["status"] == "queued"
    assert not queue_posts(fixture)
