import asyncio
import base64
import hashlib
import io
import json
import time

import pytest
from fastapi.testclient import TestClient
from pypdf import PdfReader

from pixelreceipt_backend.api import create_app
from pixelreceipt_backend.auth import password_hash
from pixelreceipt_backend.intake_models import CreateIntake
from pixelreceipt_backend.intake_store import IntakeStore
from pixelreceipt_backend.models import CreateDraft
from pixelreceipt_backend.recognition import (
    BrokerConfig,
    BrokerHttp,
    RecognitionError,
    RecognitionWorker,
    derive_image,
    load_config,
)
from pixelreceipt_backend.store import Store, StoreError, canonical

from .conftest import REVIEWER_TOKEN, TOKEN

DEVICE = "00000000-0000-4000-8000-000000000001"
PASSWORD = "synthetic-mobile-password"


def intake(body, purpose="purchase", engine="broker"):
    return CreateIntake.model_validate(
        {
            "schema": "pixelreceipt-mobile-ingest-1",
            "purpose": purpose,
            "engine": engine,
            "user_notes": "<script>untrusted</script> 牙膏幫朋友買，不是來源標籤",
            "evidence": [
                {
                    "sha256": body["evidence"][0]["sha256"],
                    "role": "receipt" if purpose == "purchase" else "menu",
                    "notes": "第一張",
                }
            ],
        }
    )


class BrokerFixture:
    def __init__(self, store, candidate, *, mode="vision", lost_submit=False, truncated=False):
        self.store = store
        self.candidate = candidate
        self.calls = []
        self.mode = mode
        self.lost_submit = lost_submit
        self.truncated = truncated
        self.job = None

    async def request(self, method, path, payload=None):
        self.calls.append((method, path, payload))
        if path == "/v1/routes/explain":
            row = IntakeStore(self.store).pending()
            assert row["status"] == "preparing"
            assert json.loads(row["payload"]) == payload
            return (
                {"selected_target_id": "fixture"}
                if self.mode == "vision"
                else {
                    "selected_target_id": None,
                    "preflight_required": True,
                    "preflight_target_id": "google-pdf-fixture",
                }
            )
        if method == "POST":
            self.job = payload["request_key"]
            row = IntakeStore(self.store).pending()
            assert row["status"] == "submitting"
            assert row["job_key"] == self.job
            assert row["monitor_until"] > time.time()
            assert json.loads(row["payload"]) == payload
            if self.lost_submit:
                raise RecognitionError("broker_transport_unknown")
            return {"request_key": self.job, "state": "queued"}
        if path.endswith("/result"):
            return {
                "request_key": "q-fixture",
                "state": "completed_usage_unknown",
                "provider": "google",
                "model": "fixture-model",
                "finish_reason": "stop",
                "response_truncated": self.truncated,
                "result": {"text": canonical(self.candidate)},
            }
        return {
            "request_key": self.job,
            "execution_key": "q-fixture",
            "state": "completed",
            "result_available": True,
        }


def worker(store, body, **kwargs):
    fixture = BrokerFixture(store, body["candidate"], **kwargs)
    config = BrokerConfig(
        "http://127.0.0.1:18081", "/unused/fixture-token", input_mode=kwargs.get("mode", "vision")
    )
    return RecognitionWorker(store, config, transport=fixture), fixture


def test_intake_immutable_notes_order_role_idempotency_and_list_summary(client, body, store):
    request = intake(body).model_dump(mode="json", by_alias=True)
    response = client.post("/v1/intakes", json=request, headers={"Idempotency-Key": "mobile-1"})
    assert response.status_code == 201
    saved = response.json()
    assert saved["input"] == request
    assert saved["evidence_snapshot"][0]["notes"] == "第一張"
    assert (
        client.post("/v1/intakes", json=request, headers={"Idempotency-Key": "mobile-1"}).json()
        == saved
    )
    request["user_notes"] = "changed"
    assert (
        client.post(
            "/v1/intakes", json=request, headers={"Idempotency-Key": "mobile-1"}
        ).status_code
        == 409
    )
    summary = client.get("/v1/intakes").json()["intakes"][0]
    assert "result" not in summary and "evidence_snapshot" not in summary
    assert set(summary["input"]) == {"purpose", "engine", "user_notes"}
    assert client.get("/v1/intakes/" + saved["id"]).json() == saved
    reopened = Store(store.data_dir)
    assert IntakeStore(reopened).get(saved["id"]) == saved


@pytest.mark.parametrize(
    "change",
    [
        {"purpose": "purchase", "evidence": []},
        {"purpose": "wat"},
        {"extra": "bad"},
        {"user_notes": "x" * 4001},
        {"engine": "direct"},
    ],
)
def test_invalid_intake_contract(client, body, change):
    value = intake(body).model_dump(mode="json", by_alias=True)
    value.update(change)
    assert (
        client.post("/v1/intakes", json=value, headers={"Idempotency-Key": "bad-1"}).status_code
        == 422
    )


def test_purchase_requires_receipt_and_unknown_duplicate_evidence(client, body):
    value = intake(body, "reference").model_dump(mode="json", by_alias=True)
    value["purpose"] = "purchase"
    assert (
        client.post("/v1/intakes", json=value, headers={"Idempotency-Key": "bad-1"}).status_code
        == 422
    )
    value["purpose"] = "reference"
    value["evidence"] *= 2
    assert (
        client.post("/v1/intakes", json=value, headers={"Idempotency-Key": "bad-1"}).status_code
        == 422
    )
    value["evidence"] = [{"sha256": "f" * 64, "role": "menu", "notes": None}]
    assert (
        client.post("/v1/intakes", json=value, headers={"Idempotency-Key": "bad-1"}).status_code
        == 422
    )


@pytest.mark.parametrize("mode", ["vision", "document_pdf"])
def test_real_derivation_queue_complete_one_draft_provenance(store, body, png, mode):
    journal = IntakeStore(store)
    record, _ = journal.create(intake(body), "flow-1")
    runner, fixture = worker(store, body, mode=mode)
    asyncio.run(runner.run_once())
    result = journal.get(record["id"])
    assert result["status"] == "succeeded", result
    assert result["draft_id"]
    assert store.get_draft(result["draft_id"])["status"] == "needs_review"
    assert store.get_draft(result["draft_id"])["candidate"] == body["candidate"] | {
        "adjustments": [body["candidate"]["adjustments"][0] | {"amount_effect": None}]
    }
    assert result["result"]["raw_outcome"]["state"] == "completed_usage_unknown"
    assert result["result"]["derivatives"][0]["source_sha256"] == body["evidence"][0]["sha256"]
    assert store.get_evidence(body["evidence"][0]["sha256"], content=True)[1] == png
    submitted = [c[2] for c in fixture.calls if c[1] == "/v1/queue"]
    assert len(submitted) == 1
    parts = submitted[0]["input"]["messages"][0]["content"]
    assert parts[0]["type"] == "text" and intake(body).user_notes in parts[0]["text"]
    assert len(parts) == 2
    if mode == "document_pdf":
        assert submitted[0]["capability"] == "text_generation"
        assert parts[1]["type"] == "document" and parts[1]["mime_type"] == "application/pdf"
        pdf = base64.b64decode(parts[1]["data"])
        parsed = PdfReader(io.BytesIO(pdf))
        assert len(parsed.pages) == 1 and len(parsed.pages[0].images) == 1
        assert result["result"]["transport_document"]["sha256"] == hashlib.sha256(pdf).hexdigest()
        assert submitted[0]["requirements"] == {"features": ["document_input"]}
    else:
        assert submitted[0]["capability"] == "vision" and parts[1]["type"] == "image"
    asyncio.run(runner.run_once())
    assert len(store.list_drafts(100, 0)) == 1


def test_lost_submit_restart_queries_original_key_without_resubmit(store, body):
    journal = IntakeStore(store)
    record, _ = journal.create(intake(body), "lost-1")
    runner, fixture = worker(store, body, lost_submit=True)
    asyncio.run(runner.run_once())
    unknown = journal.get(record["id"])
    assert unknown["status"] == "unknown"
    restarted = RecognitionWorker(Store(store.data_dir), runner.config, transport=fixture)
    asyncio.run(restarted.run_once())
    assert journal.get(record["id"])["status"] == "succeeded"
    assert len([c for c in fixture.calls if c[1] == "/v1/queue"]) == 1
    assert all(record["job_key"] in c[1] for c in fixture.calls if c[0] == "GET")


def test_unknown_config_changed_and_expired_parks_without_reroute(store, body):
    journal = IntakeStore(store)
    first, _ = journal.create(intake(body), "unknown-1")
    runner, fixture = worker(store, body, lost_submit=True)
    asyncio.run(runner.run_once())
    changed = RecognitionWorker(
        store, BrokerConfig("http://127.0.0.1:18082", "/unused/token"), transport=fixture
    )
    before = len(fixture.calls)
    asyncio.run(changed.run_once())
    assert len(fixture.calls) == before
    assert journal.get(first["id"])["error_code"] == "broker_config_changed"
    journal.update(first["id"], monitor_until=int(time.time()) - 1)
    asyncio.run(changed.run_once())
    assert journal.get(first["id"])["polling_finished"] is True
    second, _ = journal.create(intake(body), "independent-2")
    fixture.lost_submit = False
    asyncio.run(runner.run_once())
    assert journal.get(second["id"])["status"] == "succeeded"
    assert journal.get(first["id"])["status"] == "unknown"


@pytest.mark.parametrize("truncated,bad_text", [(True, False), (False, True)])
def test_raw_incomplete_or_invalid_candidate_saved_without_draft(store, body, truncated, bad_text):
    journal = IntakeStore(store)
    record, _ = journal.create(intake(body), "failed-result")
    runner, fixture = worker(store, body, truncated=truncated)
    if bad_text:
        fixture.candidate = {"invented": 100.5}
    asyncio.run(runner.run_once())
    result = journal.get(record["id"])
    assert result["status"] == "failed" and result["draft_id"] is None
    assert result["result"]["raw_outcome"]["result"]["text"]
    assert store.list_drafts(100, 0) == []


def test_reference_never_creates_draft_and_confirm_boundary(store, body):
    journal = IntakeStore(store)
    record, _ = journal.create(intake(body, "reference"), "menu")
    runner, _ = worker(store, body)
    asyncio.run(runner.run_once())
    reference = journal.get(record["id"])
    assert reference["status"] == "succeeded" and reference["draft_id"] is None
    assert reference["reference_candidate"]["items"]
    draft, _ = store.create_draft(CreateDraft.model_validate(body), "manual-guard-fixture")
    with store.connection(write=True) as connection:
        connection.execute("UPDATE intakes SET draft_id=? WHERE id=?", (draft["id"], record["id"]))
    from pixelreceipt_backend.ledger_models import ConfirmReceipt

    with pytest.raises(StoreError, match="reference_not_confirmable"):
        store.confirm(draft["id"], ConfirmReceipt(expected_revision=1), "reference-confirm")


def test_not_configured_local_and_no_eligible_route_are_explicit(store, body):
    journal = IntakeStore(store)
    record, _ = journal.create(intake(body, engine="local"), "no-local")
    runner = RecognitionWorker(store)
    asyncio.run(runner.run_once())
    assert journal.get(record["id"])["status"] == "unavailable"
    assert runner.capabilities()["engines"]["local"]["available"] is False
    record, _ = journal.create(intake(body), "no-route")
    runner, fixture = worker(store, body)

    async def rejected(method, path, payload=None):
        assert path == "/v1/routes/explain"
        return {"selected_target_id": None, "permanent_rejection": True}

    fixture.request = rejected
    asyncio.run(runner.run_once())
    assert journal.get(record["id"])["status"] == "unavailable"


def login_body():
    return {"device_id": DEVICE, "device_name": "合成 Pixel", "password": PASSWORD}


def test_mobile_token_role_hash_logout_relogin_and_reviewer_revoke(store, png):
    app = create_app(
        store,
        TOKEN,
        reviewer_token=REVIEWER_TOKEN,
        reviewer_password_hash=password_hash(PASSWORD),
        public_origin="https://testserver",
    )
    with TestClient(app, base_url="https://testserver") as client:
        first = client.post("/v1/mobile/login", json=login_body())
        assert first.status_code == 200
        token = first.json()["access_token"]
        client.headers["Authorization"] = "Bearer " + token
        assert client.get("/health").status_code == 200
        assert (
            client.post(
                "/v1/evidence", content=png, headers={"Content-Type": "image/png"}
            ).status_code
            == 200
        )
        for method, path in (
            ("PUT", "/v2/drafts/x/review"),
            ("POST", "/v2/drafts/x/confirm"),
            ("POST", "/v1/ledger/x/void"),
            ("GET", "/v1/devices"),
        ):
            assert client.request(method, path, json={}).status_code == 403
        with store.connection() as connection:
            row = dict(connection.execute("SELECT * FROM device_sessions").fetchone())
        assert token not in canonical(row) and PASSWORD not in canonical(row)
        assert row["token_hash"] == hashlib.sha256(token.encode()).hexdigest()
        assert 29 * 86400 < row["expires_at"] - time.time() <= 30 * 86400
        second = client.post("/v1/mobile/login", json=login_body()).json()["access_token"]
        assert client.get("/health").status_code == 401
        client.headers["Authorization"] = "Bearer " + second
        assert client.post("/v1/mobile/logout").status_code == 200
        assert client.get("/health").status_code == 401
        third = client.post("/v1/mobile/login", json=login_body()).json()["access_token"]
        client.headers["Authorization"] = "Bearer " + REVIEWER_TOKEN
        assert client.get("/v1/devices").json()["devices"][0]["device_name"] == "合成 Pixel"
        assert client.delete("/v1/devices/" + DEVICE).status_code == 200
        client.headers["Authorization"] = "Bearer " + third
        assert client.get("/health").status_code == 401


def test_mobile_login_bounded_unknown_fields_https_and_shared_rate_gate(store):
    app = create_app(store, TOKEN, reviewer_password_hash=password_hash(PASSWORD))
    with TestClient(app, base_url="https://testserver") as client:
        assert client.post("/v1/mobile/login", content=b"x" * 8193).status_code == 415
        assert client.post("/v1/mobile/login", json=login_body() | {"extra": 1}).status_code == 422
        assert (
            client.post("/v1/mobile/login", json=login_body() | {"device_id": "bad"}).status_code
            == 422
        )
        assert (
            client.post(
                "/v1/mobile/login", json=login_body(), headers={"Origin": "https://evil.test"}
            ).status_code
            == 403
        )
        assert (
            client.post("/v1/mobile/login", json=login_body() | {"password": "wrong"}).status_code
            == 401
        )
        assert client.post("/v1/mobile/login", json=login_body()).status_code == 429
    with TestClient(
        create_app(store, TOKEN, reviewer_password_hash=password_hash(PASSWORD))
    ) as client:
        assert client.post("/v1/mobile/login", json=login_body()).status_code == 403


def test_config_rejects_unsafe_file_endpoint_and_unknown_fields(tmp_path):
    token = tmp_path / "token"
    token.write_text(TOKEN)
    token.chmod(0o600)
    config = tmp_path / "config.json"
    value = {
        "enabled": True,
        "base_url": "http://127.0.0.1:18081",
        "token_file": str(token),
        "input_mode": "document_pdf",
        "provider": "google",
        "model": "fixture",
    }
    config.write_text(canonical(value))
    config.chmod(0o600)
    loaded, reason = load_config(config)
    assert reason is None and loaded.input_mode == "document_pdf"
    for bad in ({"base_url": "https://example.com"}, {"extra": 1}, {"input_mode": "fallback"}):
        config.write_text(canonical(value | bad))
        assert load_config(config)[0] is None
    config.write_text(canonical(value))
    token.chmod(0o644)
    assert load_config(config)[0] is None


def test_real_async_http_no_redirect_and_bounded_header(tmp_path):
    token = tmp_path / "token"
    token.write_text(TOKEN)
    token.chmod(0o600)

    async def scenario():
        calls = []

        async def handle(reader, writer):
            head = await reader.readuntil(b"\r\n\r\n")
            calls.append(head)
            writer.write(
                b"HTTP/1.1 302 Found\r\nLocation: http://example.com\r\nContent-Length: 0\r\n\r\n"
            )
            await writer.drain()
            writer.close()
            await writer.wait_closed()

        server = await asyncio.start_server(handle, "127.0.0.1", 0)
        port = server.sockets[0].getsockname()[1]
        try:
            http = BrokerHttp(BrokerConfig(f"http://127.0.0.1:{port}", token))
            with pytest.raises(RecognitionError, match="broker_http_error"):
                await http.request("GET", "/v1/queue/fixture")
            assert len(calls) == 1
        finally:
            server.close()
            await server.wait_closed()

    asyncio.run(scenario())


def test_derivation_max_side_and_original_unchanged(png):
    data, metadata = asyncio.run(derive_image(png, "image/png"))
    assert data.startswith(b"\xff\xd8\xff") and len(data) <= 384 * 1024
    assert metadata["width"] == 10 and metadata["height"] == 8


def test_raw_result_survives_crash_and_recovers_without_network(store, body, monkeypatch):
    journal = IntakeStore(store)
    record, _ = journal.create(intake(body), "raw-before-link")
    runner, fixture = worker(store, body)

    def disk_gap(*_args):
        raise OSError("synthetic crash after raw save")

    monkeypatch.setattr(runner.intakes, "complete", disk_gap)
    with pytest.raises(OSError):
        asyncio.run(runner.run_once())
    assert journal.get(record["id"])["result"]
    # Even without current Broker configuration the already-owned raw output can finish.
    recovered = RecognitionWorker(Store(store.data_dir))
    journal.update(record["id"], next_poll_at=0)
    asyncio.run(recovered.run_once())
    assert journal.get(record["id"])["status"] == "succeeded"
    assert len([c for c in fixture.calls if c[1] == "/v1/queue"]) == 1


def test_cached_result_corruption_fails_closed(store, body, monkeypatch):
    journal = IntakeStore(store)
    record, _ = journal.create(intake(body), "raw-corrupt")
    runner, _ = worker(store, body)

    def disk_gap(*_args):
        raise OSError("synthetic crash after raw save")

    monkeypatch.setattr(runner.intakes, "complete", disk_gap)
    with pytest.raises(OSError):
        asyncio.run(runner.run_once())
    journal.update(record["id"], execution_key="q-other", next_poll_at=0)
    asyncio.run(RecognitionWorker(store).run_once())
    assert journal.get(record["id"])["status"] == "failed"
    assert journal.get(record["id"])["error_code"] == "stored_job_integrity_failure"
    assert store.list_drafts(100, 0) == []


def test_lifespan_cancels_long_async_query_without_blocking_api_or_shutdown(store):
    async def scenario():
        entered = asyncio.Event()
        cancelled = asyncio.Event()

        class WaitingWorker(RecognitionWorker):
            async def run_once(self):
                entered.set()
                try:
                    await asyncio.Event().wait()
                finally:
                    cancelled.set()

        runner = WaitingWorker(store)
        async with runner.lifespan(None):
            await asyncio.wait_for(entered.wait(), 1)
            # The loop can still serve unrelated work while recognition waits.
            assert await asyncio.wait_for(asyncio.to_thread(store.list_drafts, 20, 0), 1) == []
        assert cancelled.is_set()

    asyncio.run(scenario())


def test_worker_storage_error_is_visible_in_diagnostics(store, monkeypatch):
    async def broken():
        raise OSError("synthetic disk failure")

    async def scenario():
        runner = RecognitionWorker(store)
        monkeypatch.setattr(runner, "run_once", broken)
        async with runner.lifespan(None):
            await asyncio.sleep(0.01)
            assert runner.capabilities()["worker"] == {
                "status": "degraded",
                "error_code": "recognition_storage_unavailable",
            }

    asyncio.run(scenario())


def test_v3_to_v4_preserves_rows_and_future_version_refuses(store, body):
    import sqlite3

    draft, _ = store.create_draft(CreateDraft.model_validate(body), "v3-preserved")
    store.create_session("synthetic-old-session", "old-csrf")
    with store.connection(write=True) as connection:
        connection.execute("DROP TABLE intakes")
        connection.execute("DROP TABLE device_sessions")
        connection.execute("PRAGMA user_version=3")
        before = {
            table: [tuple(row) for row in connection.execute(f"SELECT * FROM {table}")]
            for table in ("drafts", "evidence", "sessions", "reviews", "products", "ledger")
        }
    reopened = Store(store.data_dir)
    assert reopened.get_draft(draft["id"]) == draft
    with reopened.connection() as connection:
        assert connection.execute("PRAGMA user_version").fetchone()[0] == 4
        assert not connection.execute("PRAGMA foreign_key_check").fetchall()
        assert all(
            [tuple(row) for row in connection.execute(f"SELECT * FROM {table}")] == rows
            for table, rows in before.items()
        )
    with reopened.connection(write=True) as connection:
        connection.execute("PRAGMA user_version=5")
    with pytest.raises(RuntimeError, match="unsupported database version"):
        Store(store.data_dir)
    with sqlite3.connect(store.database) as connection:
        assert connection.execute("PRAGMA user_version").fetchone()[0] == 5
        assert connection.execute("SELECT COUNT(*) FROM drafts").fetchone()[0] == 1


@pytest.mark.parametrize(
    ("version_code", "version_name"),
    [(4, "0.4.2"), (5, "0.4.3"), (6, "0.4.4"), (7, "0.5.0"), (8, "0.5.1"), (9, "0.5.2")],
)
def test_mobile_release_authenticated_fixed_metadata_streaming_integrity(
    store, tmp_path, version_code, version_name
):
    directory = tmp_path / "mobile-release"
    directory.mkdir(mode=0o700)
    apk = b"synthetic APK bytes" * 1000
    (directory / "app.apk").write_bytes(apk)
    release = {
        "version_code": version_code,
        "version_name": version_name,
        "package_name": "com.momonong.pixelreceipt.mobile",
        "sha256": hashlib.sha256(apk).hexdigest(),
        "size_bytes": len(apk),
        "signer_sha256": "a" * 64,
    }
    (directory / "release.json").write_text(canonical(release))
    for path in directory.iterdir():
        path.chmod(0o400)
    app = create_app(store, TOKEN, root_path="/receipts", mobile_release_dir=directory)
    with TestClient(app) as client:
        assert client.get("/v1/mobile/release").status_code == 401
        assert client.get("/v1/mobile/apk").status_code == 401
        client.headers["Authorization"] = "Bearer " + TOKEN
        metadata = client.get("/v1/mobile/release").json()
        assert metadata == release | {"download_url": "/receipts/v1/mobile/apk"}
        response = client.get("/v1/mobile/apk")
        assert response.content == apk
        assert response.headers["content-type"] == "application/vnd.android.package-archive"
        assert "attachment" in response.headers["content-disposition"]
        assert (
            f'filename="PixelReceipt-{version_name}.apk"' in response.headers["content-disposition"]
        )
        assert client.get("/v1/mobile/apk/other").status_code == 404
    (directory / "app.apk").chmod(0o600)
    with pytest.raises(ValueError, match="immutable"):
        create_app(store, TOKEN, mobile_release_dir=directory)
    (directory / "app.apk").write_bytes(apk + b"tamper")
    (directory / "app.apk").chmod(0o400)
    with pytest.raises(ValueError, match="integrity"):
        create_app(store, TOKEN, mobile_release_dir=directory)


@pytest.mark.parametrize(
    ("version_code", "version_name", "package_name"),
    [
        (2, "0.4.0", "com.momonong.pixelreceipt.mobile"),
        (3, "0.4.1", "com.momonong.pixelreceipt.mobile"),
        (3, "0.4.2", "com.momonong.pixelreceipt.mobile"),
        (4, "0.4.1", "com.momonong.pixelreceipt.mobile"),
        (4, "0.4.3", "com.momonong.pixelreceipt.mobile"),
        (5, "0.4.2", "com.momonong.pixelreceipt.mobile"),
        (5, "0.4.4", "com.momonong.pixelreceipt.mobile"),
        (6, "0.4.3", "com.momonong.pixelreceipt.mobile"),
        (7, "0.4.5", "com.momonong.pixelreceipt.mobile"),
        (6, "0.5.0", "com.momonong.pixelreceipt.mobile"),
        (7, "0.5.1", "com.momonong.pixelreceipt.mobile"),
        (9, "0.5.1", "com.momonong.pixelreceipt.mobile"),
        (8, "0.5.2", "com.momonong.pixelreceipt.mobile"),
        (10, "0.5.2", "com.momonong.pixelreceipt.mobile"),
        (8, "0.5.0", "com.momonong.pixelreceipt.mobile"),
        (True, "0.4.2", "com.momonong.pixelreceipt.mobile"),
        ("4", "0.4.2", "com.momonong.pixelreceipt.mobile"),
        (4, "0.4.2", "com.momonong.pixelreceipt"),
        (4, "0.4.2", "com.unapproved.receipts"),
    ],
)
def test_mobile_release_rejects_legacy_mismatched_and_unapproved_identity(
    tmp_path, version_code, version_name, package_name
):
    from pixelreceipt_backend.mobile_release import MobileRelease

    directory = tmp_path / "mobile-version-admission"
    directory.mkdir(mode=0o700)
    apk = b"synthetic version admission APK"
    release = {
        "version_code": version_code,
        "version_name": version_name,
        "package_name": package_name,
        "sha256": hashlib.sha256(apk).hexdigest(),
        "size_bytes": len(apk),
        "signer_sha256": "a" * 64,
    }
    (directory / "app.apk").write_bytes(apk)
    (directory / "release.json").write_text(canonical(release))
    for path in directory.iterdir():
        path.chmod(0o400)
    with pytest.raises(ValueError, match="invalid mobile release metadata"):
        MobileRelease(directory)


def test_mobile_release_missing_is_explicit(client):
    assert client.get("/v1/mobile/release").status_code == 404
    assert client.get("/v1/mobile/apk").status_code == 404


def test_phone_sized_image_is_bounded_before_orientation_copies():
    from PIL import Image

    stream = io.BytesIO()
    with Image.new("RGB", (4000, 3000), "white") as image:
        image.save(stream, format="JPEG")
    original = stream.getvalue()
    derived, metadata = asyncio.run(derive_image(original, "image/jpeg"))
    assert (metadata["width"], metadata["height"]) == (1600, 1200)
    assert len(derived) <= 384 * 1024
    assert derived != original


def test_dashboard_login_preserves_valid_draft_deep_link_without_storage(store):
    import re

    app = create_app(
        store,
        TOKEN,
        reviewer_password_hash=password_hash(PASSWORD),
        public_origin="https://testserver",
        root_path="/receipts",
    )
    with TestClient(app, base_url="https://testserver") as client:
        page = client.get("/receipts/auth/login?draft=" + DEVICE)
        assert "?draft=" + DEVICE in page.text
        nonce = re.search(r'name="csrf" value="([^"]+)"', page.text)[1]
        response = client.post(
            "/receipts/auth/login?draft=" + DEVICE,
            data={"password": PASSWORD, "csrf": nonce},
            headers={"Origin": "https://testserver"},
            follow_redirects=False,
        )
        assert response.status_code == 303
        assert response.headers["location"] == "/receipts/#draft/" + DEVICE
        assert "password" not in response.headers["location"]
        invalid = client.get("/receipts/auth/login?draft=https://evil.test")
        assert "evil.test" not in invalid.text


def fenced_synthetic_text():
    from pathlib import Path

    return (Path(__file__).parent / "fixtures/fenced-synthetic-candidate.txt").read_text(
        encoding="utf-8"
    )


def test_real_synthetic_json_fence_strips_only_outer_wrapper():
    from pixelreceipt_backend.recognition import normalize_candidate_text, parse_candidate

    raw = fenced_synthetic_text()
    normalized, removed = normalize_candidate_text(" \r\n" + raw + "\t\n")
    assert removed is True
    assert normalized == raw.removeprefix("```json\n").removesuffix("\n```")
    assert normalize_candidate_text(normalized) == (normalized, False)
    candidate = parse_candidate(raw)
    assert candidate == parse_candidate(normalized)
    assert candidate["merchant"] == "SYNTHETIC TEST CAFE"
    assert candidate["printed_total_yuan"] == 110
    assert [
        (item["raw_name"], item["quantity"], item["printed_line_total_yuan"])
        for item in candidate["items"]
    ] == [("Tea", 2, 100), ("Cookie", 1, 30)]
    assert candidate["adjustments"][0]["amount_yuan"] == 20


@pytest.mark.parametrize(
    "wrapper",
    [
        lambda raw: "以下是結果：\n" + raw,
        lambda raw: raw + "\n辨識完成。",
        lambda raw: raw + "\n" + raw,
        lambda raw: raw.replace("```json", "```JSON", 1),
        lambda raw: raw.replace("```json", "```", 1),
        lambda raw: raw.removesuffix("\n```"),
    ],
)
def test_candidate_parser_rejects_prose_multiple_fences_and_non_json_fence(wrapper):
    from pixelreceipt_backend.recognition import normalize_candidate_text, parse_candidate

    malformed = wrapper(fenced_synthetic_text())
    assert normalize_candidate_text(malformed)[1] is False
    with pytest.raises(RecognitionError, match="invalid_model_candidate"):
        parse_candidate(malformed)


def test_candidate_json_fence_keeps_duplicate_key_and_money_validation():
    from pixelreceipt_backend.recognition import parse_candidate

    raw = fenced_synthetic_text()
    duplicate = raw.replace('"currency": "TWD",', '"currency": "TWD", "currency": "TWD",', 1)
    fractional = raw.replace('"printed_total_yuan": 110', '"printed_total_yuan": 110.5', 1)
    for invalid in (duplicate, fractional):
        with pytest.raises(RecognitionError, match="invalid_model_candidate"):
            parse_candidate(invalid)


@pytest.mark.parametrize("truncated", [False, True])
def test_fenced_result_preserves_raw_parser_provenance_and_completion_gate(store, body, truncated):
    from pixelreceipt_backend.recognition import CANDIDATE_PARSER_VERSION, parse_candidate

    journal = IntakeStore(store)
    record, _ = journal.create(intake(body), "fence-provenance")
    runner, fixture = worker(store, body, truncated=truncated)
    request = fixture.request
    text = fenced_synthetic_text()

    async def fenced(method, path, payload=None):
        value = await request(method, path, payload)
        if path.endswith("/result"):
            value["result"]["text"] = text
        return value

    fixture.request = fenced
    asyncio.run(runner.run_once())
    saved = journal.get(record["id"])
    assert saved["result"]["raw_outcome"]["result"]["text"] == text
    provenance = saved["result"]["candidate_parser"]
    assert provenance == {
        "version": CANDIDATE_PARSER_VERSION,
        "json_fence_removed": not truncated,
        "attempted": not truncated,
    }
    if truncated:
        assert saved["status"] == "failed" and saved["error_code"] == "model_output_incomplete"
        assert saved["draft_id"] is None
    else:
        assert saved["status"] == "succeeded"
        assert store.get_draft(saved["draft_id"])["candidate"] == parse_candidate(text)
    assert len([call for call in fixture.calls if call[1] == "/v1/queue"]) == 1
