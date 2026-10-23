"""Real v3 Store fixture -> separately supplied, frozen v4 source, no live data."""

import hashlib
import importlib.util
import io
import json
import os
import sqlite3
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location(
    "mobile_backup_integration", Path(__file__).parents[2] / "deploy/receipt_backup.py"
)
backup = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(backup)
SOURCE = os.environ.get("PIXELRECEIPT_V4_SOURCE")


def rows(database, *, sessions=False):
    with sqlite3.connect(database) as connection:
        tables = [
            row[0]
            for row in connection.execute(
                "SELECT name FROM sqlite_schema WHERE type='table' "
                "AND name NOT LIKE 'sqlite_%' ORDER BY name"
            )
        ]
        return {
            name: connection.execute('SELECT * FROM "' + name + '" ORDER BY rowid').fetchall()
            for name in tables
            if sessions or name not in {"sessions", "device_sessions"}
        }


@unittest.skipUnless(SOURCE, "set PIXELRECEIPT_V4_SOURCE to frozen candidate backend/src")
class MobileMigrationBackupTests(unittest.TestCase):
    def candidate(self, script, *args):
        result = subprocess.run(
            [
                sys.executable,
                "-I",
                "-B",
                "-c",
                "import sys;sys.path.insert(0,sys.argv.pop(1));" + script,
                str(Path(SOURCE).resolve()),
                *map(str, args),
            ],
            capture_output=True,
            timeout=30,
            check=False,
        )
        self.assertEqual(
            result.returncode, 0, "candidate fixture command failed; output suppressed"
        )
        return result.stdout

    def test_v3_to_v4_preserves_ten_drafts_and_all_notes_jobs_then_restore_revokes(self):
        from PIL import Image
        from pixelreceipt_backend.evidence import inspect_evidence
        from pixelreceipt_backend.models import CreateDraft
        from pixelreceipt_backend.store import SCHEMA_VERSION, Store

        self.assertEqual(SCHEMA_VERSION, 3, "fixture requires the isolated v3 baseline environment")
        with tempfile.TemporaryDirectory(prefix="pixelreceipt-v3-v4-fixture-") as directory:
            parent = Path(directory)
            parent.chmod(0o700)
            store = Store(parent / "live")
            image = io.BytesIO()
            Image.new("RGB", (2, 3), (1, 2, 3)).save(image, format="PNG")
            content = image.getvalue()
            evidence = store.put_evidence(content, inspect_evidence(content, "image/png"))
            candidate = {
                "schema": "pixelreceipt-central-candidate-1",
                "currency": "TWD",
                "amount_unit": "TWD_YUAN",
                "merchant": "合成商店",
                "receipt_date": "2026-10-21",
                "printed_total_yuan": 10,
                "items": [{"raw_name": "合成飲料", "quantity": 1, "printed_line_total_yuan": 10}],
                "adjustments": [],
            }
            for index in range(10):
                store.create_draft(
                    CreateDraft.model_validate(
                        {
                            "evidence": [{"sha256": evidence["sha256"], "pages": [1]}],
                            "source": {"kind": "ai_candidate", "label": "SYNTHETIC"},
                            "candidate": candidate,
                        }
                    ),
                    "fixture-" + str(index),
                )
            store.create_session("synthetic-browser-token", "synthetic-csrf")
            before = rows(store.database)
            self.assertEqual(len(before["drafts"]), 10)
            self.assertEqual(len(before["ledger"]), 0)
            v3backup = parent / "v3backup"
            backup.snapshot(store.data_dir, v3backup)
            # A failure after issuing all DDL still rolls back the one Store transaction.
            rollback_trial = parent / "atomic-failure"
            backup.restore(v3backup, rollback_trial)
            self.candidate(
                "from pathlib import Path;from pixelreceipt_backend.store import Store;"
                "original=Store._migrate_v3\n"
                "def failed(c):\n original(c)\n raise RuntimeError('fixture')\n"
                "Store._migrate_v3=staticmethod(failed)\n"
                "try:Store(Path(sys.argv[1]));raise AssertionError()\n"
                "except RuntimeError:pass",
                rollback_trial,
            )
            with sqlite3.connect(rollback_trial / "drafts.sqlite3") as connection:
                self.assertEqual(connection.execute("PRAGMA user_version").fetchone()[0], 3)
                self.assertIsNone(
                    connection.execute(
                        "SELECT name FROM sqlite_schema WHERE name='intakes'"
                    ).fetchone()
                )
            self.assertEqual(rows(rollback_trial / "drafts.sqlite3"), before)
            self.candidate(
                "from pathlib import Path;"
                "from pixelreceipt_backend.store import Store,SCHEMA_VERSION;"
                "assert SCHEMA_VERSION==4;Store(Path(sys.argv[1]))",
                store.data_dir,
            )
            with self.assertRaises(RuntimeError):
                Store(store.data_dir)  # v3 must refuse a migrated v4 DB.
            after = rows(store.database)
            self.assertEqual({name: after[name] for name in before}, before)
            with sqlite3.connect(store.database) as connection:
                connection.execute(
                    "INSERT INTO device_sessions VALUES "
                    "('device','合成手機','synthetic-device-hash',1,99,NULL)"
                )
                values = {
                    "id": "intake",
                    "idempotency_key": "original-key",
                    "content_digest": "a" * 64,
                    "input": json.dumps(
                        {"user_notes": "價標；幫朋友買", "notes": "完整原始備註"},
                        ensure_ascii=False,
                    ),
                    "evidence_snapshot": json.dumps([{"sha256": evidence["sha256"]}]),
                    "status": "unknown",
                    "job_key": "original-job",
                    "payload": "original-normalized-payload",
                    "config_snapshot": "nonsecret-route-snapshot",
                    "execution_key": "original-execution",
                    "result": "raw-untrusted-model-result",
                    "created_at": "2026-10-21",
                    "updated_at": "2026-10-21",
                }
                columns = list(values)
                connection.execute(
                    "INSERT INTO intakes ("
                    + ",".join(columns)
                    + ") VALUES ("
                    + ",".join("?" for _ in columns)
                    + ")",
                    list(values.values()),
                )
            original = rows(store.database)
            v4backup = parent / "v4backup"
            self.assertEqual(backup.snapshot(store.data_dir, v4backup)["database_version"], 4)
            with sqlite3.connect(v4backup / "drafts.sqlite3") as connection:
                self.assertEqual(
                    connection.execute("SELECT token_hash FROM device_sessions").fetchone()[0],
                    "synthetic-device-hash",
                )
            restored = parent / "restored"
            backup.restore(v4backup, restored)
            self.assertEqual(rows(restored / "drafts.sqlite3"), original)
            with sqlite3.connect(restored / "drafts.sqlite3") as connection:
                self.assertEqual(
                    connection.execute("SELECT COUNT(*) FROM sessions").fetchone()[0], 0
                )
                self.assertEqual(
                    connection.execute("SELECT COUNT(*) FROM device_sessions").fetchone()[0], 0
                )
            self.assertEqual(
                hashlib.sha256((restored / "blobs" / evidence["sha256"]).read_bytes()).hexdigest(),
                evidence["sha256"],
            )
