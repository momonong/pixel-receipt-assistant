"""A database identity survives token rotation, reopen and private backups."""

import uuid

from fastapi.testclient import TestClient

from pixelreceipt_backend.api import create_app
from pixelreceipt_backend.store import Store

from .conftest import TOKEN


def test_ledger_identity_is_read_only_stable_and_independent_of_credentials(
    client, store, tmp_path
):
    response = client.get("/v1/session")
    assert response.status_code == 200
    scope = response.json()["ledger_id"]
    assert str(uuid.UUID(scope)) == scope
    assert response.json()["role"] == "reviewer"
    assert {"auth_method", "csrf_token", "expires_at"} <= set(response.json())
    assert Store(store.data_dir).ledger_id() == scope
    with store.connection() as connection:
        previous = [tuple(row) for row in connection.execute("SELECT * FROM ledger_metadata")]
        changes = connection.total_changes
        assert store.ledger_id(connection) == scope
        assert connection.total_changes == changes
        assert [
            tuple(row) for row in connection.execute("SELECT * FROM ledger_metadata")
        ] == previous
    rotated = "synthetic-rotated-reviewer-token-000000000000000"
    with TestClient(create_app(Store(store.data_dir), TOKEN, reviewer_token=rotated)) as other:
        other.headers["Authorization"] = "Bearer " + rotated
        assert other.get("/v1/session").json()["ledger_id"] == scope
    separate = Store(tmp_path / "separate-ledger")
    with TestClient(create_app(separate, TOKEN, reviewer_token=rotated)) as other:
        other.headers["Authorization"] = "Bearer " + rotated
        assert other.get("/v1/session").json()["ledger_id"] != scope


def test_migrating_v4_allocates_scope_once_without_changing_legacy_records(store):
    with store.connection(write=True) as connection:
        for table in (
            "receipt_attempts",
            "receipt_regions",
            "receipt_groups",
            "ledger_metadata",
            "insight_history",
            "insight_annotations",
        ):
            connection.execute(f"DROP TABLE IF EXISTS {table}")
        connection.execute("PRAGMA user_version=4")
        tables = [
            row[0]
            for row in connection.execute("SELECT name FROM sqlite_master WHERE type='table'")
        ]
        old = {
            table: [tuple(row) for row in connection.execute(f"SELECT * FROM {table}")]
            for table in tables
        }
    migrated = Store(store.data_dir)
    scope = migrated.ledger_id()
    assert str(uuid.UUID(scope)) == scope
    assert Store(store.data_dir).ledger_id() == scope
    with migrated.connection() as connection:
        assert old == {
            table: [tuple(row) for row in connection.execute(f"SELECT * FROM {table}")]
            for table in tables
        }
        assert [tuple(row) for row in connection.execute("SELECT * FROM ledger_metadata")] == [
            ("ledger_id", scope)
        ]
