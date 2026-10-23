"""Only a complete same-attempt work_busy rejection may repeat a queue POST."""

import asyncio
import hashlib
import json
from datetime import UTC, datetime
from types import SimpleNamespace

import pytest

from pixelreceipt_backend import intake_store, recognition
from pixelreceipt_backend.intake_store import IntakeStore
from pixelreceipt_backend.recognition import BrokerConfig, BrokerHttp, RecognitionError
from pixelreceipt_backend.store import Store, canonical

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


class BusyBroker(BrokerFixture):
    def __init__(self, store, candidate, rejections=1):
        super().__init__(store, candidate)
        self.rejections = rejections

    async def request(self, method, path, payload=None):
        if method == "POST" and path == "/v1/queue" and self.rejections:
            self.calls.append((method, path, json.loads(canonical(payload))))
            saved = IntakeStore(self.store).raw(
                next(
                    row["id"]
                    for row in IntakeStore(self.store).list(100, 0)
                    if row["job_key"] == payload["request_key"]
                )
            )
            assert saved["status"] == "submitting"
            assert json.loads(saved["payload"]) == payload
            self.rejections -= 1
            raise RecognitionError("broker_work_busy", status_code=503)
        return await super().request(method, path, payload)


def worker(store, body, rejections=1):
    transport = BusyBroker(store, body["candidate"], rejections)
    config = BrokerConfig("http://127.0.0.1:18081", "/unused/fixture-token")
    return recognition.RecognitionWorker(store, config, transport=transport), transport


def posts(transport):
    return [call for call in transport.calls if call[:2] == ("POST", "/v1/queue")]


def response(content=b'{"error":"work_busy"}', *, status=503, extra=b"", length=None):
    return (
        (
            f"HTTP/1.1 {status} Synthetic\r\nContent-Type: application/json\r\n"
            f"Content-Length: {len(content) if length is None else length}\r\n"
            "Connection: close\r\n"
        ).encode()
        + extra
        + b"\r\n"
        + content
    )


@pytest.mark.parametrize(
    ("raw", "expected"),
    [
        (response(), "broker_work_busy"),
        (response(b'{"error":"internal_error"}'), "broker_http_error"),
        (response(b'{"error":"work_busy","extra":true}'), "broker_http_error"),
        (response(b'{"error":"work_busy","error":"work_busy"}'), "broker_transport_unknown"),
        (response(b'{"error":"work_busy","number":NaN}'), "broker_transport_unknown"),
        (response('{"error":"work_busy"}'.encode("utf-16")), "broker_transport_unknown"),
        (response(length=100), "broker_transport_unknown"),
        (response(extra=b"Content-Length: 21\r\n"), "broker_transport_unknown"),
        (response(extra=b"Bad Header: value\r\n"), "broker_transport_unknown"),
        (response(extra=b"Transfer-Encoding: chunked\r\n"), "broker_protocol_error"),
        (response().replace(b"Connection: close\r\n", b""), "broker_http_error"),
        (response(status=502), "broker_http_error"),
    ],
)
def test_complete_loopback_response_is_classified_without_automatic_retry(
    monkeypatch, raw, expected
):
    monkeypatch.setattr(recognition, "private_file", lambda *args, **kwargs: TOKEN)

    async def scenario():
        calls = []

        async def handle(reader, writer):
            calls.append(await reader.readuntil(b"\r\n\r\n"))
            writer.write(raw)
            await writer.drain()
            writer.close()
            try:
                await writer.wait_closed()
            except OSError:
                pass

        server = await asyncio.start_server(handle, "127.0.0.1", 0)
        port = server.sockets[0].getsockname()[1]
        try:
            http = BrokerHttp(BrokerConfig(f"http://127.0.0.1:{port}", "/unused"))
            with pytest.raises(RecognitionError) as caught:
                await http.request("POST", "/v1/queue", {"request_key": "synthetic"})
            assert caught.value.code == expected
            assert len(calls) == 1
        finally:
            server.close()
            await server.wait_closed()

    asyncio.run(scenario())


@pytest.mark.parametrize(
    ("raw", "expected"),
    [
        (response(), "broker_work_busy"),
        (response(length=100), "broker_transport_unknown"),
        (response(b'{"error":"internal_error"}'), "broker_transport_unknown"),
        (response(b'{"request_key":"synthetic"}', status=200), "broker_transport_unknown"),
        (b"", "broker_transport_unknown"),
    ],
)
def test_write_failure_needs_complete_busy_response_on_same_socket(monkeypatch, raw, expected):
    monkeypatch.setattr(recognition, "private_file", lambda *args, **kwargs: TOKEN)

    class BrokenWriter:
        def write(self, _data):
            pass

        async def drain(self):
            raise BrokenPipeError

        def close(self):
            pass

        async def wait_closed(self):
            pass

    async def scenario():
        reader = asyncio.StreamReader()
        reader.feed_data(raw)
        reader.feed_eof()
        connections = []

        async def open_connection(*args, **kwargs):
            connections.append((args, kwargs))
            return reader, BrokenWriter()

        monkeypatch.setattr(asyncio, "open_connection", open_connection)
        http = BrokerHttp(BrokerConfig("http://127.0.0.1:18081", "/unused"))
        with pytest.raises(RecognitionError) as caught:
            await http.request("POST", "/v1/queue", {"data": "x" * 1024 * 1024})
        assert caught.value.code == expected
        assert len(connections) == 1

    asyncio.run(scenario())


def test_busy_restart_reuses_exact_frozen_payload_key_endpoint_and_deadline(store, body, clock):
    journal = IntakeStore(store)
    record, _ = journal.create(intake(body), "busy-restart")
    runner, transport = worker(store, body)
    asyncio.run(runner.run_once())
    saved = journal.raw(record["id"])
    assert saved["status"] == "queued" and saved["error_code"] == "broker_work_busy"
    assert saved["next_poll_at"] == clock.now + 1
    assert json.loads(saved["config_snapshot"])["work_busy_retry"] == {
        "first_rejected_at": clock.now,
        "last_rejected_at": clock.now,
        "retry_until": clock.now + 240,
        "rejections": 1,
    }
    assert journal.pending() is None
    clock.now += 1
    restarted = recognition.RecognitionWorker(
        Store(store.data_dir), runner.config, transport=transport
    )
    asyncio.run(restarted.run_once())
    complete = journal.raw(record["id"])
    assert complete["status"] == "succeeded"
    assert len(posts(transport)) == 2 and posts(transport)[0][2] == posts(transport)[1][2]
    assert complete["payload"] == saved["payload"]
    assert complete["monitor_until"] == saved["monitor_until"]
    assert sum(path == "/v1/routes/explain" for _, path, _ in transport.calls) == 1


def test_busy_backoff_is_persistent_bounded_and_does_not_block_other_due_intake(store, body, clock):
    journal = IntakeStore(store)
    first, _ = journal.create(intake(body), "busy-first")
    runner, transport = worker(store, body, rejections=10)
    first_rejected = clock.now
    for count, delay in enumerate([1, 2, 4, 8, 8], start=1):
        asyncio.run(runner.run_once())
        saved = journal.raw(first["id"])
        assert saved["next_poll_at"] == clock.now + delay
        assert json.loads(saved["config_snapshot"])["work_busy_retry"]["rejections"] == count
        assert journal.pending() is None
        clock.now += delay
    second, _ = journal.create(intake(body), "independent-due")
    journal.update(first["id"], next_poll_at=clock.now + 8)
    transport.rejections = 0
    asyncio.run(runner.run_once())
    assert journal.get(second["id"])["status"] == "succeeded"
    assert journal.get(first["id"])["status"] == "queued"
    clock.now = first_rejected + 240
    before = len(posts(transport))
    asyncio.run(runner.run_once())
    exhausted = journal.get(first["id"])
    assert exhausted["status"] == "failed"
    assert exhausted["error_code"] == "broker_work_busy_exhausted"
    assert exhausted["polling_finished"] is True and journal.pending() is None
    assert len(posts(transport)) == before


def test_original_deadline_shorter_than_busy_budget_is_not_extended(store, body, clock):
    journal = IntakeStore(store)
    record, _ = journal.create(intake(body), "short-original-deadline")
    runner, transport = worker(store, body)
    asyncio.run(runner.run_once())
    saved = journal.raw(record["id"])
    snapshot = json.loads(saved["config_snapshot"])
    payload = json.loads(saved["payload"])
    deadline = clock.now + 2
    payload["deadline"] = datetime.fromtimestamp(deadline, UTC).isoformat()
    snapshot["payload_sha256"] = hashlib.sha256(canonical(payload).encode()).hexdigest()
    snapshot["work_busy_retry"]["retry_until"] = deadline
    journal.update(
        record["id"],
        payload=canonical(payload),
        config_snapshot=canonical(snapshot),
        monitor_until=deadline,
    )
    clock.now = deadline
    asyncio.run(runner.run_once())
    assert journal.get(record["id"])["error_code"] == "broker_work_busy_exhausted"
    assert len(posts(transport)) == 1
    assert journal.raw(record["id"])["payload"] == canonical(payload)


@pytest.mark.parametrize("error", ["broker_transport_unknown", "broker_http_error"])
def test_busy_then_ambiguous_send_never_retries_again_even_with_saved_busy_history(
    store, body, clock, error, monkeypatch
):
    journal = IntakeStore(store)
    record, _ = journal.create(intake(body), "busy-then-unknown")
    runner, transport = worker(store, body)
    asyncio.run(runner.run_once())
    clock.now += 1
    original = transport.request

    async def ambiguous_send(method, path, payload=None):
        if method == "POST" and path == "/v1/queue":
            transport.calls.append((method, path, payload))
            transport.job = payload["request_key"]
            raise RecognitionError(error, status_code=503 if error == "broker_http_error" else None)
        return await original(method, path, payload)

    monkeypatch.setattr(transport, "request", ambiguous_send)
    asyncio.run(runner.run_once())
    assert journal.get(record["id"])["status"] == "unknown"
    assert len(posts(transport)) == 2
    restarted = recognition.RecognitionWorker(
        Store(store.data_dir), runner.config, transport=transport
    )
    asyncio.run(restarted.run_once())
    assert journal.get(record["id"])["status"] == "succeeded"
    assert len(posts(transport)) == 2


def test_inflight_busy_retry_cannot_wait_past_remaining_retry_window(
    store, body, clock, monkeypatch
):
    journal = IntakeStore(store)
    record, _ = journal.create(intake(body), "busy-inflight-timeout")
    runner, transport = worker(store, body)
    asyncio.run(runner.run_once())
    saved = journal.raw(record["id"])
    snapshot = json.loads(saved["config_snapshot"])
    snapshot["work_busy_retry"].update(first_rejected_at=clock.now - 239, retry_until=clock.now + 1)
    journal.update(record["id"], config_snapshot=canonical(snapshot), next_poll_at=0)
    clock.now += 0.99
    original = transport.request

    async def stalled_send(method, path, payload=None):
        if method == "POST" and path == "/v1/queue":
            transport.calls.append((method, path, payload))
            transport.job = payload["request_key"]
            await asyncio.Event().wait()
        return await original(method, path, payload)

    monkeypatch.setattr(transport, "request", stalled_send)
    asyncio.run(runner.run_once())
    assert journal.get(record["id"])["status"] == "unknown"
    assert journal.get(record["id"])["error_code"] == "broker_transport_unknown"
    assert len(posts(transport)) == 2
    monkeypatch.setattr(transport, "request", original)
    asyncio.run(runner.run_once())
    assert journal.get(record["id"])["status"] == "succeeded"
    assert len(posts(transport)) == 2


def test_immediate_result_with_expiry_uses_frozen_payload_deadline(store, body, clock, monkeypatch):
    journal = IntakeStore(store)
    record, _ = journal.create(intake(body), "immediate-expiry")
    runner, transport = worker(store, body, rejections=0)
    original = transport.request

    async def expires(method, path, payload=None):
        result = await original(method, path, payload)
        if method == "GET" and not path.endswith("/result"):
            result["expires_at"] = datetime.fromtimestamp(clock.now + 60, UTC).isoformat()
        return result

    monkeypatch.setattr(transport, "request", expires)
    asyncio.run(runner.run_once())
    saved = journal.raw(record["id"])
    assert saved["status"] == "succeeded" and saved["monitor_until"] == clock.now + 60
    assert int(datetime.fromisoformat(json.loads(saved["payload"])["deadline"]).timestamp()) == (
        clock.now + 900
    )


def test_config_or_payload_drift_cannot_resubmit_saved_busy_request(store, body, clock):
    journal = IntakeStore(store)
    record, _ = journal.create(intake(body), "busy-config-drift")
    runner, transport = worker(store, body)
    asyncio.run(runner.run_once())
    clock.now += 1
    runner.config = BrokerConfig("http://127.0.0.1:18082", "/unused/fixture-token")
    asyncio.run(runner.run_once())
    assert journal.get(record["id"])["error_code"] == "broker_config_changed"
    assert len(posts(transport)) == 1

    tampered, _ = journal.create(intake(body), "busy-payload-drift")
    journal.update(record["id"], polling_finished=1)
    runner, transport = worker(store, body)
    asyncio.run(runner.run_once())
    saved = journal.raw(tampered["id"])
    payload = json.loads(saved["payload"])
    payload["max_output_tokens"] += 1
    journal.update(tampered["id"], payload=canonical(payload))
    clock.now += 1
    asyncio.run(runner.run_once())
    assert journal.get(tampered["id"])["error_code"] == "stored_job_integrity_failure"
    assert len(posts(transport)) == 1
