import json
import sqlite3

import pytest

from pixelreceipt_backend.evidence import inspect_evidence
from pixelreceipt_backend.models import CreateDraft, ReviewDraft
from pixelreceipt_backend.store import Store


def downgrade_synthetic_to_v1(store):
    # Rebuild exactly the original v1 schema in a new file; no production data is used.
    with store.connection() as source:
        records = {
            table: [dict(r) for r in source.execute(f"SELECT * FROM {table}")]
            for table in ("evidence", "drafts", "reviews")
        }
    replacement = store.data_dir / "v1-temporary.sqlite3"
    with sqlite3.connect(replacement) as connection:
        connection.execute(
            """CREATE TABLE evidence (sha256 TEXT PRIMARY KEY, size_bytes INTEGER NOT NULL,
               metadata TEXT NOT NULL, created_at TEXT NOT NULL)"""
        )
        connection.execute(
            """CREATE TABLE drafts (id TEXT PRIMARY KEY, idempotency_key TEXT NOT NULL UNIQUE,
               content_digest TEXT NOT NULL, original TEXT NOT NULL, candidate TEXT NOT NULL,
               evidence TEXT NOT NULL, source TEXT NOT NULL, revision INTEGER NOT NULL,
               created_at TEXT NOT NULL, updated_at TEXT NOT NULL)"""
        )
        connection.execute(
            """CREATE TABLE reviews (draft_id TEXT NOT NULL REFERENCES drafts(id),
               revision INTEGER NOT NULL, candidate TEXT NOT NULL, review_note TEXT,
               created_at TEXT NOT NULL, PRIMARY KEY(draft_id, revision))"""
        )
        connection.execute("CREATE INDEX drafts_creation ON drafts(created_at, id)")
        for table, rows in records.items():
            for row in rows:
                for field in ("stage", "review_document", "ledger_id", "action"):
                    row.pop(field, None)
                connection.execute(
                    f"INSERT INTO {table} VALUES ({','.join('?' for _ in row)})",
                    tuple(row.values()),
                )
        connection.execute("PRAGMA user_version = 1")
    replacement.replace(store.database)


def test_v1_migration_preserves_original_revisions_source_evidence_and_bytes(store, png):
    evidence = store.put_evidence(png, inspect_evidence(png, "image/png"))
    candidate = {
        "schema": "pixelreceipt-central-candidate-1",
        "currency": "TWD",
        "amount_unit": "TWD_YUAN",
        "merchant": "SYNTHETIC",
        "receipt_date": None,
        "printed_total_yuan": 0,
        "items": [],
        "adjustments": [],
    }
    draft, _ = store.create_draft(
        CreateDraft.model_validate(
            {
                "candidate": candidate,
                "source": {"kind": "manual"},
                "evidence": [{"sha256": evidence["sha256"], "pages": [1]}],
            }
        ),
        "v1-migration",
    )
    candidate["merchant"] = "SYNTHETIC REVIEWED"
    previous = store.review(
        draft["id"], ReviewDraft.model_validate({"expected_revision": 1, "candidate": candidate})
    )
    history = store.history(draft["id"])
    downgrade_synthetic_to_v1(store)
    reopened = Store(store.data_dir)
    assert reopened.get_draft(draft["id"]) == previous
    assert reopened.history(draft["id"]) == history
    assert reopened.get_evidence(evidence["sha256"], content=True)[1] == png
    with reopened.connection() as connection:
        assert connection.execute("PRAGMA user_version").fetchone()[0] == 4
        assert connection.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
        assert not connection.execute("PRAGMA foreign_key_check").fetchall()


def test_malformed_v1_schema_is_refused_without_partial_alteration(tmp_path):
    directory = tmp_path / "invalid-v1"
    directory.mkdir(mode=0o700)
    with sqlite3.connect(directory / "drafts.sqlite3") as connection:
        connection.execute("CREATE TABLE retained (payload TEXT)")
        connection.execute(
            "INSERT INTO retained VALUES (?)", (json.dumps({"synthetic": "retained"}),)
        )
        connection.execute("PRAGMA user_version = 1")
    with pytest.raises(RuntimeError, match="migration refused"):
        Store(directory)
    with sqlite3.connect(directory / "drafts.sqlite3") as connection:
        assert connection.execute("PRAGMA user_version").fetchone()[0] == 1
        assert json.loads(connection.execute("SELECT payload FROM retained").fetchone()[0]) == {
            "synthetic": "retained"
        }


def test_v2_to_v3_preserves_confirmed_ledger_history_and_sessions(client, body, store):
    from .test_prices import receipt

    ledger = receipt(client, body)
    store.create_session("synthetic-existing-session", "synthetic-csrf")
    previous_draft = store.get_draft(ledger["draft_id"])
    previous_history = store.history(ledger["draft_id"])
    with store.connection(write=True) as connection:
        for table in (
            "intakes",
            "device_sessions",
            "price_review_history",
            "price_reviews",
            "products",
        ):
            connection.execute(f"DROP TABLE {table}")
        connection.execute("PRAGMA user_version = 2")
    reopened = Store(store.data_dir)
    assert reopened.get_ledger(ledger["id"]) == ledger
    assert reopened.get_draft(ledger["draft_id"]) == previous_draft
    assert reopened.history(ledger["draft_id"]) == previous_history
    assert reopened.get_session("synthetic-existing-session")["csrf_token"] == "synthetic-csrf"
    with reopened.connection() as connection:
        assert connection.execute("PRAGMA user_version").fetchone()[0] == 4
        assert connection.execute("SELECT COUNT(*) FROM products").fetchone()[0] == 0
        assert connection.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
