import re
import time

import pytest
from fastapi.testclient import TestClient

from pixelreceipt_backend.api import create_app
from pixelreceipt_backend.auth import password_hash, verify_password

from .conftest import REVIEWER_TOKEN, TOKEN

PASSWORD = "synthetic-reviewer-password-only"
ORIGIN = "https://receipts.example.test"


def app_for(store, **kwargs):
    return create_app(
        store,
        TOKEN,
        reviewer_token=REVIEWER_TOKEN,
        reviewer_password_hash=password_hash(PASSWORD),
        **kwargs,
    )


def csrf_form(response):
    return re.search(r'name="csrf" value="([A-Za-z0-9_-]+)"', response.text)[1]


def login(client, *, origin=ORIGIN, password=PASSWORD):
    page = client.get("/auth/login")
    assert page.status_code == 200
    return client.post(
        "/auth/login",
        data={"csrf": csrf_form(page), "password": password},
        headers={"Origin": origin},
        follow_redirects=False,
    )


def test_login_server_form_secure_cookie_csrf_origin_logout_and_restart(store):
    app = app_for(store)
    with TestClient(app, base_url=ORIGIN) as client:
        assert client.get("/v1/session").status_code == 401
        response = login(client)
        assert response.status_code == 303
        cookie = response.headers.get_list("set-cookie")[0]
        assert "HttpOnly" in cookie and "Secure" in cookie and "SameSite=strict" in cookie
        assert "Path=/" in cookie
        session = client.get("/v1/session").json()
        assert session["role"] == "reviewer" and session["auth_method"] == "session"
        assert session["csrf_token"] and session["expires_at"]
        headers = {"Origin": ORIGIN, "X-CSRF-Token": session["csrf_token"]}
        assert client.post("/v1/drafts", json={}).status_code == 403
        assert client.post("/v1/drafts", json={}, headers={"Origin": ORIGIN}).status_code == 403
        assert (
            client.post(
                "/v1/drafts", json={}, headers={**headers, "Origin": "https://attacker.example"}
            ).status_code
            == 403
        )
        assert client.post("/v1/drafts", json={}, headers=headers).status_code == 422
        assert (
            client.post("/auth/logout", headers=headers, follow_redirects=False).status_code == 200
        )
        assert client.get("/v1/session").status_code == 401
        with store.connection() as connection:
            assert connection.execute("SELECT COUNT(*) FROM sessions").fetchone()[0] == 0


def test_login_csrf_invalid_password_missing_origin_and_attempt_limit(store):
    with TestClient(app_for(store), base_url=ORIGIN) as client:
        page = client.get("/auth/login")
        assert page.headers["referrer-policy"] == "same-origin"
        response = client.post(
            "/auth/login",
            data={"csrf": csrf_form(page), "password": PASSWORD},
            headers={"Origin": "null"},
        )
        assert response.status_code == 403
        page = client.get("/auth/login")
        assert (
            client.post(
                "/auth/login", data={"csrf": csrf_form(page), "password": PASSWORD}
            ).status_code
            == 403
        )
        assert (
            client.post(
                "/auth/login",
                data={"csrf": "wrong", "password": PASSWORD},
                headers={"Origin": ORIGIN},
            ).status_code
            == 403
        )
        # All three malformed attempts also consume the shared pre-body rate gate.
        for _ in range(2):
            response = login(client, password="wrong-password")
            assert response.status_code == 401
            assert PASSWORD not in response.text and "wrong-password" not in response.text
        assert login(client).status_code == 429
        assert client.get("/v1/session").status_code == 401


def test_prefix_cookie_path_public_origin_and_session_expiry(store):
    with TestClient(
        app_for(store, root_path="/receipts", public_origin=ORIGIN), base_url="http://internal.test"
    ) as client:
        page = client.get("/auth/login")
        assert 'action="/receipts/auth/login"' in page.text
        assert "Path=/receipts/" in page.headers["set-cookie"]
        # External HTTPS client reaches the same ASGI application through a prefix-stripping proxy.
    with TestClient(
        app_for(store, root_path="/receipts", public_origin=ORIGIN), base_url=ORIGIN
    ) as client:
        page = client.get("/receipts/auth/login")
        response = client.post(
            "/receipts/auth/login",
            data={"csrf": csrf_form(page), "password": PASSWORD},
            headers={"Origin": ORIGIN},
            follow_redirects=False,
        )
        assert response.status_code == 303 and response.headers["location"] == "/receipts/"
        assert client.get("/receipts/v1/session").status_code == 200
        with store.connection(write=True) as connection:
            connection.execute("UPDATE sessions SET expires_at = ?", (int(time.time()) - 1,))
        assert client.get("/receipts/v1/session").status_code == 401


def test_session_ids_are_only_stored_as_hashes_and_password_verifier_is_one_way(store):
    verifier = password_hash(PASSWORD)
    assert verify_password(PASSWORD, verifier)
    assert not verify_password("different", verifier)
    with TestClient(app_for(store), base_url=ORIGIN) as client:
        login(client)
        token = client.cookies.get("__Secure-pixelreceipt-session")
        with store.connection() as connection:
            row = dict(connection.execute("SELECT * FROM sessions").fetchone())
        assert token not in str(row)
        assert len(row["token_hash"]) == 64
        # Restarts keep unexpired session state, while restore tooling explicitly invalidates it.
        with TestClient(app_for(store), base_url=ORIGIN) as restarted:
            restarted.cookies.set("__Secure-pixelreceipt-session", token)
            assert restarted.get("/v1/session").status_code == 200


def test_ingest_only_default_never_gains_reviewer_and_login_disabled(store):
    with TestClient(create_app(store, TOKEN), base_url=ORIGIN) as client:
        assert client.get("/auth/login").status_code == 503
        client.headers["Authorization"] = "Bearer " + TOKEN
        assert client.get("/health").status_code == 200
        assert client.get("/v1/session").status_code == 403
        assert client.put("/v2/drafts/absent/review", json={}).status_code == 403
    with pytest.raises(ValueError, match="distinct"):
        create_app(store, TOKEN, reviewer_token=TOKEN)


def test_static_hook_is_allowlisted_and_never_exposes_private_files(store, tmp_path):
    static = tmp_path / "static"
    static.mkdir()
    (static / "index.html").write_text("<h1>SYNTHETIC dashboard</h1>")
    (static / "app.js").write_text("// synthetic")
    (static / "review.mjs").write_text("// synthetic review module")
    (static / "auth-token").write_text("must-never-be-served")
    (static / "app.css").symlink_to(static / "auth-token")
    with TestClient(app_for(store, static_dir=static)) as client:
        assert client.get("/").status_code == 200
        assert client.get("/app.js").status_code == 200
        assert client.get("/review.mjs").status_code == 200
        assert client.get("/app.css").status_code == 404
        assert client.get("/auth-token").status_code == 404
        assert client.get("/v1/drafts").status_code == 401
        assert "frame-ancestors 'none'" in client.get("/").headers["content-security-policy"]
