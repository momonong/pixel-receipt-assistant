#!/usr/bin/env python3
"""Small ingest-only HTTP client. Python 3.12+, standard library, POSIX state locks."""

import argparse
import contextlib
import fcntl
import hashlib
import http.client
import ipaddress
import json
import os
import re
import ssl
import stat
import sys
import tempfile
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path

MAX_UPLOAD = 20 * 1024 * 1024
MAX_JSON = 1024 * 1024
MAX_RESPONSE = 8 * 1024 * 1024
MEDIA = {
    ".jpg": "image/jpeg",
    ".jpeg": "image/jpeg",
    ".png": "image/png",
    ".webp": "image/webp",
    ".pdf": "application/pdf",
}
KEY = re.compile(r"[A-Za-z0-9][A-Za-z0-9._:-]{0,127}\Z")


class ClientError(Exception):
    def __init__(self, code, exit_code=2, **details):
        self.code, self.exit_code, self.details = code, exit_code, details
        super().__init__(code)


def base_url(value):
    """Never allow credentials in URLs, ambiguous prefixes, or cleartext LAN traffic."""
    if not isinstance(value, str) or not value:
        raise ClientError("api_url_required")
    try:
        parsed = urllib.parse.urlsplit(value)
        valid = (
            parsed.scheme in {"https", "http"}
            and parsed.hostname
            and parsed.username is None
            and parsed.password is None
            and not parsed.query
            and not parsed.fragment
            and re.fullmatch(r"(?:/[A-Za-z0-9_-]+)*/?", parsed.path)
            and not any(ord(char) <= 32 or ord(char) >= 127 for char in value)
        )
        if not valid or (parsed.port is not None and not 1 <= parsed.port <= 65535):
            raise ValueError
        if (
            parsed.scheme == "http"
            and not ipaddress.ip_address(parsed.hostname).is_loopback
        ):
            raise ValueError
    except ValueError:
        raise ClientError("https_required_except_literal_loopback") from None
    return value.rstrip("/")


def canonical(value):
    return json.dumps(
        value, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode()


def read_json(content):
    def pairs(entries):
        value = {}
        for key, child in entries:
            if key in value:
                raise ValueError
            value[key] = child
        return value

    def reject(_):
        raise ValueError

    try:
        return json.loads(
            content, object_pairs_hook=pairs, parse_float=reject, parse_constant=reject
        )
    except (ValueError, UnicodeError, RecursionError):
        raise ClientError("invalid_integer_json") from None


def read_file(path, limit, *, private=False):
    try:
        descriptor = os.open(
            path, os.O_RDONLY | os.O_NONBLOCK | getattr(os, "O_NOFOLLOW", 0)
        )
        with os.fdopen(descriptor, "rb") as stream:
            metadata = os.fstat(stream.fileno())
            if not stat.S_ISREG(metadata.st_mode):
                raise ClientError("regular_file_required")
            if private and (
                stat.S_IMODE(metadata.st_mode) != 0o600
                or metadata.st_uid != os.geteuid()
            ):
                raise ClientError("private_owned_600_file_required")
            content = stream.read(limit + 1)
    except OSError:
        raise ClientError("cannot_read_input_file") from None
    if len(content) > limit:
        raise ClientError("input_too_large")
    return content


def token_from(args):
    configured = os.environ.get("PIXELRECEIPT_INGEST_TOKEN")
    path = args.token_file or os.environ.get("PIXELRECEIPT_INGEST_TOKEN_FILE")
    if configured and path:
        raise ClientError("choose_token_environment_or_file")
    try:
        token = (
            configured
            if configured
            else read_file(path, 514, private=True).decode("ascii").strip()
            if path
            else ""
        )
    except UnicodeError:
        raise ClientError("invalid_ingest_token") from None
    if (
        not 32 <= len(token) <= 512
        or not token.isascii()
        or any(c.isspace() for c in token)
    ):
        raise ClientError("missing_or_invalid_ingest_token")
    return token


def private_parent(path):
    path = path.expanduser().absolute()
    # State paths must not traverse symlinks. Never chmod an existing directory.
    if path.parent.resolve() != path.parent:
        raise ClientError("state_symlink_path_refused")
    try:
        path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        metadata = path.parent.stat()
        if stat.S_IMODE(metadata.st_mode) != 0o700 or metadata.st_uid != os.geteuid():
            raise ClientError("state_parent_must_be_owned_700_directory")
        if path.is_symlink():
            raise ClientError("state_symlink_path_refused")
    except OSError:
        raise ClientError("cannot_prepare_private_state") from None
    return path


def save_state(path, value):
    """Fsync the request key before sending anything; a crash never creates a new key."""
    descriptor, temporary = tempfile.mkstemp(prefix=".receipt-state-", dir=path.parent)
    try:
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(canonical(value) + b"\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
        directory = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
        try:
            os.fsync(directory)
        finally:
            os.close(directory)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


@contextlib.contextmanager
def request_state(path, api_url, body):
    path = private_parent(path)
    try:
        descriptor = os.open(
            str(path) + ".lock",
            os.O_RDWR | os.O_CREAT | os.O_NONBLOCK | getattr(os, "O_NOFOLLOW", 0),
            0o600,
        )
        with os.fdopen(descriptor, "rb") as lock:
            metadata = os.fstat(lock.fileno())
            if (
                not stat.S_ISREG(metadata.st_mode)
                or stat.S_IMODE(metadata.st_mode) != 0o600
                or metadata.st_uid != os.geteuid()
            ):
                raise ClientError("private_owned_600_file_required")
            try:
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError:
                raise ClientError("request_already_in_progress") from None
            digest = hashlib.sha256(body).hexdigest()
            if path.exists():
                state = read_json(read_file(path, 8192, private=True))
                if (
                    not isinstance(state, dict)
                    or state.get("version") != 1
                    or state.get("base_url") != api_url
                    or state.get("body_sha256") != digest
                    or not isinstance(state.get("idempotency_key"), str)
                    or not KEY.fullmatch(state["idempotency_key"])
                    or type(state.get("attempts")) is not int
                    or state["attempts"] < 0
                ):
                    raise ClientError("request_state_mismatch_do_not_generate_new_key")
            else:
                state = {
                    "version": 1,
                    "base_url": api_url,
                    "body_sha256": digest,
                    "idempotency_key": str(uuid.uuid4()),
                    "attempts": 0,
                    "status": "prepared",
                }
                save_state(path, state)
            yield path, state
    except OSError:
        raise ClientError("cannot_lock_or_persist_request_state") from None


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, msg, headers, newurl):
        return None


def redact(value, token):
    # Even a misconfigured upstream must not echo the bearer token into output.
    if isinstance(value, str):
        return value.replace(token, "[REDACTED]")
    if isinstance(value, list):
        return [redact(child, token) for child in value]
    if isinstance(value, dict):
        return {
            redact(key, token): redact(child, token) for key, child in value.items()
        }
    return value


def request(args, token, method, path, body=None, media_type=None, key=None):
    headers = {
        "Authorization": "Bearer " + token,
        "Accept": "application/json",
        "User-Agent": "PixelReceipt-Ingest/1.0",
    }
    if media_type:
        headers["Content-Type"] = media_type
    if key:
        headers["Idempotency-Key"] = key
    query = urllib.request.Request(
        args.base_url + path, data=body, method=method, headers=headers
    )
    # No ambient proxies, redirects, cookies, access logs, or implicit retries.
    opener = urllib.request.build_opener(
        urllib.request.ProxyHandler({}),
        NoRedirect(),
        urllib.request.HTTPSHandler(context=ssl.create_default_context()),
    )
    mutation = method != "GET"
    try:
        with opener.open(query, timeout=args.timeout) as response:
            content = response.read(MAX_RESPONSE + 1)
            if (
                len(content) > MAX_RESPONSE
                or response.headers.get_content_type() != "application/json"
            ):
                raise ClientError(
                    "invalid_response_outcome_unknown"
                    if mutation
                    else "invalid_response",
                    4 if mutation else 5,
                )
            try:
                value = read_json(content)
            except ClientError:
                raise ClientError(
                    "invalid_response_outcome_unknown"
                    if mutation
                    else "invalid_response",
                    4 if mutation else 5,
                ) from None
            return {"http_status": response.status, "result": redact(value, token)}
    except urllib.error.HTTPError as error:
        status = error.code
        error.close()
        # Upstream errors may contain receipt text, credentials or a sensitive Location.
        if mutation and (status == 408 or status >= 500):
            raise ClientError(
                "server_error_outcome_unknown_reuse_same_request", 4, http_status=status
            ) from None
        code = "redirect_refused" if 300 <= status < 400 else "http_error"
        raise ClientError(code, 3, http_status=status) from None
    except (
        urllib.error.URLError,
        TimeoutError,
        ConnectionError,
        OSError,
        http.client.HTTPException,
    ):
        raise ClientError(
            "outcome_unknown_reuse_same_request" if mutation else "transport_error",
            4 if mutation else 5,
        ) from None


def run(args):
    args.base_url = base_url(args.base_url)
    if not 0.05 <= args.timeout <= 300:
        raise ClientError("timeout_must_be_0_05_to_300_seconds")
    token = token_from(args)
    if args.command == "health":
        return request(args, token, "GET", "/health")
    if args.command == "upload":
        content = read_file(args.file, MAX_UPLOAD)
        media_type = args.media_type or MEDIA.get(args.file.suffix.lower())
        if media_type not in MEDIA.values():
            raise ClientError("supported_media_type_required")
        digest = hashlib.sha256(content).hexdigest()
        try:
            result = request(args, token, "POST", "/v1/evidence", content, media_type)
        except ClientError as error:
            if error.exit_code == 4:
                error.details["evidence_sha256"] = digest
            raise
        metadata = result["result"]
        if (
            not isinstance(metadata, dict)
            or metadata.get("sha256") != digest
            or metadata.get("size_bytes") != len(content)
        ):
            raise ClientError(
                "upload_response_mismatch_outcome_unknown", 4, evidence_sha256=digest
            )
        return result
    if args.command == "create-draft":
        value = read_json(read_file(args.body, MAX_JSON))
        if not isinstance(value, dict):
            raise ClientError("draft_body_must_be_object")
        body = canonical(value)
        with request_state(args.request_state, args.base_url, body) as (path, state):
            state.update(status="in_flight", attempts=state["attempts"] + 1)
            save_state(path, state)
            try:
                result = request(
                    args,
                    token,
                    "POST",
                    "/v1/drafts",
                    body,
                    "application/json",
                    state["idempotency_key"],
                )
                draft = result["result"]
                if not isinstance(draft, dict) or not isinstance(draft.get("id"), str):
                    raise ClientError("invalid_draft_response_outcome_unknown", 4)
            except ClientError as error:
                state["status"] = (
                    "outcome_unknown" if error.exit_code == 4 else "http_error"
                )
                save_state(path, state)
                raise
            state.update(status="completed", draft_id=draft["id"])
            save_state(path, state)
            return result
    if args.command == "get-draft":
        try:
            draft_id = str(uuid.UUID(args.draft_id))
        except ValueError:
            raise ClientError("draft_uuid_required") from None
        return request(args, token, "GET", "/v1/drafts/" + draft_id)
    if not 1 <= args.limit <= 100 or not 0 <= args.offset <= 100000:
        raise ClientError("invalid_pagination")
    return request(
        args, token, "GET", f"/v1/drafts?limit={args.limit}&offset={args.offset}"
    )


def main(argv=None):
    parser = argparse.ArgumentParser(
        description="PixelReceipt ingest-only HTTP client (no reviewer/confirm commands)"
    )
    parser.add_argument(
        "--base-url",
        default=os.environ.get("PIXELRECEIPT_API_URL"),
    )
    parser.add_argument(
        "--token-file",
        type=Path,
        help="owned mode-600 ingest token file; never pass the token itself",
    )
    parser.add_argument("--timeout", type=float, default=30)
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("health")
    upload = commands.add_parser("upload")
    upload.add_argument("--file", type=Path, required=True)
    upload.add_argument("--media-type", choices=sorted(set(MEDIA.values())))
    create = commands.add_parser("create-draft")
    create.add_argument(
        "--body",
        type=Path,
        required=True,
        help="CreateDraft JSON; receipt data stays local",
    )
    create.add_argument(
        "--request-state",
        type=Path,
        required=True,
        help="persistent private request receipt; reuse after timeout",
    )
    get = commands.add_parser("get-draft")
    get.add_argument("draft_id")
    listing = commands.add_parser("list-drafts")
    listing.add_argument("--limit", type=int, default=20)
    listing.add_argument("--offset", type=int, default=0)
    args = parser.parse_args(argv)
    try:
        result = run(args)
    except ClientError as error:
        print(
            json.dumps({"error": error.code, **error.details}, ensure_ascii=False),
            file=sys.stderr,
        )
        return error.exit_code
    except (OSError, ValueError, RecursionError):
        # Do not expose input paths, receipt text, server bodies or traceback locals.
        print('{"error":"local_io_or_encoding_error"}', file=sys.stderr)
        return 2
    print(json.dumps(result, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
