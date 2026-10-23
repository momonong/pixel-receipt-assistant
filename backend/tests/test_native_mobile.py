"""Native reviewer tokens stay separate from ingest tokens and browser cookies."""

import hashlib
import time
from datetime import datetime

import pytest
from fastapi.testclient import TestClient

from pixelreceipt_backend.api import create_app
from pixelreceipt_backend.auth import password_hash
from pixelreceipt_backend.intake_store import IntakeStore
from pixelreceipt_backend.store import Store

from .conftest import TOKEN
from .test_auth_sessions import csrf_form
from .test_ledger import review_body
from .test_mobile_ingest import DEVICE, PASSWORD, intake, login_body

ORIGIN = "https://testserver"


@pytest.fixture
def native_client(store):
    app = create_app(store, TOKEN, reviewer_password_hash=password_hash(PASSWORD))
    with TestClient(app, base_url=ORIGIN) as client:
        yield client


def login_native(client, **changes):
    response = client.post("/v2/mobile/login", json=login_body() | changes)
    assert response.status_code == 200, response.text
    return response.json()


def test_native_reviewer_can_review_and_confirm_after_restart(native_client, store, body):
    response = native_client.post("/v2/mobile/login", json=login_body())
    assert response.status_code == 200
    session = response.json()
    token = session["access_token"]
    assert token.startswith("prm_") and session["token_type"] == "Bearer"
    assert session["role"] == "reviewer" and session["device_id"] == DEVICE
    assert 29 * 86400 < datetime.fromisoformat(session["expires_at"]).timestamp() - time.time()
    assert datetime.fromisoformat(session["expires_at"]).timestamp() <= time.time() + 30 * 86400
    assert "set-cookie" not in response.headers
    with store.connection() as connection:
        row = dict(connection.execute("SELECT * FROM sessions").fetchone())
        assert connection.execute("SELECT COUNT(*) FROM device_sessions").fetchone()[0] == 0
    assert row["token_hash"] == hashlib.sha256(token.encode()).hexdigest()
    assert token not in str(row) and PASSWORD not in str(row)

    # A restarted process resolves the persisted session; no CSRF is required for Bearer.
    with TestClient(create_app(Store(store.data_dir), TOKEN), base_url=ORIGIN) as client:
        client.headers["Authorization"] = "Bearer " + token
        identity = client.get("/v1/session").json()
        assert identity["role"] == "reviewer"
        assert identity["auth_method"] == "mobile_session" and identity["csrf_token"] is None
        draft = client.post("/v1/drafts", json=body, headers={"Idempotency-Key": "native-draft"})
        assert draft.status_code == 201
        path = "/v2/drafts/" + draft.json()["id"]
        reviewed = client.put(path + "/review", json=review_body(body))
        assert reviewed.status_code == 200 and reviewed.json()["calculation"]["ready"]
        confirmed = client.post(
            path + "/confirm",
            json={"expected_revision": 2},
            headers={"Idempotency-Key": "native-confirm"},
        )
        assert confirmed.status_code == 201 and confirmed.json()["status"] == "active"
        assert (
            client.post(
                path + "/confirm",
                json={"expected_revision": 2},
                headers={"Idempotency-Key": "native-confirm"},
            ).json()
            == confirmed.json()
        )


def test_native_logout_revokes_only_current_session_and_keeps_legacy_device(native_client, store):
    legacy = native_client.post("/v1/mobile/login", json=login_body()).json()["access_token"]
    first = login_native(native_client)["access_token"]
    second = login_native(native_client)["access_token"]
    assert first != second
    native_client.headers["Authorization"] = "Bearer " + first
    assert native_client.post("/v2/mobile/logout").json() == {"status": "logged_out"}
    assert native_client.get("/health").status_code == 401
    assert store.get_session(first) is None
    native_client.headers["Authorization"] = "Bearer " + second
    assert native_client.get("/v1/session").status_code == 200
    native_client.headers["Authorization"] = "Bearer " + legacy
    assert native_client.get("/health").status_code == 200
    assert native_client.put("/v2/drafts/absent/review", json={}).status_code == 403
    assert native_client.post("/v2/mobile/logout").status_code == 200
    assert native_client.get("/health").status_code == 401
    native_client.headers["Authorization"] = "Bearer " + second
    assert native_client.get("/v1/session").status_code == 200


def test_native_session_expiry_cannot_fall_back_to_device(native_client, store):
    token = login_native(native_client)["access_token"]
    # Even a matching active device row cannot resurrect a native session.
    store.create_device_session(DEVICE, "synthetic collision", token)
    with store.connection(write=True) as connection:
        connection.execute("UPDATE sessions SET expires_at=?", (int(time.time()) - 1,))
    native_client.headers["Authorization"] = "Bearer " + token
    assert native_client.get("/health").status_code == 401
    assert native_client.post("/v2/mobile/logout").status_code == 401


@pytest.mark.parametrize(
    "marker", ["web-csrf-token", "mobile:bad-uuid", "mobile:AAAAAAAA-0000-4000-8000-000000000001"]
)
def test_native_prefix_requires_valid_mobile_marker(native_client, store, marker):
    token = "prm_synthetic-invalid-domain-token-000000000000"
    store.create_session(token, marker)
    store.create_device_session(DEVICE, "synthetic collision", token)
    native_client.headers["Authorization"] = "Bearer " + token
    assert native_client.get("/v1/session").status_code == 401
    assert native_client.get("/health").status_code == 401


def test_unprefixed_session_and_revoked_prefixed_device_cannot_gain_reviewer(native_client, store):
    unprefixed = "synthetic-cookie-session-token-000000000000"
    store.create_session(unprefixed, "mobile:" + DEVICE)
    native_client.headers["Authorization"] = "Bearer " + unprefixed
    assert native_client.get("/v1/session").status_code == 401
    prefixed = "prm_synthetic-revoked-device-token-000000000000"
    store.create_device_session(DEVICE, "synthetic revoked device", prefixed)
    store.revoke_device(DEVICE)
    native_client.headers["Authorization"] = "Bearer " + prefixed
    assert native_client.get("/health").status_code == 401
    native_client.headers["Authorization"] = "Bearer " + ("prm_synthetic-forged-token-" + "a" * 32)
    assert native_client.get("/health").status_code == 401


@pytest.mark.parametrize("prefixed", [True, False])
def test_mobile_session_cannot_be_replayed_as_browser_cookie(native_client, store, prefixed):
    if prefixed:
        token = login_native(native_client)["access_token"]
    else:
        token = "synthetic-unprefixed-mobile-cookie-000000000000"
        store.create_session(token, "mobile:" + DEVICE)
    native_client.cookies.set("__Secure-pixelreceipt-session", token)
    assert native_client.get("/v1/session").status_code == 401
    assert (
        native_client.post(
            "/v1/drafts", json={}, headers={"Origin": ORIGIN, "X-CSRF-Token": "mobile:" + DEVICE}
        ).status_code
        == 401
    )


def test_native_login_preserves_browser_cookie_csrf_and_native_logout_does_not_clear_it(
    native_client,
):
    native = login_native(native_client)["access_token"]
    form = native_client.get("/auth/login")
    response = native_client.post(
        "/auth/login",
        data={"password": PASSWORD, "csrf": csrf_form(form)},
        headers={"Origin": ORIGIN},
        follow_redirects=False,
    )
    assert response.status_code == 303
    session = native_client.get("/v1/session").json()
    assert session["auth_method"] == "session" and session["csrf_token"]
    assert native_client.post("/v1/drafts", json={}).status_code == 403
    assert (
        native_client.post(
            "/v1/drafts", json={}, headers={"Origin": ORIGIN, "X-CSRF-Token": session["csrf_token"]}
        ).status_code
        == 422
    )
    response = native_client.post(
        "/v2/mobile/logout", headers={"Authorization": "Bearer " + native}
    )
    assert response.status_code == 200 and "set-cookie" not in response.headers
    assert native_client.get("/v1/session").json()["auth_method"] == "session"


def test_native_login_wrong_password_origin_https_validation_and_shared_rate_gate(store):
    app = create_app(store, TOKEN, reviewer_password_hash=password_hash(PASSWORD))
    with TestClient(app, base_url=ORIGIN) as client:
        assert (
            client.post("/v2/mobile/login", json=login_body() | {"password": "wrong"}).status_code
            == 401
        )
        assert (
            client.post(
                "/v2/mobile/login", json=login_body(), headers={"Origin": "https://evil.test"}
            ).status_code
            == 403
        )
        assert (
            client.post("/v2/mobile/login", json=login_body() | {"extra": True}).status_code == 422
        )
        assert (
            client.post(
                "/v2/mobile/login",
                content=b"x" * 8193,
                headers={"Content-Type": "application/json"},
            ).status_code
            == 413
        )
        assert (
            client.post("/v1/mobile/login", json=login_body() | {"password": "wrong"}).status_code
            == 401
        )
        assert client.post("/v2/mobile/login", json=login_body()).status_code == 429
    with TestClient(
        create_app(store, TOKEN, reviewer_password_hash=password_hash(PASSWORD))
    ) as client:
        assert client.post("/v2/mobile/login", json=login_body()).status_code == 403
    with TestClient(create_app(store, TOKEN), base_url=ORIGIN) as client:
        assert client.post("/v2/mobile/login", json=login_body()).status_code == 503
    with store.connection() as connection:
        assert connection.execute("SELECT COUNT(*) FROM sessions").fetchone()[0] == 0


def assert_accounting(client, identifier, expected):
    detail = client.get("/v1/intakes/" + identifier).json()
    listing = client.get("/v1/intakes").json()["intakes"]
    summary = next(row for row in listing if row["id"] == identifier)
    for row in (detail, summary):
        assert {key: row[key] for key in expected} == expected
    return detail


def test_intake_accounting_projection_tracks_review_confirm_void_without_recognition_replay(
    client,
    store,
    body,
):
    journal = IntakeStore(store)
    initial, _ = journal.create(intake(body), "native-accounting")
    expected = {"draft_status": None, "ledger_id": None, "ledger_status": None}
    assert_accounting(client, initial["id"], expected)
    journal.complete(initial["id"], body["candidate"], {"provider": "synthetic"})
    succeeded = journal.get(initial["id"])
    draft_id = succeeded["draft_id"]
    expected["draft_status"] = "needs_review"
    assert_accounting(client, initial["id"], expected)
    assert (
        client.put("/v2/drafts/" + draft_id + "/review", json=review_body(body)).status_code == 200
    )
    confirmed = client.post(
        "/v2/drafts/" + draft_id + "/confirm",
        json={"expected_revision": 2},
        headers={"Idempotency-Key": "projected-confirm"},
    )
    assert confirmed.status_code == 201
    expected = {
        "draft_status": "confirmed",
        "ledger_id": confirmed.json()["id"],
        "ledger_status": "active",
    }
    assert_accounting(client, initial["id"], expected)
    assert (
        client.post(
            "/v1/ledger/" + expected["ledger_id"] + "/void",
            json={"expected_revision": 1, "reason": "synthetic correction"},
            headers={"Idempotency-Key": "projected-void"},
        ).status_code
        == 200
    )
    expected.update(draft_status="voided", ledger_status="voided")
    final = assert_accounting(client, initial["id"], expected)
    assert final["status"] == "succeeded"
    for key in ("input", "evidence_snapshot", "job_key", "draft_id", "result", "created_at"):
        assert final[key] == succeeded[key]
    assert len(store.list_ledger(100, 0)) == 1
    with store.connection() as connection:
        assert connection.execute("PRAGMA user_version").fetchone()[0] == 4
    reopened = Store(store.data_dir)
    assert IntakeStore(reopened).get(initial["id"])["ledger_status"] == "voided"


@pytest.mark.parametrize("purpose", ["purchase", "reference"])
def test_historical_succeeded_intake_without_draft_has_nullable_accounting(
    client, store, body, purpose
):
    journal = IntakeStore(store)
    record, _ = journal.create(intake(body, purpose=purpose), "historical-without-draft")
    if purpose == "reference":
        journal.complete(record["id"], body["candidate"], {})
    else:
        journal.update(record["id"], status="succeeded")
    value = assert_accounting(
        client,
        record["id"],
        {
            "draft_status": None,
            "ledger_id": None,
            "ledger_status": None,
        },
    )
    assert value["status"] == "succeeded" and value["draft_id"] is None


def test_browser_login_avoids_reserved_native_prefix(native_client, monkeypatch):
    from pixelreceipt_backend import auth

    original = auth.secrets.token_urlsafe
    reserved_issued = False

    def token_bytes(size):
        nonlocal reserved_issued
        if size == 48 and not reserved_issued:
            reserved_issued = True
            return "prm_" + "a" * 60
        return original(size)

    monkeypatch.setattr(auth.secrets, "token_urlsafe", token_bytes)
    form = native_client.get("/auth/login")
    response = native_client.post(
        "/auth/login",
        data={"password": PASSWORD, "csrf": csrf_form(form)},
        headers={"Origin": ORIGIN},
        follow_redirects=False,
    )
    assert response.status_code == 303
    assert not native_client.cookies.get("__Secure-pixelreceipt-session").startswith("prm_")
    assert native_client.get("/v1/session").status_code == 200


def test_native_only_device_is_listed_and_whole_device_revoke_invalidates_all_sessions(
    native_client,
    store,
):
    first = login_native(native_client)["access_token"]
    second = login_native(native_client)["access_token"]
    native_client.headers["Authorization"] = "Bearer " + first
    devices = native_client.get("/v1/devices").json()["devices"]
    assert len(devices) == 1
    assert devices[0]["device_id"] == DEVICE
    assert devices[0]["device_name"] == "手機 App"
    assert devices[0]["revoked_at"] is None
    assert devices[0]["created_at"] <= time.time() < devices[0]["expires_at"]
    assert native_client.delete("/v1/devices/" + DEVICE).json() == {"status": "revoked"}
    for token in (first, second):
        native_client.headers["Authorization"] = "Bearer " + token
        assert native_client.get("/health").status_code == 401
        assert store.get_session(token) is None


def test_device_revoke_covers_legacy_and_native_sessions_but_preserves_other_device_and_browser(
    native_client,
    store,
):
    other_id = "00000000-0000-4000-8000-000000000002"
    first = login_native(native_client)["access_token"]
    second = login_native(native_client)["access_token"]
    other = login_native(native_client, device_id=other_id)["access_token"]
    legacy = native_client.post("/v1/mobile/login", json=login_body()).json()["access_token"]
    expired = "prm_synthetic-expired-native-token-000000000000"
    store.create_session(expired, "mobile:" + DEVICE, lifetime=-1)
    form = native_client.get("/auth/login")
    assert (
        native_client.post(
            "/auth/login",
            data={"password": PASSWORD, "csrf": csrf_form(form)},
            headers={"Origin": ORIGIN},
            follow_redirects=False,
        ).status_code
        == 303
    )
    browser = native_client.get("/v1/session").json()
    devices = native_client.get("/v1/devices").json()["devices"]
    assert len(devices) == 2
    mixed = next(row for row in devices if row["device_id"] == DEVICE)
    assert mixed["device_name"] == login_body()["device_name"]
    assert mixed["revoked_at"] is None
    assert (
        native_client.delete(
            "/v1/devices/" + DEVICE,
            headers={"Origin": ORIGIN, "X-CSRF-Token": browser["csrf_token"]},
        ).status_code
        == 200
    )
    assert native_client.get("/v1/session").json()["auth_method"] == "session"
    for token in (first, second, legacy):
        assert (
            native_client.get("/health", headers={"Authorization": "Bearer " + token}).status_code
            == 401
        )
    assert (
        native_client.get("/v1/session", headers={"Authorization": "Bearer " + other}).status_code
        == 200
    )
    with store.connection() as connection:
        assert (
            connection.execute(
                "SELECT COUNT(*) FROM sessions WHERE csrf_token=?",
                ("mobile:" + DEVICE,),
            ).fetchone()[0]
            == 0
        )
        assert (
            connection.execute(
                "SELECT COUNT(*) FROM sessions WHERE csrf_token=?",
                ("mobile:" + other_id,),
            ).fetchone()[0]
            == 1
        )
    remaining = native_client.get("/v1/devices").json()["devices"]
    assert next(row for row in remaining if row["device_id"] == DEVICE)["revoked_at"] is not None
    assert next(row for row in remaining if row["device_id"] == other_id)["revoked_at"] is None


def test_device_list_ignores_expired_or_malformed_native_markers_and_keeps_legacy_state(
    client,
    store,
):
    now = int(time.time())
    legacy = "synthetic-legacy-device-token-000000000000"
    store.create_device_session(DEVICE, "既有裝置", legacy)
    store.revoke_device(DEVICE)
    with store.connection() as connection:
        previous = dict(
            connection.execute(
                """SELECT device_id,device_name,created_at,expires_at,revoked_at
                   FROM device_sessions""",
            ).fetchone()
        )
    for token, marker, lifetime in (
        ("prm_synthetic-expired-device-token-000000000000", "mobile:" + DEVICE, -1),
        ("prm_synthetic-malformed-device-token-000000000", "mobile:bad-uuid", 100),
        (
            "prm_synthetic-noncanonical-device-token-000000",
            "mobile:AAAAAAAA-0000-4000-8000-000000000001",
            100,
        ),
        ("synthetic-browser-session-token-0000000000000", "ordinary-csrf", 100),
    ):
        store.create_session(token, marker, lifetime=lifetime)
    assert client.get("/v1/devices").json()["devices"] == [previous]
    native = "prm_synthetic-active-device-token-000000000000"
    store.create_session(native, "mobile:" + DEVICE, lifetime=3600)
    devices = client.get("/v1/devices").json()["devices"]
    assert len(devices) == 1
    assert devices[0]["device_name"] == "既有裝置"
    assert devices[0]["revoked_at"] is None
    # An already-revoked legacy row's longer expiry cannot describe current access.
    assert now + 3599 <= devices[0]["expires_at"] <= int(time.time()) + 3600
    assert client.delete("/v1/devices/" + DEVICE).status_code == 200
    assert client.delete("/v1/devices/unknown-device").status_code == 200
