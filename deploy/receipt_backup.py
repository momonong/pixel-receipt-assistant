#!/usr/bin/env python3
"""Local private snapshot/verify/restore; never manages services or remote machines."""

import argparse
import hashlib
import json
import os
import re
import shutil
import sqlite3
import stat
import sys
import time
from datetime import UTC, datetime
from pathlib import Path

AUTH_FILES = {"auth-token", "reviewer-token", "reviewer-password"}
LIVE_FILES = AUTH_FILES | {
    "drafts.sqlite3",
    "drafts.sqlite3-wal",
    "drafts.sqlite3-shm",
    "blobs",
}
SNAPSHOT_FILES = AUTH_FILES | {"drafts.sqlite3", "blobs", "snapshot.json"}
HASH = re.compile(r"[0-9a-f]{64}\Z")
SUPPORTED_VERSIONS = {1, 2, 3, 4}
SESSION_TABLES = ("sessions", "device_sessions")


class BackupError(Exception):
    pass


def fail(code):
    raise BackupError(code)


def private_directory(value):
    path = value.expanduser().absolute()
    if path.resolve() != path or path == Path(path.anchor) or path == Path.home():
        fail("dedicated_nonsymlink_directory_required")
    metadata = path.stat()
    if (
        not stat.S_ISDIR(metadata.st_mode)
        or stat.S_IMODE(metadata.st_mode) != 0o700
        or metadata.st_uid != os.geteuid()
    ):
        fail("owned_700_directory_required")
    return path


def regular_file(path, *, private=False):
    metadata = path.lstat()
    if not stat.S_ISREG(metadata.st_mode) or metadata.st_uid != os.geteuid():
        fail("owned_regular_file_required")
    if private and stat.S_IMODE(metadata.st_mode) != 0o600:
        fail("owned_600_file_required")


def file_digest(path):
    regular_file(path)
    digest = hashlib.sha256()
    size = 0
    descriptor = os.open(path, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0))
    with os.fdopen(descriptor, "rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            size += len(chunk)
            digest.update(chunk)
    return digest.hexdigest(), size


def connect_readonly(path):
    regular_file(path, private=True)
    return sqlite3.connect(path.as_uri() + "?mode=ro", uri=True, timeout=5)


def database_manifest(database):
    with connect_readonly(database) as connection:
        version = connection.execute("PRAGMA user_version").fetchone()[0]
        if version not in SUPPORTED_VERSIONS:
            fail("unsupported_database_version")
        if connection.execute("PRAGMA integrity_check").fetchall() != [("ok",)]:
            fail("sqlite_integrity_check_failed")
        if connection.execute("PRAGMA foreign_key_check").fetchone() is not None:
            fail("sqlite_foreign_key_check_failed")
        rows = connection.execute(
            "SELECT sha256, size_bytes FROM evidence ORDER BY sha256"
        ).fetchall()
        for sha256, size in rows:
            if (
                not isinstance(sha256, str)
                or not HASH.fullmatch(sha256)
                or type(size) is not int
                or size < 0
            ):
                fail("invalid_evidence_metadata")
        return version, rows


def validate_entries(path, allowed, *, live=False):
    for child in path.iterdir():
        if child.name not in allowed and not (
            live and child.name.startswith("upload-")
        ):
            fail("unexpected_data_directory_entry")
        if child.is_symlink():
            fail("symlink_refused")
        if child.name != "blobs":
            regular_file(child, private=True)


def validate_blobs(directory, rows, *, exact):
    private_directory(directory)
    names = set()
    for path in directory.iterdir():
        if not HASH.fullmatch(path.name):
            fail("unexpected_blob_entry")
        regular_file(path, private=True)
        names.add(path.name)
    referenced = {sha256 for sha256, _ in rows}
    if exact and names != referenced:
        fail("snapshot_blob_inventory_mismatch")
    for sha256, expected_size in rows:
        actual_hash, size = file_digest(directory / sha256)
        if actual_hash != sha256 or size != expected_size:
            fail("evidence_integrity_failure")
    return len(names - referenced)


def reserve_destination(value):
    path = value.expanduser().absolute()
    private_directory(path.parent)
    if path == path.parent or path.is_symlink():
        fail("new_destination_required")
    # Exclusive mkdir: never replace even an empty existing directory.
    path.mkdir(mode=0o700)
    (path / "INCOMPLETE").write_bytes(b"")
    (path / "INCOMPLETE").chmod(0o600)
    (path / "blobs").mkdir(mode=0o700)
    return path


def copy_private(source, destination):
    regular_file(source, private=True)
    source_fd = os.open(source, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0))
    try:
        destination_fd = os.open(
            destination, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600
        )
        with (
            os.fdopen(destination_fd, "wb") as output,
            os.fdopen(source_fd, "rb") as incoming,
        ):
            source_fd = -1
            shutil.copyfileobj(incoming, output, length=1024 * 1024)
            output.flush()
            os.fsync(output.fileno())
    finally:
        if source_fd != -1:
            os.close(source_fd)


def sync_directory(path):
    descriptor = os.open(path, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def snapshot(source, destination):
    source = private_directory(source)
    validate_entries(source, LIVE_FILES, live=True)
    private_directory(source / "blobs")
    destination = reserve_destination(destination)
    database = destination / "drafts.sqlite3"
    # SQLite backup incorporates committed WAL pages. Never copy only the main DB.
    deadline = time.monotonic() + 60

    def progress(_status, _remaining, _total):
        if time.monotonic() > deadline:
            fail("sqlite_snapshot_timeout")

    with (
        connect_readonly(source / "drafts.sqlite3") as original,
        sqlite3.connect(database) as target,
    ):
        original.backup(target, pages=256, progress=progress)
        target.execute("PRAGMA journal_mode = DELETE")
    database.chmod(0o600)
    version, rows = database_manifest(database)
    for sha256, _ in rows:
        copy_private(source / "blobs" / sha256, destination / "blobs" / sha256)
    # Immutable/no-GC blobs make the DB snapshot + later blob copy consistent.
    # Live auth changes are outside that invariant; operators must pause rotation.
    auth_names = []
    for name in sorted(AUTH_FILES):
        if (source / name).exists():
            copy_private(source / name, destination / name)
            auth_names.append(name)
    skipped = len(list((source / "blobs").iterdir())) - len(rows)
    manifest = {
        "snapshot_version": 1,
        "database_version": version,
        "created_at": datetime.now(UTC).isoformat(),
        "database_sha256": file_digest(database)[0],
        "evidence_count": len(rows),
        "evidence_bytes": sum(size for _, size in rows),
        "auth_files": auth_names,
        "unreferenced_or_later_blobs_not_copied": max(skipped, 0),
        "sessions_in_snapshot": "invalidated_on_restore",
    }
    descriptor = os.open(
        destination / "snapshot.json", os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600
    )
    with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
        json.dump(manifest, stream, sort_keys=True)
        stream.write("\n")
        stream.flush()
        os.fsync(stream.fileno())
    verify(destination, allow_incomplete=True)
    sync_directory(destination / "blobs")
    sync_directory(destination)
    (destination / "INCOMPLETE").unlink()
    sync_directory(destination)
    sync_directory(destination.parent)
    return {
        "status": "snapshot_verified",
        "database_version": version,
        "evidence_count": len(rows),
    }


def verify(source, *, allow_incomplete=False):
    source = private_directory(source)
    validate_entries(
        source, SNAPSHOT_FILES | ({"INCOMPLETE"} if allow_incomplete else set())
    )
    regular_file(source / "snapshot.json", private=True)
    if (source / "snapshot.json").stat().st_size > 16384:
        fail("invalid_snapshot_manifest")
    manifest = json.loads((source / "snapshot.json").read_text(encoding="utf-8"))
    version, rows = database_manifest(source / "drafts.sqlite3")
    expected_auth = sorted(
        path.name for path in source.iterdir() if path.name in AUTH_FILES
    )
    if (
        not isinstance(manifest, dict)
        or manifest.get("snapshot_version") != 1
        or manifest.get("database_version") != version
        or manifest.get("database_sha256") != file_digest(source / "drafts.sqlite3")[0]
        or manifest.get("evidence_count") != len(rows)
        or manifest.get("evidence_bytes") != sum(size for _, size in rows)
        or manifest.get("auth_files") != expected_auth
    ):
        fail("snapshot_manifest_mismatch")
    validate_blobs(source / "blobs", rows, exact=True)
    return {
        "status": "snapshot_verified",
        "database_version": version,
        "evidence_count": len(rows),
    }


def restore(source, destination):
    verify(source)
    source = private_directory(source)
    destination = reserve_destination(destination)
    copy_private(source / "drafts.sqlite3", destination / "drafts.sqlite3")
    with sqlite3.connect(destination / "drafts.sqlite3") as connection:
        session_tables = [
            name
            for name in SESSION_TABLES
            if connection.execute(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", (name,)
            ).fetchone()
        ]
        for name in session_tables:
            connection.execute('DELETE FROM "' + name + '"')
        connection.commit()
        # Return a self-contained main DB; never restore stale WAL/SHM files.
        connection.execute("PRAGMA wal_checkpoint(TRUNCATE)")
    version, rows = database_manifest(destination / "drafts.sqlite3")
    for sha256, _ in rows:
        copy_private(source / "blobs" / sha256, destination / "blobs" / sha256)
    for name in sorted(AUTH_FILES):
        if (source / name).exists():
            copy_private(source / name, destination / name)
    validate_blobs(destination / "blobs", rows, exact=True)
    with connect_readonly(destination / "drafts.sqlite3") as connection:
        for name in session_tables:
            if connection.execute('SELECT COUNT(*) FROM "' + name + '"').fetchone()[0]:
                fail("session_invalidation_failed")
    (destination / "INCOMPLETE").unlink()
    sync_directory(destination / "blobs")
    sync_directory(destination)
    sync_directory(destination.parent)
    return {
        "status": "restored_to_new_directory",
        "database_version": version,
        "evidence_count": len(rows),
        "sessions_invalidated": True,
    }


def main(argv=None):
    parser = argparse.ArgumentParser(
        description="Private local SQLite/evidence snapshot and new-directory restore"
    )
    commands = parser.add_subparsers(dest="command", required=True)
    for action in ("snapshot", "verify", "restore"):
        command = commands.add_parser(action)
        command.add_argument("--source", type=Path, required=True)
        if action != "verify":
            command.add_argument(
                "--destination",
                type=Path,
                required=True,
                help="must not exist; parent must already be owned mode 700",
            )
    args = parser.parse_args(argv)
    previous_umask = os.umask(0o077)
    try:
        if args.command == "verify":
            result = verify(args.source)
        else:
            operation = snapshot if args.command == "snapshot" else restore
            result = operation(args.source, args.destination)
        print(json.dumps(result))
        return 0
    except (BackupError, OSError, sqlite3.Error, ValueError, TypeError) as error:
        code = (
            str(error)
            if isinstance(error, BackupError)
            else "local_snapshot_operation_failed"
        )
        print(
            json.dumps({"error": code, "partial_destination_must_not_be_used": True}),
            file=sys.stderr,
        )
        return 2
    finally:
        os.umask(previous_umask)


if __name__ == "__main__":
    sys.exit(main())
