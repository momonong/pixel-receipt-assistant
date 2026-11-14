import base64
import hashlib
import json
from types import SimpleNamespace

import pytest

from pixelreceipt_backend.intake_store import IntakeStore
from pixelreceipt_backend.store import Store, StoreError, canonical

from .test_mobile_ingest import intake


def test_storage_usage_is_authenticated_and_contains_no_paths(client, store, png, body):
    response = client.get("/v1/storage")
    assert response.status_code == 200
    usage = response.json()
    assert usage["schema"] == "pixelreceipt-storage-1"
    assert usage["evidence_bytes"] == len(png)
    assert usage["evidence_count"] == 1
    assert usage["evidence_limit_bytes"] == 1024**3
    assert usage["database_bytes"] > 0
    assert usage["filesystem_available_bytes"] > 0
    assert usage["warning"] == "ok"
    assert str(store.data_dir) not in response.text
    assert client.get("/v1/storage", headers={"Authorization": ""}).status_code == 401


def test_evidence_capacity_preserves_existing_hash_at_limit(tmp_path, png):
    store = Store(tmp_path / "private", evidence_limit_bytes=len(png), disk_reserve_bytes=0)
    first = store.put_evidence(png, {"media_type": "image/png"})
    assert store.put_evidence(png, {}) == first
    assert store.storage_usage()["warning"] == "full"
    with pytest.raises(StoreError, match="evidence_storage_limit"):
        store.put_evidence(png + b"new", {"media_type": "image/png"})
    assert store.get_evidence(first["sha256"], content=True)[1] == png


def test_low_disk_reserve_rejects_new_photo_but_not_existing(tmp_path, png, monkeypatch):
    import pixelreceipt_backend.store as storage

    store = Store(tmp_path / "private", disk_reserve_bytes=512)
    first = store.put_evidence(png, {"media_type": "image/png"})
    monkeypatch.setattr(storage.shutil, "disk_usage", lambda _: SimpleNamespace(free=512))
    assert store.storage_usage()["warning"] == "low_disk"
    assert store.put_evidence(png, {}) == first
    with pytest.raises(StoreError, match="evidence_disk_reserve"):
        store.put_evidence(b"new", {})


def test_unknown_payload_media_is_deduplicated_and_restored_byte_for_byte(store, body, png):
    journal = IntakeStore(store)
    row, _ = journal.create(intake(body), "storage-payload")
    encoded = base64.b64encode(png).decode("ascii")
    payload = canonical(
        {
            "request_key": row["job_key"],
            "request": {"parts": [{"type": "image", "mime_type": "image/png", "data": encoded}]},
            "notes": "Keep original note exactly. 中文",
        }
    )
    journal.update(row["id"], payload=payload, status="unknown")
    with store.connection() as connection:
        stored = connection.execute(
            "SELECT payload FROM intakes WHERE id=?", (row["id"],)
        ).fetchone()[0]
    assert encoded not in stored
    assert json.loads(stored)["schema"] == "pixelreceipt-payload-ref-1"
    assert journal.raw(row["id"])["payload"] == payload
    reopened = IntakeStore(Store(store.data_dir))
    assert reopened.raw(row["id"])["payload"] == payload
    assert reopened.raw(row["id"])["job_key"] == row["job_key"]
    store._blob_path(hashlib.sha256(png).hexdigest()).write_bytes(b"damaged")
    with pytest.raises(StoreError, match="evidence_integrity_failure"):
        reopened.raw(row["id"])


def test_unregistered_media_and_legacy_payload_are_retained_exactly(store, body):
    journal = IntakeStore(store)
    row, _ = journal.create(intake(body), "unregistered-media")
    payload = canonical({"request": {"parts": [{"type": "image", "data": "eA=="}]}})
    journal.update(row["id"], payload=payload)
    assert journal.raw(row["id"])["payload"] == payload
    with store.connection() as connection:
        assert (
            connection.execute("SELECT payload FROM intakes WHERE id=?", (row["id"],)).fetchone()[0]
            == payload
        )


@pytest.mark.parametrize("media_count", [1, 2])
def test_payload_at_request_limit_can_still_restore(store, png, media_count):
    from pixelreceipt_backend.payload_media import (
        MAX_PAYLOAD_BYTES,
        compact_payload,
        restore_payload,
    )

    store.put_evidence(png, {"media_type": "image/png"})
    body = {
        "parts": [
            {"type": "image", "data": base64.b64encode(png).decode()} for _ in range(media_count)
        ],
        "note": "",
    }
    body["note"] = "x" * (MAX_PAYLOAD_BYTES - len(canonical(body).encode()))
    payload = canonical(body)
    assert len(payload.encode()) == MAX_PAYLOAD_BYTES
    with store.connection() as connection:
        packed = compact_payload(store, connection, payload)
    assert restore_payload(store, packed) == payload


def test_usage_tolerates_wal_disappearing_during_file_stat(store, monkeypatch):
    from pathlib import Path

    original = Path.stat
    calls = 0

    def racing_stat(path, *args, **kwargs):
        nonlocal calls
        if path.name == "drafts.sqlite3-wal":
            calls += 1
            if calls == 2:
                raise FileNotFoundError("synthetic checkpoint")
        return original(path, *args, **kwargs)

    with store.connection():
        monkeypatch.setattr(Path, "stat", racing_stat)
        assert store.storage_usage()["database_bytes"] >= store.database.stat().st_size
