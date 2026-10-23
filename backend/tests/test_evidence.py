import hashlib
import io
import sqlite3
from pathlib import Path

import pytest
from fastapi.testclient import TestClient
from PIL import Image
from pypdf import PdfWriter

from pixelreceipt_backend.api import create_app
from pixelreceipt_backend.evidence import MAX_UPLOAD_BYTES
from pixelreceipt_backend.store import Store

from .conftest import TOKEN
from .test_drafts import create


def upload(client, content, media_type):
    return client.post("/v1/evidence", content=content, headers={"Content-Type": media_type})


def pdf(pages=1, *, active=False, encrypted=False):
    writer = PdfWriter()
    for _ in range(pages):
        writer.add_blank_page(width=100, height=100)
    if active:
        writer.add_js("app.alert('synthetic');")
    if encrypted:
        writer.encrypt("synthetic-test-only")
    stream = io.BytesIO()
    writer.write(stream)
    return stream.getvalue()


def test_original_bytes_hash_dedup_and_restart(client, store, png):
    expected = hashlib.sha256(png).hexdigest()
    result = upload(client, png, "image/png")
    assert result.status_code == 200
    evidence = result.json()
    assert evidence["sha256"] == expected
    assert evidence["size_bytes"] == len(png)
    assert evidence["width"] == 10
    assert evidence["height"] == 8
    path = store.blobs / expected
    before = path.stat().st_mtime_ns
    assert upload(client, png, "image/png").json() == evidence
    assert path.stat().st_mtime_ns == before
    content = client.get(f"/v1/evidence/{expected}/content")
    assert content.content == png
    assert content.headers["content-disposition"].startswith("attachment")
    assert content.headers["cache-control"] == "no-store"
    with TestClient(create_app(Store(store.data_dir), TOKEN)) as restarted:
        restarted.headers["Authorization"] = "Bearer " + TOKEN
        assert restarted.get(f"/v1/evidence/{expected}").json() == evidence
        assert restarted.get(f"/v1/evidence/{expected}/content").content == png


@pytest.mark.parametrize(
    "format_name,media_type",
    [
        ("JPEG", "image/jpeg"),
        ("WEBP", "image/webp"),
        ("PNG", "image/png"),
    ],
)
def test_supported_image_formats(client, format_name, media_type):
    stream = io.BytesIO()
    Image.new("RGB", (4, 4), "white").save(stream, format=format_name)
    result = upload(client, stream.getvalue(), media_type)
    assert result.status_code == 200
    assert result.json()["media_type"] == media_type


def test_pdf_page_groups_and_download(client, body):
    content = pdf(pages=2)
    evidence = upload(client, content, "application/pdf").json()
    assert evidence["page_count"] == 2
    assert evidence["width"] is None
    body["evidence"] = [{"sha256": evidence["sha256"], "pages": [1, 2]}]
    draft = create(client, body)
    assert draft.status_code == 201
    assert draft.json()["evidence"][0]["pages"] == [1, 2]
    assert client.get(f"/v1/evidence/{evidence['sha256']}/content").content == content


@pytest.mark.parametrize(
    "content,media_type,status",
    [
        (b"", "image/png", 422),
        (b"<script>synthetic</script>", "image/png", 422),
        (b"%PDF-1.7\nmalformed", "application/pdf", 422),
        (b"GIF89a", "image/gif", 415),
        (pdf(active=True), "application/pdf", 422),
        (pdf(encrypted=True), "application/pdf", 422),
        (pdf(pages=101), "application/pdf", 422),
    ],
)
def test_invalid_active_unsupported_evidence_fails_closed(
    client, store, content, media_type, status
):
    assert upload(client, content, media_type).status_code == status
    with store.connection() as connection:
        assert connection.execute("SELECT COUNT(*) FROM evidence").fetchone()[0] == 0
    assert list(store.blobs.iterdir()) == []


def test_upload_limits_mime_mismatch_and_truncation(client, png):
    assert upload(client, png, "image/jpeg").status_code == 422
    assert upload(client, png[:40], "image/png").status_code == 422
    assert upload(client, b"x" * (MAX_UPLOAD_BYTES + 1), "image/png").status_code == 413
    wide = io.BytesIO()
    Image.new("RGB", (20_001, 1)).save(wide, format="PNG")
    assert upload(client, wide.getvalue(), "image/png").status_code == 422


def test_corrupt_or_symlink_blob_never_overwritten_or_attached(client, store, body, png):
    sha256 = body["evidence"][0]["sha256"]
    path = store.blobs / sha256
    path.write_bytes(b"corrupt synthetic data")
    assert client.get(f"/v1/evidence/{sha256}/content").status_code == 503
    assert upload(client, png, "image/png").status_code == 503
    assert create(client, body).status_code == 503
    assert path.read_bytes() == b"corrupt synthetic data"
    path.unlink()
    destination = store.data_dir / "synthetic-outside-blob"
    destination.write_bytes(png)
    path.symlink_to(destination)
    assert client.get(f"/v1/evidence/{sha256}/content").status_code == 503
    assert upload(client, png, "image/png").status_code == 503
    assert destination.read_bytes() == png


def test_store_limit_and_no_path_or_url_ingestion(client, png, monkeypatch):
    monkeypatch.setattr("pixelreceipt_backend.store.MAX_STORED_BYTES", len(png) - 1)
    assert upload(client, png, "image/png").status_code == 507
    assert client.post("/v1/evidence", json={"path": "/etc/passwd"}).status_code == 415
    assert client.post("/v1/evidence", json={"url": "http://127.0.0.1/"}).status_code == 415
    assert client.get("/v1/evidence/invalid-hash").status_code == 404
    assert client.get("/v1/evidence/" + "0" * 64).status_code == 404


def test_auth_required_including_health_and_download(store, png):
    with TestClient(create_app(store, TOKEN)) as unauthenticated:
        assert unauthenticated.get("/health").status_code == 401
        assert unauthenticated.get("/v1/drafts").status_code == 401
        assert upload(unauthenticated, png, "image/png").status_code == 401
        unauthenticated.headers["Authorization"] = "Bearer wrong-token"
        assert unauthenticated.get("/v1/evidence/" + "0" * 64 + "/content").status_code == 401
        assert (
            unauthenticated.options(
                "/v1/drafts",
                headers={
                    "Origin": "https://synthetic.example",
                    "Access-Control-Request-Method": "POST",
                },
            ).headers.get("access-control-allow-origin")
            is None
        )


def test_schema_versions_fail_without_destructive_fallback(tmp_path):
    directory = tmp_path / "future"
    store = Store(directory)
    with store.connection(write=True) as connection:
        connection.execute("PRAGMA user_version = 99")
    with pytest.raises(RuntimeError, match="migration required"):
        Store(directory)
    with sqlite3.connect(directory / "drafts.sqlite3") as connection:
        assert connection.execute("PRAGMA user_version").fetchone()[0] == 99
        assert connection.execute("SELECT COUNT(*) FROM evidence").fetchone()[0] == 0
    unversioned = tmp_path / "unversioned"
    unversioned.mkdir()
    unversioned.chmod(0o700)
    with sqlite3.connect(unversioned / "drafts.sqlite3") as connection:
        connection.execute("CREATE TABLE retained (value TEXT)")
        connection.execute("INSERT INTO retained VALUES ('synthetic-retained')")
    with pytest.raises(RuntimeError, match="unversioned"):
        Store(unversioned)
    with sqlite3.connect(unversioned / "drafts.sqlite3") as connection:
        assert (
            connection.execute("SELECT value FROM retained").fetchone()[0] == "synthetic-retained"
        )


def test_private_storage_permissions_and_refuse_root_or_symlinks(store, tmp_path):
    assert store.data_dir.stat().st_mode & 0o777 == 0o700
    assert store.database.stat().st_mode & 0o777 == 0o600
    with pytest.raises(ValueError):
        Store(Path("/"))
    linked = tmp_path / "symlink"
    linked.symlink_to(store.data_dir, target_is_directory=True)
    with pytest.raises(ValueError):
        Store(linked)


def test_existing_shared_or_unrelated_directory_is_unchanged(tmp_path):
    shared = tmp_path / "shared"
    shared.mkdir(mode=0o755)
    original_mode = shared.stat().st_mode
    with pytest.raises(ValueError, match="already be private"):
        Store(shared)
    assert shared.stat().st_mode == original_mode
    assert list(shared.iterdir()) == []
    private = tmp_path / "private-unrelated"
    private.mkdir(mode=0o700)
    (private / "retained.txt").write_text("synthetic user data")
    with pytest.raises(ValueError, match="unrelated"):
        Store(private)
    assert (private / "retained.txt").read_text() == "synthetic user data"
    assert not (private / "drafts.sqlite3").exists()
    with pytest.raises(ValueError):
        Store(tmp_path / ".." / ".." / ".." / ".." / ".." / "..")
