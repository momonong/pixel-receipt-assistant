"""SQLite v1 and immutable content-addressed evidence, with explicit initialization."""

import hashlib
import json
import os
import sqlite3
import tempfile
import time
import uuid
from contextlib import contextmanager
from datetime import UTC, datetime
from pathlib import Path

from .domain import calculate
from .ledger_models import ConfirmReceipt, ReviewDocument, ReviewReceipt, VoidReceipt
from .models import LONG_MAX, Candidate, CreateDraft, ReviewDraft

SCHEMA_VERSION = 4
MAX_STORED_BYTES = 1024 * 1024 * 1024


class StoreError(Exception):
    def __init__(self, status: int, code: str):
        self.status = status
        self.code = code
        super().__init__(code)


def canonical(value: dict) -> str:
    return json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(",", ":"))


def timestamp() -> str:
    return datetime.now(UTC).isoformat(timespec="microseconds")


def private_directory(directory: Path, *, blobs=False) -> Path:
    supplied = directory.expanduser().absolute()
    if supplied.is_symlink():
        raise ValueError("data directory must be a dedicated private directory")
    directory = supplied.resolve()
    if directory == Path(directory.anchor) or directory == Path.home().resolve():
        raise ValueError("data directory must be a dedicated private directory")
    if directory.exists():
        if not directory.is_dir() or directory.stat().st_mode & 0o077:
            raise ValueError("existing data directory must already be private (mode 700)")
        allowed = {
            "auth-token",
            "reviewer-token",
            "reviewer-password",
            "blobs",
            "drafts.sqlite3",
            "drafts.sqlite3-wal",
            "drafts.sqlite3-shm",
        }
        for entry in directory.iterdir():
            recognized = (
                len(entry.name) == 64 and all(c in "0123456789abcdef" for c in entry.name)
                if blobs
                else entry.name in allowed or entry.name.startswith("upload-")
            )
            if not recognized:
                raise ValueError(
                    "existing directory contains unrelated data; choose a dedicated path"
                )
    else:
        directory.mkdir(parents=True, mode=0o700)
    return directory


class Store:
    def __init__(self, data_dir: Path):
        self.data_dir = private_directory(data_dir)
        self.blobs = private_directory(self.data_dir / "blobs", blobs=True)
        self.database = self.data_dir / "drafts.sqlite3"
        if self.database.is_symlink():
            raise ValueError("database must not be a symlink")
        with self.connection(write=True) as connection:
            version = connection.execute("PRAGMA user_version").fetchone()[0]
            if version == 0:
                existing = connection.execute(
                    "SELECT name FROM sqlite_master WHERE name NOT LIKE 'sqlite_%'"
                ).fetchall()
                if existing:
                    raise RuntimeError("unversioned database; explicit migration required")
                # No previous database exists: this is initial schema creation, not a migration.
                statements = [
                    """CREATE TABLE evidence (
                        sha256 TEXT PRIMARY KEY, size_bytes INTEGER NOT NULL,
                        metadata TEXT NOT NULL, created_at TEXT NOT NULL)""",
                    """CREATE TABLE drafts (
                        id TEXT PRIMARY KEY, idempotency_key TEXT NOT NULL UNIQUE,
                        content_digest TEXT NOT NULL, original TEXT NOT NULL,
                        candidate TEXT NOT NULL, evidence TEXT NOT NULL, source TEXT NOT NULL,
                        revision INTEGER NOT NULL, created_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL)""",
                    """CREATE TABLE reviews (
                        draft_id TEXT NOT NULL REFERENCES drafts(id), revision INTEGER NOT NULL,
                        candidate TEXT NOT NULL, review_note TEXT, created_at TEXT NOT NULL,
                        PRIMARY KEY(draft_id, revision))""",
                    "CREATE INDEX drafts_creation ON drafts(created_at, id)",
                    "PRAGMA user_version = 1",
                ]
                for statement in statements:
                    connection.execute(statement)
                version = 1
            if version == 1:
                self._migrate_v1(connection)
                version = 2
            if version == 2:
                self._migrate_v2(connection)
                version = 3
            if version == 3:
                self._migrate_v3(connection)
                version = 4
            if version != SCHEMA_VERSION:
                raise RuntimeError(f"unsupported database version {version}; migration required")
        self.database.chmod(0o600)

    @staticmethod
    def _migrate_v1(connection):
        expected = {
            "evidence": {"sha256", "size_bytes", "metadata", "created_at"},
            "drafts": {
                "id",
                "idempotency_key",
                "content_digest",
                "original",
                "candidate",
                "evidence",
                "source",
                "revision",
                "created_at",
                "updated_at",
            },
            "reviews": {"draft_id", "revision", "candidate", "review_note", "created_at"},
        }
        tables = {
            row[0]
            for row in connection.execute(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'"
            )
        }
        if tables != set(expected):
            raise RuntimeError("invalid v1 schema; migration refused")
        for table, columns in expected.items():
            actual = {row[1] for row in connection.execute(f"PRAGMA table_info({table})")}
            if actual != columns:
                raise RuntimeError("invalid v1 schema; migration refused")
        if connection.execute("PRAGMA quick_check").fetchone()[0] != "ok":
            raise RuntimeError("database integrity check failed; migration refused")
        if connection.execute("PRAGMA foreign_key_check").fetchall():
            raise RuntimeError("database foreign keys invalid; migration refused")
        try:
            for row in connection.execute("SELECT * FROM drafts"):
                CreateDraft.model_validate(
                    {
                        "candidate": json.loads(row["original"]),
                        "source": json.loads(row["source"]),
                        "evidence": json.loads(row["evidence"]),
                    }
                )
                Candidate.model_validate(json.loads(row["candidate"]))
                if not 1 <= row["revision"] <= LONG_MAX:
                    raise ValueError("invalid revision")
            for row in connection.execute("SELECT * FROM reviews"):
                Candidate.model_validate(json.loads(row["candidate"]))
                if not 2 <= row["revision"] <= LONG_MAX:
                    raise ValueError("invalid revision")
        except (ValueError, TypeError) as error:
            raise RuntimeError("invalid v1 records; migration refused") from error
        for statement in (
            "ALTER TABLE drafts ADD COLUMN stage TEXT NOT NULL DEFAULT 'needs_review'",
            "ALTER TABLE drafts ADD COLUMN review_document TEXT",
            "ALTER TABLE drafts ADD COLUMN ledger_id TEXT",
            "ALTER TABLE reviews ADD COLUMN review_document TEXT",
            "ALTER TABLE reviews ADD COLUMN action TEXT NOT NULL DEFAULT 'reviewer'",
            """CREATE TABLE ledger (
                id TEXT PRIMARY KEY, draft_id TEXT NOT NULL UNIQUE REFERENCES drafts(id),
                snapshot TEXT NOT NULL, confirmed_at TEXT NOT NULL, confirmed_by TEXT NOT NULL)""",
            """CREATE TABLE ledger_events (
                ledger_id TEXT NOT NULL REFERENCES ledger(id), revision INTEGER NOT NULL,
                action TEXT NOT NULL, actor TEXT NOT NULL, reason TEXT, created_at TEXT NOT NULL,
                PRIMARY KEY(ledger_id, revision))""",
            """CREATE TABLE mutations (
                idempotency_key TEXT PRIMARY KEY, operation TEXT NOT NULL, target TEXT NOT NULL,
                content_digest TEXT NOT NULL, response TEXT NOT NULL)""",
            """CREATE TABLE sessions (
                token_hash TEXT PRIMARY KEY, csrf_token TEXT NOT NULL,
                created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL)""",
            "CREATE INDEX ledger_creation ON ledger(confirmed_at, id)",
            "PRAGMA user_version = 2",
        ):
            connection.execute(statement)
        if connection.execute("PRAGMA foreign_key_check").fetchall():
            raise RuntimeError("migration integrity check failed")

    @staticmethod
    def _migrate_v2(connection):
        expected = {
            "evidence",
            "drafts",
            "reviews",
            "ledger",
            "ledger_events",
            "mutations",
            "sessions",
        }
        tables = {
            row[0]
            for row in connection.execute(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'"
            )
        }
        if tables != expected or connection.execute("PRAGMA quick_check").fetchone()[0] != "ok":
            raise RuntimeError("invalid v2 schema; migration refused")
        if connection.execute("PRAGMA foreign_key_check").fetchall():
            raise RuntimeError("invalid v2 foreign keys; migration refused")
        # Validate the v2 fields relied on below, before adding any tables.
        required = {
            "drafts": {"stage", "review_document", "ledger_id"},
            "ledger": {"id", "draft_id", "snapshot", "confirmed_at", "confirmed_by"},
            "ledger_events": {"ledger_id", "revision", "action", "actor", "reason", "created_at"},
            "sessions": {"token_hash", "csrf_token", "created_at", "expires_at"},
        }
        for table, columns in required.items():
            actual = {row[1] for row in connection.execute(f"PRAGMA table_info({table})")}
            if not columns <= actual:
                raise RuntimeError("invalid v2 schema; migration refused")
        for statement in (
            """CREATE TABLE products (
                id TEXT PRIMARY KEY, body TEXT NOT NULL, search_text TEXT NOT NULL,
                idempotency_key TEXT NOT NULL UNIQUE, content_digest TEXT NOT NULL,
                created_at TEXT NOT NULL)""",
            """CREATE TABLE price_reviews (
                ledger_id TEXT PRIMARY KEY REFERENCES ledger(id), revision INTEGER NOT NULL,
                document TEXT NOT NULL, updated_at TEXT NOT NULL, actor TEXT NOT NULL)""",
            """CREATE TABLE price_review_history (
                ledger_id TEXT NOT NULL REFERENCES ledger(id), revision INTEGER NOT NULL,
                document TEXT NOT NULL, created_at TEXT NOT NULL, actor TEXT NOT NULL,
                PRIMARY KEY(ledger_id, revision))""",
            "CREATE INDEX products_creation ON products(created_at, id)",
            "PRAGMA user_version = 3",
        ):
            connection.execute(statement)
        if connection.execute("PRAGMA foreign_key_check").fetchall():
            raise RuntimeError("migration integrity check failed")

    @staticmethod
    def _migrate_v3(connection):
        expected = {
            "evidence",
            "drafts",
            "reviews",
            "ledger",
            "ledger_events",
            "mutations",
            "sessions",
            "products",
            "price_reviews",
            "price_review_history",
        }
        actual = {
            row[0]
            for row in connection.execute(
                "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'"
            )
        }
        if actual != expected or connection.execute("PRAGMA quick_check").fetchone()[0] != "ok":
            raise RuntimeError("invalid v3 schema; migration refused")
        if connection.execute("PRAGMA foreign_key_check").fetchall():
            raise RuntimeError("invalid v3 foreign keys; migration refused")
        required = {
            "drafts": {"id", "candidate", "source", "stage", "review_document", "ledger_id"},
            "evidence": {"sha256", "size_bytes", "metadata", "created_at"},
            "sessions": {"token_hash", "csrf_token", "created_at", "expires_at"},
        }
        for table, columns in required.items():
            if not columns <= {r[1] for r in connection.execute(f"PRAGMA table_info({table})")}:
                raise RuntimeError("invalid v3 schema; migration refused")
        for statement in (
            """CREATE TABLE device_sessions (
                device_id TEXT PRIMARY KEY, device_name TEXT NOT NULL,
                token_hash TEXT NOT NULL UNIQUE,
                created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL, revoked_at INTEGER)""",
            """CREATE TABLE intakes (
                id TEXT PRIMARY KEY, idempotency_key TEXT NOT NULL UNIQUE,
                content_digest TEXT NOT NULL, input TEXT NOT NULL, evidence_snapshot TEXT NOT NULL,
                status TEXT NOT NULL, error_code TEXT, job_key TEXT NOT NULL UNIQUE,
                payload TEXT, config_snapshot TEXT, execution_key TEXT, result TEXT,
                draft_id TEXT UNIQUE REFERENCES drafts(id), reference_candidate TEXT,
                created_at TEXT NOT NULL, updated_at TEXT NOT NULL,
                next_poll_at INTEGER NOT NULL DEFAULT 0,
                monitor_until INTEGER NOT NULL DEFAULT 0,
                poll_attempts INTEGER NOT NULL DEFAULT 0,
                polling_finished INTEGER NOT NULL DEFAULT 0)""",
            "CREATE INDEX intakes_creation ON intakes(created_at, id)",
            "PRAGMA user_version = 4",
        ):
            connection.execute(statement)

    @contextmanager
    def connection(self, *, write=False):
        connection = sqlite3.connect(self.database, timeout=5)
        connection.row_factory = sqlite3.Row
        try:
            connection.execute("PRAGMA foreign_keys = ON")
            connection.execute("PRAGMA journal_mode = WAL")
            connection.execute("BEGIN IMMEDIATE" if write else "BEGIN")
            yield connection
            if write:
                connection.commit()
        except BaseException:
            connection.rollback()
            raise
        finally:
            connection.close()

    def _blob_path(self, sha256: str) -> Path:
        # This helper is also called below the HTTP boundary; fail closed here too.
        if len(sha256) != 64 or any(char not in "0123456789abcdef" for char in sha256):
            raise StoreError(404, "evidence_not_found")
        path = self.blobs / sha256
        if path.is_symlink():
            raise StoreError(503, "evidence_integrity_failure")
        return path

    def _read_blob(self, sha256: str) -> bytes:
        path = self._blob_path(sha256)
        try:
            # O_NOFOLLOW protects against following a replaced symlink on supported platforms.
            descriptor = os.open(path, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0))
            with os.fdopen(descriptor, "rb") as stream:
                content = stream.read(20 * 1024 * 1024 + 1)
        except OSError as error:
            raise StoreError(503, "evidence_integrity_failure") from error
        if hashlib.sha256(content).hexdigest() != sha256:
            raise StoreError(503, "evidence_integrity_failure")
        return content

    def put_evidence(self, content: bytes, metadata: dict) -> dict:
        sha256 = hashlib.sha256(content).hexdigest()
        path = self._blob_path(sha256)
        with self.connection(write=True) as connection:
            existing = connection.execute(
                "SELECT * FROM evidence WHERE sha256 = ?", (sha256,)
            ).fetchone()
            if existing:
                self._read_blob(sha256)
                return self._evidence(existing)
            total = connection.execute(
                "SELECT COALESCE(SUM(size_bytes), 0) FROM evidence"
            ).fetchone()[0]
            if total + len(content) > MAX_STORED_BYTES:
                raise StoreError(507, "evidence_storage_limit")
            descriptor, temporary = tempfile.mkstemp(prefix="upload-", dir=self.data_dir)
            try:
                with os.fdopen(descriptor, "wb") as stream:
                    stream.write(content)
                    stream.flush()
                    os.fsync(stream.fileno())
                try:
                    # Atomic no-replace: a retry or crashed previous upload cannot overwrite bytes.
                    os.link(temporary, path)
                except FileExistsError:
                    self._read_blob(sha256)
                directory_fd = os.open(self.blobs, os.O_RDONLY)
                try:
                    os.fsync(directory_fd)
                finally:
                    os.close(directory_fd)
            finally:
                os.unlink(temporary)
            connection.execute(
                "INSERT INTO evidence VALUES (?, ?, ?, ?)",
                (sha256, len(content), canonical(metadata), timestamp()),
            )
            row = connection.execute(
                "SELECT * FROM evidence WHERE sha256 = ?", (sha256,)
            ).fetchone()
            return self._evidence(row)

    @staticmethod
    def _evidence(row) -> dict:
        return {
            "sha256": row["sha256"],
            "size_bytes": row["size_bytes"],
            **json.loads(row["metadata"]),
            "created_at": row["created_at"],
        }

    def get_evidence(self, sha256: str, *, content=False):
        self._blob_path(sha256)
        with self.connection() as connection:
            row = connection.execute(
                "SELECT * FROM evidence WHERE sha256 = ?", (sha256,)
            ).fetchone()
            if row is None:
                raise StoreError(404, "evidence_not_found")
            value = self._evidence(row)
            return (value, self._read_blob(sha256)) if content else value

    @staticmethod
    def _draft(row) -> dict:
        return {
            "id": row["id"],
            "revision": row["revision"],
            "status": row["stage"],
            "ledger_id": row["ledger_id"],
            "review_document": json.loads(row["review_document"])
            if row["review_document"]
            else None,
            "calculation": calculate(
                Candidate.model_validate(json.loads(row["candidate"])),
                ReviewDocument.model_validate(json.loads(row["review_document"]))
                if row["review_document"]
                else None,
            ),
            "original_candidate": json.loads(row["original"]),
            "candidate": json.loads(row["candidate"]),
            "evidence": json.loads(row["evidence"]),
            "source": json.loads(row["source"]),
            "created_at": row["created_at"],
            "updated_at": row["updated_at"],
        }

    def create_draft(self, request: CreateDraft, key: str) -> tuple[dict, bool]:
        body = request.model_dump(mode="json", by_alias=True)
        digest = hashlib.sha256(canonical(body).encode()).hexdigest()
        with self.connection(write=True) as connection:
            row = connection.execute(
                "SELECT * FROM drafts WHERE idempotency_key = ?", (key,)
            ).fetchone()
            if row:
                if row["content_digest"] != digest:
                    raise StoreError(409, "idempotency_key_content_conflict")
                # Return the current record, preserving all review edits and the original ID.
                return self._draft(row), False
            for reference in request.evidence:
                evidence = connection.execute(
                    "SELECT * FROM evidence WHERE sha256 = ?", (reference.sha256,)
                ).fetchone()
                if evidence is None:
                    raise StoreError(422, "unknown_evidence_reference")
                if max(reference.pages) > json.loads(evidence["metadata"])["page_count"]:
                    raise StoreError(422, "invalid_evidence_page")
                self._read_blob(reference.sha256)
            draft_id = str(uuid.uuid4())
            now = timestamp()
            candidate = canonical(body["candidate"])
            connection.execute(
                """INSERT INTO drafts (id, idempotency_key, content_digest, original, candidate,
                    evidence, source, revision, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                (
                    draft_id,
                    key,
                    digest,
                    candidate,
                    candidate,
                    canonical(body["evidence"]),
                    canonical(body["source"]),
                    1,
                    now,
                    now,
                ),
            )
            row = connection.execute("SELECT * FROM drafts WHERE id = ?", (draft_id,)).fetchone()
            return self._draft(row), True

    def get_draft(self, draft_id: str) -> dict:
        with self.connection() as connection:
            row = connection.execute("SELECT * FROM drafts WHERE id = ?", (draft_id,)).fetchone()
            if row is None:
                raise StoreError(404, "draft_not_found")
            return self._draft(row)

    def list_drafts(self, limit: int, offset: int, status: str | None = None) -> list[dict]:
        with self.connection() as connection:
            rows = connection.execute(
                """SELECT * FROM drafts WHERE (? IS NULL OR stage = ?)
                   ORDER BY created_at, id LIMIT ? OFFSET ?""",
                (status, status, limit, offset),
            ).fetchall()
            return [self._draft(row) for row in rows]

    def review(self, draft_id: str, request: ReviewDraft) -> dict:
        with self.connection(write=True) as connection:
            row = connection.execute("SELECT * FROM drafts WHERE id = ?", (draft_id,)).fetchone()
            if row is None:
                raise StoreError(404, "draft_not_found")
            if row["stage"] != "needs_review":
                raise StoreError(409, "draft_not_reviewable")
            if row["revision"] != request.expected_revision:
                raise StoreError(409, "revision_conflict")
            candidate = canonical(request.candidate.model_dump(mode="json", by_alias=True))
            revision = row["revision"] + 1
            now = timestamp()
            changed = connection.execute(
                """UPDATE drafts SET candidate = ?, revision = ?,
                   updated_at = ?, review_document = NULL
                   WHERE id = ? AND revision = ?""",
                (candidate, revision, now, draft_id, request.expected_revision),
            ).rowcount
            if changed != 1:
                raise StoreError(409, "revision_conflict")
            connection.execute(
                """INSERT INTO reviews (draft_id, revision, candidate, review_note, created_at)
                   VALUES (?, ?, ?, ?, ?)""",
                (draft_id, revision, candidate, request.review_note, now),
            )
            row = connection.execute("SELECT * FROM drafts WHERE id = ?", (draft_id,)).fetchone()
            return self._draft(row)

    def history(self, draft_id: str) -> list[dict]:
        original = self.get_draft(draft_id)
        with self.connection() as connection:
            rows = connection.execute(
                "SELECT * FROM reviews WHERE draft_id = ? ORDER BY revision", (draft_id,)
            ).fetchall()
            return [
                {
                    "revision": 1,
                    "candidate": original["original_candidate"],
                    "review_note": None,
                    "review_document": None,
                    "action": "initial_import",
                    "actor": "initial_import",
                    "created_at": original["created_at"],
                }
            ] + [
                {
                    "revision": row["revision"],
                    "candidate": json.loads(row["candidate"]),
                    "review_note": row["review_note"],
                    "actor": "reviewer",
                    "review_document": json.loads(row["review_document"])
                    if row["review_document"]
                    else None,
                    "action": row["action"],
                    "created_at": row["created_at"],
                }
                for row in rows
            ]

    def review_receipt(self, draft_id: str, request: ReviewReceipt) -> dict:
        with self.connection(write=True) as connection:
            row = connection.execute("SELECT * FROM drafts WHERE id = ?", (draft_id,)).fetchone()
            if row is None:
                raise StoreError(404, "draft_not_found")
            if row["stage"] != "needs_review":
                raise StoreError(409, "draft_not_reviewable")
            if row["revision"] != request.expected_revision:
                raise StoreError(409, "revision_conflict")
            candidate = canonical(request.candidate.model_dump(mode="json", by_alias=True))
            document = canonical(request.document())
            revision = row["revision"] + 1
            now = timestamp()
            connection.execute(
                """UPDATE drafts SET candidate = ?, review_document = ?,
                   revision = ?, updated_at = ?
                   WHERE id = ? AND revision = ?""",
                (candidate, document, revision, now, draft_id, request.expected_revision),
            )
            connection.execute(
                """INSERT INTO reviews
                   (draft_id, revision, candidate, review_note, created_at, review_document, action)
                   VALUES (?, ?, ?, ?, ?, ?, 'reviewer')""",
                (draft_id, revision, candidate, request.review_note, now, document),
            )
            return self._draft(
                connection.execute("SELECT * FROM drafts WHERE id = ?", (draft_id,)).fetchone()
            )

    @staticmethod
    def _replay(connection, operation, target, body, key):
        digest = hashlib.sha256(canonical(body).encode()).hexdigest()
        row = connection.execute(
            "SELECT * FROM mutations WHERE idempotency_key = ?", (key,)
        ).fetchone()
        if row and (
            row["operation"] != operation
            or row["target"] != target
            or row["content_digest"] != digest
        ):
            raise StoreError(409, "idempotency_key_content_conflict")
        return (json.loads(row["response"]) if row else None), digest

    def confirm(self, draft_id: str, request: ConfirmReceipt, key: str) -> tuple[dict, bool]:
        with self.connection(write=True) as connection:
            replay, digest = self._replay(
                connection, "confirm", draft_id, request.model_dump(), key
            )
            if replay:
                return replay, False
            row = connection.execute("SELECT * FROM drafts WHERE id = ?", (draft_id,)).fetchone()
            if row is None:
                raise StoreError(404, "draft_not_found")
            if row["revision"] != request.expected_revision:
                raise StoreError(409, "revision_conflict")
            if row["stage"] != "needs_review":
                raise StoreError(409, "draft_not_reviewable")
            intake = connection.execute(
                "SELECT input FROM intakes WHERE draft_id = ?", (draft_id,)
            ).fetchone()
            if intake and json.loads(intake["input"])["purpose"] != "purchase":
                raise StoreError(409, "reference_not_confirmable")
            draft = self._draft(row)
            if not draft["calculation"]["ready"]:
                error = StoreError(409, "confirmation_blocked")
                error.calculation = draft["calculation"]
                raise error
            # Recheck evidence when creating the durable confirmation snapshot.
            for reference in draft["evidence"]:
                self._read_blob(reference["sha256"])
            now = timestamp()
            ledger_id = str(uuid.uuid4())
            connection.execute(
                """UPDATE drafts SET stage = 'confirmed', revision = revision + 1,
                   ledger_id = ?, updated_at = ? WHERE id = ?""",
                (ledger_id, now, draft_id),
            )
            snapshot = self._draft(
                connection.execute("SELECT * FROM drafts WHERE id = ?", (draft_id,)).fetchone()
            )
            connection.execute(
                "INSERT INTO ledger VALUES (?, ?, ?, ?, 'reviewer')",
                (ledger_id, draft_id, canonical(snapshot), now),
            )
            connection.execute(
                "INSERT INTO ledger_events VALUES (?, 1, 'confirmed', 'reviewer', NULL, ?)",
                (ledger_id, now),
            )
            connection.execute(
                """INSERT INTO reviews
                   (draft_id, revision, candidate, review_note, created_at, review_document, action)
                   VALUES (?, ?, ?, NULL, ?, ?, 'confirmed')""",
                (draft_id, snapshot["revision"], row["candidate"], now, row["review_document"]),
            )
            value = self._ledger(connection, ledger_id)
            connection.execute(
                "INSERT INTO mutations VALUES (?, ?, ?, ?, ?)",
                (key, "confirm", draft_id, digest, canonical(value)),
            )
            return value, True

    @staticmethod
    def _ledger(connection, ledger_id: str) -> dict:
        row = connection.execute("SELECT * FROM ledger WHERE id = ?", (ledger_id,)).fetchone()
        if row is None:
            raise StoreError(404, "ledger_not_found")
        events = [
            dict(event)
            for event in connection.execute(
                """SELECT revision, action, actor, reason, created_at FROM ledger_events
                   WHERE ledger_id = ? ORDER BY revision""",
                (ledger_id,),
            )
        ]
        return {
            "id": row["id"],
            "draft_id": row["draft_id"],
            "revision": events[-1]["revision"],
            "status": "voided" if events[-1]["action"] == "voided" else "active",
            "snapshot": json.loads(row["snapshot"]),
            "confirmed_at": row["confirmed_at"],
            "confirmed_by": row["confirmed_by"],
            "events": events,
        }

    def get_ledger(self, ledger_id: str) -> dict:
        with self.connection() as connection:
            return self._ledger(connection, ledger_id)

    def list_ledger(self, limit: int, offset: int) -> list[dict]:
        with self.connection() as connection:
            ids = connection.execute(
                "SELECT id FROM ledger ORDER BY confirmed_at DESC, id LIMIT ? OFFSET ?",
                (limit, offset),
            ).fetchall()
            return [self._ledger(connection, row["id"]) for row in ids]

    def void(self, ledger_id: str, request: VoidReceipt, key: str) -> tuple[dict, bool]:
        with self.connection(write=True) as connection:
            replay, digest = self._replay(connection, "void", ledger_id, request.model_dump(), key)
            if replay:
                return replay, False
            value = self._ledger(connection, ledger_id)
            if value["revision"] != request.expected_revision:
                raise StoreError(409, "revision_conflict")
            if value["status"] != "active":
                raise StoreError(409, "ledger_already_voided")
            draft_row = connection.execute(
                "SELECT revision FROM drafts WHERE id = ?", (value["draft_id"],)
            ).fetchone()
            if draft_row["revision"] >= LONG_MAX:
                raise StoreError(409, "revision_exhausted")
            now = timestamp()
            connection.execute(
                "INSERT INTO ledger_events VALUES (?, ?, 'voided', 'reviewer', ?, ?)",
                (ledger_id, value["revision"] + 1, request.reason, now),
            )
            connection.execute(
                """UPDATE drafts SET stage = 'voided', revision = revision + 1,
                   updated_at = ? WHERE id = ?""",
                (now, value["draft_id"]),
            )
            row = connection.execute(
                "SELECT * FROM drafts WHERE id = ?", (value["draft_id"],)
            ).fetchone()
            connection.execute(
                """INSERT INTO reviews
                   (draft_id, revision, candidate, review_note, created_at, review_document, action)
                   VALUES (?, ?, ?, ?, ?, ?, 'voided')""",
                (
                    row["id"],
                    row["revision"],
                    row["candidate"],
                    request.reason,
                    now,
                    row["review_document"],
                ),
            )
            result = self._ledger(connection, ledger_id)
            connection.execute(
                "INSERT INTO mutations VALUES (?, ?, ?, ?, ?)",
                (key, "void", ledger_id, digest, canonical(result)),
            )
            return result, True

    def create_session(self, token: str, csrf_token: str, lifetime: int = 8 * 3600) -> int:
        now = int(time.time())
        with self.connection(write=True) as connection:
            connection.execute(
                "INSERT INTO sessions VALUES (?, ?, ?, ?)",
                (hashlib.sha256(token.encode()).hexdigest(), csrf_token, now, now + lifetime),
            )
        return now + lifetime

    def get_session(self, token: str) -> dict | None:
        with self.connection() as connection:
            row = connection.execute(
                "SELECT * FROM sessions WHERE token_hash = ?",
                (hashlib.sha256(token.encode()).hexdigest(),),
            ).fetchone()
            return dict(row) if row and row["expires_at"] > int(time.time()) else None

    def delete_session(self, token: str):
        with self.connection(write=True) as connection:
            connection.execute(
                "DELETE FROM sessions WHERE token_hash = ?",
                (hashlib.sha256(token.encode()).hexdigest(),),
            )

    def create_device_session(self, device_id, device_name, token, lifetime=30 * 86400):
        now = int(time.time())
        with self.connection(write=True) as connection:
            connection.execute(
                """INSERT INTO device_sessions VALUES (?, ?, ?, ?, ?, NULL)
                   ON CONFLICT(device_id) DO UPDATE SET device_name=excluded.device_name,
                   token_hash=excluded.token_hash, created_at=excluded.created_at,
                   expires_at=excluded.expires_at, revoked_at=NULL""",
                (
                    device_id,
                    device_name,
                    hashlib.sha256(token.encode()).hexdigest(),
                    now,
                    now + lifetime,
                ),
            )
        return now + lifetime

    def get_device_session(self, token):
        with self.connection() as connection:
            row = connection.execute(
                "SELECT * FROM device_sessions WHERE token_hash=?",
                (hashlib.sha256(token.encode()).hexdigest(),),
            ).fetchone()
            return (
                dict(row)
                if row and row["revoked_at"] is None and row["expires_at"] > int(time.time())
                else None
            )

    def list_devices(self):
        with self.connection() as connection:
            return [
                dict(row)
                for row in connection.execute(
                    """SELECT device_id, device_name, created_at, expires_at, revoked_at
                   FROM device_sessions ORDER BY created_at DESC, device_id"""
                )
            ]

    def revoke_device(self, device_id):
        with self.connection(write=True) as connection:
            connection.execute(
                "UPDATE device_sessions SET revoked_at=? WHERE device_id=?",
                (int(time.time()), device_id),
            )
