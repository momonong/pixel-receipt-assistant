"""Loopback-only launcher; deployment reverse proxies must provide TLS separately."""

import argparse
import getpass
import os
import secrets
from pathlib import Path
from urllib.parse import urlsplit

import uvicorn

from .api import create_app
from .auth import password_hash
from .recognition import load_config
from .store import Store, private_directory


def read_private(directory: Path, name: str, env_name: str) -> str | None:
    configured = os.environ.get(env_name)
    if configured:
        return configured
    path = directory / name
    if not path.exists() and not path.is_symlink():
        return None
    if path.is_symlink() or not path.is_file():
        raise ValueError(f"{name} must be a private regular file")
    if path.stat().st_mode & 0o077:
        raise ValueError(f"{name} must be private (chmod 600)")
    return path.read_text(encoding="ascii").strip()


def read_token(directory: Path) -> str:
    token = read_private(directory, "auth-token", "PIXELRECEIPT_TOKEN")
    if token is None:
        raise ValueError("missing auth-token; run init first or set PIXELRECEIPT_TOKEN")
    return token


def main():
    parser = argparse.ArgumentParser(description="PixelReceipt central receipt ledger")
    parser.add_argument("action", choices=["init", "serve", "configure-reviewer"])
    parser.add_argument("--data-dir", type=Path, default=Path(".pixelreceipt-private"))
    parser.add_argument("--port", type=int, default=18991)
    parser.add_argument("--root-path", default="")
    parser.add_argument("--static-dir", type=Path)
    parser.add_argument("--public-origin")
    parser.add_argument("--recognition-config", type=Path)
    parser.add_argument("--mobile-release-dir", type=Path)
    parser.add_argument("--insecure-local-cookie", action="store_true")
    args = parser.parse_args()
    if not 1024 <= args.port <= 65535:
        parser.error("port must be 1024..65535")
    if args.public_origin:
        origin = urlsplit(args.public_origin)
        invalid = (
            origin.scheme not in {"http", "https"}
            or not origin.netloc
            or origin.path not in {"", "/"}
            or origin.query
            or origin.fragment
            or origin.username
        )
        if invalid:
            parser.error("public-origin must contain only scheme and authority")
        if not args.insecure_local_cookie and origin.scheme != "https":
            parser.error("public-origin must use HTTPS")
        if args.insecure_local_cookie and origin.hostname not in {"127.0.0.1", "localhost", "::1"}:
            parser.error("insecure cookies are limited to an explicit loopback origin")
    if args.action == "init":
        directory = private_directory(args.data_dir)
        token_path = directory / "auth-token"
        descriptor = os.open(token_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, "w", encoding="ascii") as stream:
            stream.write(secrets.token_urlsafe(48) + "\n")
        Store(directory)
        print(
            f"Initialized private storage at {directory}; token saved in auth-token (not printed)."
        )
        return
    if not args.data_dir.is_dir():
        parser.error("data directory missing; run init first")
    directory = private_directory(args.data_dir)
    if args.action == "configure-reviewer":
        password = getpass.getpass("Reviewer password (12+ characters): ")
        if password != getpass.getpass("Repeat password: "):
            parser.error("passwords did not match")
        verifier = password_hash(password)
        descriptor = os.open(
            directory / "reviewer-password", os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600
        )
        with os.fdopen(descriptor, "w", encoding="ascii") as stream:
            stream.write(verifier + "\n")
        print("Reviewer verifier created; credential was not printed.")
        return
    token = read_token(directory)
    reviewer_token = read_private(directory, "reviewer-token", "PIXELRECEIPT_REVIEWER_TOKEN")
    verifier = read_private(directory, "reviewer-password", "PIXELRECEIPT_REVIEWER_PASSWORD_HASH")
    store = Store(directory)
    recognition_config, recognition_reason = load_config(args.recognition_config)
    uvicorn.run(
        create_app(
            store,
            token,
            reviewer_token=reviewer_token,
            reviewer_password_hash=verifier,
            root_path=args.root_path,
            static_dir=args.static_dir,
            cookie_secure=not args.insecure_local_cookie,
            public_origin=args.public_origin,
            mobile_release_dir=args.mobile_release_dir,
            recognition_config=recognition_config,
            recognition_unavailable_reason=recognition_reason,
        ),
        host="127.0.0.1",
        port=args.port,
        access_log=False,
        proxy_headers=False,
        limit_concurrency=16,
        timeout_keep_alive=5,
    )


if __name__ == "__main__":
    main()
