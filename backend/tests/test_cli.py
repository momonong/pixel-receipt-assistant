import sys

import pytest

from pixelreceipt_backend import cli


def test_init_never_prints_or_overwrites_token_and_launcher_is_loopback(
    tmp_path, monkeypatch, capsys
):
    directory = tmp_path / "private"
    monkeypatch.delenv("PIXELRECEIPT_TOKEN", raising=False)
    monkeypatch.setattr(sys, "argv", ["backend", "init", "--data-dir", str(directory)])
    cli.main()
    token = (directory / "auth-token").read_text().strip()
    assert len(token) >= 32
    assert token not in capsys.readouterr().out
    assert (directory / "auth-token").stat().st_mode & 0o777 == 0o600
    with pytest.raises(FileExistsError):
        cli.main()
    assert (directory / "auth-token").read_text().strip() == token
    monkeypatch.setattr(sys, "argv", ["backend", "serve", "--data-dir", str(directory)])
    captured = {}
    monkeypatch.setattr(cli.uvicorn, "run", lambda app, **kwargs: captured.update(kwargs))
    cli.main()
    assert captured["host"] == "127.0.0.1"
    assert captured["proxy_headers"] is False
    assert captured["access_log"] is False
    assert captured["limit_concurrency"] == 16
    assert captured["timeout_keep_alive"] == 5


def test_token_permissions_and_missing_directory_fail_closed(tmp_path, monkeypatch):
    monkeypatch.delenv("PIXELRECEIPT_TOKEN", raising=False)
    with pytest.raises(ValueError, match="missing auth-token"):
        cli.read_token(tmp_path)
    path = tmp_path / "auth-token"
    path.write_text("synthetic-never-used-credential-0000000000000000")
    path.chmod(0o644)
    with pytest.raises(ValueError, match="must be private"):
        cli.read_token(tmp_path)
    monkeypatch.setattr(sys, "argv", ["backend", "serve", "--data-dir", str(tmp_path / "absent")])
    with pytest.raises(SystemExit):
        cli.main()


def test_configure_reviewer_creates_private_verifier_without_printing_password(
    tmp_path, monkeypatch, capsys
):
    directory = tmp_path / "private"
    monkeypatch.setattr(sys, "argv", ["backend", "init", "--data-dir", str(directory)])
    cli.main()
    password = "synthetic-cli-reviewer-password"
    monkeypatch.setattr(cli.getpass, "getpass", lambda _: password)
    monkeypatch.setattr(
        sys, "argv", ["backend", "configure-reviewer", "--data-dir", str(directory)]
    )
    cli.main()
    verifier = (directory / "reviewer-password").read_text().strip()
    from pixelreceipt_backend.auth import verify_password

    assert verify_password(password, verifier)
    assert password not in capsys.readouterr().out
    assert (directory / "reviewer-password").stat().st_mode & 0o777 == 0o600
    with pytest.raises(FileExistsError):
        cli.main()
    assert (directory / "reviewer-password").read_text().strip() == verifier


def test_insecure_cookie_option_rejects_public_origin(tmp_path, monkeypatch):
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "backend",
            "serve",
            "--data-dir",
            str(tmp_path),
            "--insecure-local-cookie",
            "--public-origin",
            "http://public.example",
        ],
    )
    with pytest.raises(SystemExit):
        cli.main()
