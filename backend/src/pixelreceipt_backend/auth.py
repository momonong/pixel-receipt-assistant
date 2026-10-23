"""Private verifier and opaque, revocable reviewer sessions. No token enters frontend JS."""

import asyncio
import hashlib
import hmac
import re
import secrets
import time
from dataclasses import dataclass
from datetime import UTC, datetime
from html import escape
from urllib.parse import parse_qs, urlencode, urlsplit

from fastapi import HTTPException, Request
from fastapi.responses import HTMLResponse, JSONResponse, RedirectResponse

from .intake_models import device_uuid
from .store import Store

MOBILE_SESSION_PREFIX = "prm_"
MOBILE_SESSION_MARKER = "mobile:"


def validate_token(token: str):
    if not 32 <= len(token) <= 512 or not token.isascii() or any(c.isspace() for c in token):
        raise ValueError("token must contain 32..512 non-whitespace ASCII characters")


def password_hash(password: str) -> str:
    if not 12 <= len(password) <= 1024:
        raise ValueError("reviewer password must contain 12..1024 characters")
    salt = secrets.token_bytes(16)
    digest = hashlib.scrypt(password.encode(), salt=salt, n=16384, r=8, p=1, dklen=32)
    return "scrypt-v1$" + salt.hex() + "$" + digest.hex()


def validate_password_hash(value: str):
    parts = value.split("$")
    if len(parts) != 3 or parts[0] != "scrypt-v1":
        raise ValueError("invalid reviewer password verifier")
    try:
        if len(bytes.fromhex(parts[1])) != 16 or len(bytes.fromhex(parts[2])) != 32:
            raise ValueError
    except ValueError as error:
        raise ValueError("invalid reviewer password verifier") from error


def verify_password(password: str, verifier: str) -> bool:
    if not 1 <= len(password) <= 1024:
        return False
    _, salt, expected = verifier.split("$")
    digest = hashlib.scrypt(
        password.encode(), salt=bytes.fromhex(salt), n=16384, r=8, p=1, dklen=32
    )
    return hmac.compare_digest(digest.hex(), expected)


@dataclass(frozen=True)
class Principal:
    role: str
    auth_method: str
    csrf_token: str | None = None
    expires_at: int | None = None
    device_id: str | None = None


class Auth:
    def __init__(
        self,
        store: Store,
        token: str,
        *,
        reviewer_token=None,
        reviewer_password_hash=None,
        cookie_secure=True,
        root_path="",
        public_origin=None,
    ):
        validate_token(token)
        if reviewer_token:
            validate_token(reviewer_token)
            if hmac.compare_digest(token, reviewer_token):
                raise ValueError("reviewer token must be distinct from ingestion token")
        if reviewer_password_hash:
            validate_password_hash(reviewer_password_hash)
        self.store = store
        self.token = token
        self.reviewer_token = reviewer_token
        self.verifier = reviewer_password_hash
        self.secure = cookie_secure
        self.cookie_path = root_path.rstrip("/") + "/"
        self.cookie_name = (
            "__Secure-pixelreceipt-session" if cookie_secure else "pixelreceipt-session"
        )
        self.login_cookie = "__Secure-pixelreceipt-login" if cookie_secure else "pixelreceipt-login"
        self.public_origin = public_origin.rstrip("/") if public_origin else None
        self.login_attempts: dict[str, list[float]] = {}

    def check_origin(self, request: Request):
        origin = request.headers.get("origin")
        url = urlsplit(str(request.url))
        expected = self.public_origin or f"{url.scheme}://{url.netloc}"
        if origin != expected:
            raise HTTPException(403, "origin_check_failed")
        if request.headers.get("sec-fetch-site") == "cross-site":
            raise HTTPException(403, "origin_check_failed")

    def authenticate(self, request: Request) -> Principal:
        authorization = request.headers.get("authorization")
        if authorization is not None:
            if authorization.isascii() and authorization.startswith("Bearer "):
                bearer = authorization.removeprefix("Bearer ")
                if 32 <= len(bearer) <= 512:
                    if bearer.startswith(MOBILE_SESSION_PREFIX):
                        # Native tokens never fall back to the legacy device domain.
                        principal = self._mobile_session(bearer)
                        if principal:
                            return principal
                    else:
                        if self.reviewer_token and hmac.compare_digest(bearer, self.reviewer_token):
                            return Principal("reviewer", "bearer")
                        if hmac.compare_digest(bearer, self.token):
                            return Principal("ingest", "bearer")
                        device = self.store.get_device_session(bearer)
                        if device:
                            return Principal(
                                "ingest",
                                "device",
                                expires_at=device["expires_at"],
                                device_id=device["device_id"],
                            )
            raise HTTPException(
                401, "authentication_required", headers={"WWW-Authenticate": "Bearer"}
            )
        token = request.cookies.get(self.cookie_name, "")
        if len(token) <= 256 and token.isascii() and not token.startswith(MOBILE_SESSION_PREFIX):
            session = self.store.get_session(token) if token else None
            if session and not session["csrf_token"].startswith(MOBILE_SESSION_MARKER):
                if request.method not in {"GET", "HEAD", "OPTIONS"}:
                    self.check_origin(request)
                    csrf = request.headers.get("x-csrf-token", "")
                    if not csrf.isascii() or not hmac.compare_digest(csrf, session["csrf_token"]):
                        raise HTTPException(403, "csrf_check_failed")
                return Principal(
                    "reviewer", "session", session["csrf_token"], session["expires_at"]
                )
        raise HTTPException(401, "authentication_required", headers={"WWW-Authenticate": "Bearer"})

    def _mobile_session(self, token: str) -> Principal | None:
        session = self.store.get_session(token)
        device_id = self._mobile_device_id(session["csrf_token"]) if session else None
        if device_id is None:
            return None
        return Principal(
            "reviewer", "mobile_session", expires_at=session["expires_at"], device_id=device_id
        )

    @staticmethod
    def _mobile_device_id(marker: str) -> str | None:
        if not marker.startswith(MOBILE_SESSION_MARKER):
            return None
        # Reuse the hashed session store without interpreting this internal marker
        # as a browser CSRF secret or persisting the bearer/password in plaintext.
        try:
            return device_uuid(marker.removeprefix(MOBILE_SESSION_MARKER))
        except ValueError:
            return None

    def list_devices(self) -> list[dict]:
        now = int(time.time())
        with self.store.connection() as connection:
            devices = {
                row["device_id"]: dict(row)
                for row in connection.execute(
                    """SELECT device_id,device_name,created_at,expires_at,revoked_at
                       FROM device_sessions"""
                )
            }
            native = connection.execute(
                """SELECT csrf_token,MIN(created_at) AS created_at,MAX(expires_at) AS expires_at
                   FROM sessions WHERE expires_at>? AND csrf_token LIKE ? GROUP BY csrf_token""",
                (now, MOBILE_SESSION_MARKER + "%"),
            )
            for row in native:
                device_id = self._mobile_device_id(row["csrf_token"])
                if device_id is None:
                    continue
                device = devices.get(device_id)
                if device is None:
                    devices[device_id] = {
                        "device_id": device_id,
                        "device_name": "手機 App",
                        "created_at": row["created_at"],
                        "expires_at": row["expires_at"],
                        "revoked_at": None,
                    }
                else:
                    legacy_active = device["revoked_at"] is None and device["expires_at"] > now
                    device["created_at"] = min(device["created_at"], row["created_at"])
                    device["expires_at"] = (
                        max(device["expires_at"], row["expires_at"])
                        if legacy_active
                        else row["expires_at"]
                    )
                    device["revoked_at"] = None
        return sorted(devices.values(), key=lambda row: (-row["created_at"], row["device_id"]))

    def revoke_device(self, device_id: str):
        marker = MOBILE_SESSION_MARKER + device_id
        with self.store.connection(write=True) as connection:
            # Device revocation covers every credential in one transaction. A
            # native logout deliberately remains a single-session deletion.
            connection.execute(
                "UPDATE device_sessions SET revoked_at=? WHERE device_id=?",
                (int(time.time()), device_id),
            )
            if self._mobile_device_id(marker) is not None:
                connection.execute("DELETE FROM sessions WHERE csrf_token=?", (marker,))

    def reviewer(self, request: Request) -> Principal:
        principal = self.authenticate(request)
        if principal.role != "reviewer":
            raise HTTPException(403, "reviewer_required")
        return principal

    def session_response(self, request: Request):
        principal = self.reviewer(request)
        return {
            "role": principal.role,
            "auth_method": principal.auth_method,
            "csrf_token": principal.csrf_token,
            "expires_at": datetime.fromtimestamp(principal.expires_at, UTC).isoformat()
            if principal.expires_at
            else None,
        }

    @staticmethod
    def draft_return(value):
        return value if isinstance(value, str) and re.fullmatch(r"[a-f0-9-]{36}", value) else None

    @staticmethod
    def return_route(value):
        if not isinstance(value, str):
            return None
        simple = r"#(?:overview|review|ledger|prices|intakes)"
        identifier = r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
        detail = rf"#(?:draft|ledger)/{identifier}"
        return value if re.fullmatch(rf"(?:{simple}|{detail})", value) else None

    def login_form(self, *, failed=False, status=200, next_draft=None, return_to=None):
        if self.verifier is None:
            raise HTTPException(503, "reviewer_login_unavailable")
        nonce = secrets.token_urlsafe(32)
        target = self.draft_return(next_draft)
        route = self.return_route(return_to)
        query = urlencode({"return_to": route}) if route else ("draft=" + target if target else "")
        action = self.cookie_path + "auth/login" + ("?" + query if query else "")
        action = escape(action, quote=True)
        error = "<p class='auth-error' role='alert'>登入未完成，請重新輸入。</p>" if failed else ""
        html = f"""<!doctype html><html lang="zh-Hant"><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Pixel Receipt 登入</title>
<link rel="stylesheet" href="{escape(self.cookie_path + "app.css", quote=True)}">
<body class="auth-page"><main class="auth-card"><h1>Pixel Receipt</h1>
<p class="auth-description">登入以核對收據與確認記帳。</p>{error}
<form action="{action}" method="post"><input type="hidden" name="csrf" value="{nonce}">
<label class="auth-field">核對密碼
<input name="password" type="password" autocomplete="current-password"
 required maxlength="1024"></label><button class="button full" type="submit">登入</button>
</form><p class="auth-note">請使用人工核對專用密碼。</p></main></body></html>"""
        response = HTMLResponse(html, status_code=status)
        response.set_cookie(
            self.login_cookie,
            nonce,
            max_age=600,
            path=self.cookie_path,
            httponly=True,
            secure=self.secure,
            samesite="strict",
        )
        return response

    def login_gate(self, request):
        now = time.monotonic()
        client = request.client.host if request.client else "unknown"
        self.login_attempts = {
            key: [t for t in times if now - t < 60]
            for key, times in self.login_attempts.items()
            if times and now - times[-1] < 60
        }
        attempts = self.login_attempts.setdefault(client, [])
        if len(attempts) >= 5 or sum(map(len, self.login_attempts.values())) >= 30:
            return False
        attempts.append(now)
        return True

    async def mobile_login(self, request, parse, *, reviewer_session=False):
        from .intake_models import MobileLogin

        if self.verifier is None:
            raise HTTPException(503, "reviewer_login_unavailable")
        if not self.login_gate(request):
            raise HTTPException(429, "login_rate_limited")
        if self.secure and not (
            self.public_origin
            and urlsplit(self.public_origin).scheme == "https"
            or request.url.scheme == "https"
        ):
            raise HTTPException(403, "https_required")
        if request.headers.get("origin") is not None:
            self.check_origin(request)
        try:
            async with asyncio.timeout(10):
                body = await parse(request, MobileLogin, limit=8192)
        except TimeoutError as error:
            raise HTTPException(408, "login_timeout") from error
        if not await asyncio.to_thread(verify_password, body.password, self.verifier):
            raise HTTPException(401, "authentication_required")
        if reviewer_session:
            token = MOBILE_SESSION_PREFIX + secrets.token_urlsafe(48)
            expires = self.store.create_session(
                token, MOBILE_SESSION_MARKER + body.device_id, lifetime=30 * 86400
            )
        else:
            token = secrets.token_urlsafe(48)
            # Reserve the native domain even for the exceedingly rare random collision.
            while token.startswith(MOBILE_SESSION_PREFIX):
                token = secrets.token_urlsafe(48)
            expires = self.store.create_device_session(body.device_id, body.device_name, token)
        return {
            "token_type": "Bearer",
            "access_token": token,
            "expires_at": datetime.fromtimestamp(expires, UTC).isoformat(),
            "device_id": body.device_id,
            "role": "reviewer" if reviewer_session else "ingest",
        }

    def mobile_logout(self, request):
        principal = self.authenticate(request)
        if principal.auth_method == "mobile_session":
            self.store.delete_session(request.headers["authorization"].removeprefix("Bearer "))
        elif principal.auth_method == "device":
            self.store.revoke_device(principal.device_id)
        else:
            raise HTTPException(403, "device_session_required")
        return {"status": "logged_out"}

    async def login(self, request: Request, bounded_body):
        next_draft = self.draft_return(request.query_params.get("draft"))
        return_to = self.return_route(request.query_params.get("return_to"))
        if self.verifier is None:
            raise HTTPException(503, "reviewer_login_unavailable")
        if not self.login_gate(request):
            return self.login_form(
                failed=True, status=429, next_draft=next_draft, return_to=return_to
            )
        try:
            self.check_origin(request)
        except HTTPException:
            return self.login_form(
                failed=True, status=403, next_draft=next_draft, return_to=return_to
            )
        if (
            request.headers.get("content-type", "").split(";")[0]
            != "application/x-www-form-urlencoded"
        ):
            return self.login_form(
                failed=True, status=400, next_draft=next_draft, return_to=return_to
            )
        try:
            body = await bounded_body(request, 8192)
            fields = parse_qs(body.decode("utf-8"), strict_parsing=True, max_num_fields=2)
            if set(fields) != {"password", "csrf"} or any(len(v) != 1 for v in fields.values()):
                raise ValueError
            csrf = fields["csrf"][0]
            nonce = request.cookies.get(self.login_cookie, "")
            if (
                not csrf
                or not csrf.isascii()
                or not nonce.isascii()
                or not hmac.compare_digest(csrf, nonce)
            ):
                raise ValueError
        except (ValueError, UnicodeError):
            return self.login_form(
                failed=True, status=403, next_draft=next_draft, return_to=return_to
            )
        if not await asyncio.to_thread(verify_password, fields["password"][0], self.verifier):
            return self.login_form(
                failed=True, status=401, next_draft=next_draft, return_to=return_to
            )
        old = request.cookies.get(self.cookie_name)
        if old:
            self.store.delete_session(old)
        token = secrets.token_urlsafe(48)
        while token.startswith(MOBILE_SESSION_PREFIX):
            token = secrets.token_urlsafe(48)
        self.store.create_session(token, secrets.token_urlsafe(32))
        response = RedirectResponse(
            self.cookie_path + (return_to or ("#draft/" + next_draft if next_draft else "")),
            status_code=303,
        )
        response.set_cookie(
            self.cookie_name,
            token,
            max_age=8 * 3600,
            path=self.cookie_path,
            httponly=True,
            secure=self.secure,
            samesite="strict",
        )
        response.delete_cookie(
            self.login_cookie,
            path=self.cookie_path,
            secure=self.secure,
            httponly=True,
            samesite="strict",
        )
        return response

    def logout(self, request: Request):
        principal = self.reviewer(request)
        if principal.auth_method == "session":
            self.store.delete_session(request.cookies[self.cookie_name])
        response = JSONResponse({"status": "logged_out"})
        response.delete_cookie(
            self.cookie_name,
            path=self.cookie_path,
            secure=self.secure,
            httponly=True,
            samesite="strict",
        )
        return response
