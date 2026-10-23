"""Offline backup/restore tests with synthetic SQLite and binary evidence only."""

import contextlib
import hashlib
import importlib.util
import io
import json
import sqlite3
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

SCRIPT = Path(__file__).resolve().parents[2] / "deploy" / "receipt_backup.py"
SPEC = importlib.util.spec_from_file_location("receipt_backup", SCRIPT)
backup = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(backup)
SECRET = "synthetic-auth-secret-must-never-appear-in-output"


class BackupTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.parent = Path(self.temporary.name)
        self.parent.chmod(0o700)
        self.live = self.parent / "live"
        self.live.mkdir(mode=0o700)
        (self.live / "blobs").mkdir(mode=0o700)
        self.content = b"synthetic-evidence-no-personal-data"
        self.sha256 = hashlib.sha256(self.content).hexdigest()
        blob = self.live / "blobs" / self.sha256
        blob.write_bytes(self.content)
        blob.chmod(0o600)
        for filename in backup.AUTH_FILES:
            path = self.live / filename
            path.write_text(SECRET)
            path.chmod(0o600)
        self.connection = sqlite3.connect(self.live / "drafts.sqlite3")
        self.addCleanup(self.connection.close)
        self.connection.execute("PRAGMA journal_mode=WAL")
        self.connection.execute(
            "CREATE TABLE evidence (sha256 TEXT PRIMARY KEY, size_bytes INTEGER)"
        )
        self.connection.execute(
            "CREATE TABLE drafts (id TEXT PRIMARY KEY, candidate TEXT)"
        )
        self.connection.execute(
            "CREATE TABLE sessions (token_hash TEXT PRIMARY KEY, csrf_token TEXT)"
        )
        self.connection.execute("PRAGMA user_version=2")
        self.connection.execute(
            "INSERT INTO evidence VALUES (?,?)", (self.sha256, len(self.content))
        )
        self.connection.execute(
            "INSERT INTO drafts VALUES ('synthetic-id','synthetic-candidate')"
        )
        self.connection.execute(
            "INSERT INTO sessions VALUES ('old-session','old-csrf')"
        )
        self.connection.commit()
        for path in self.live.iterdir():
            if path.is_file():
                path.chmod(0o600)
        self.snapshot = self.parent / "backup"
        self.restored = self.parent / "restored"

    def call(self, action, source, destination=None):
        output, error = io.StringIO(), io.StringIO()
        args = [action, "--source", str(source)]
        if destination:
            args += ["--destination", str(destination)]
        with contextlib.redirect_stdout(output), contextlib.redirect_stderr(error):
            code = backup.main(args)
        self.assertNotIn(SECRET, output.getvalue() + error.getvalue())
        return code, output.getvalue(), error.getvalue()

    def test_wal_snapshot_verify_and_new_directory_restore_clears_sessions(self):
        self.assertTrue((self.live / "drafts.sqlite3-wal").exists())
        self.assertEqual(self.call("snapshot", self.live, self.snapshot)[0], 0)
        self.assertEqual(self.call("verify", self.snapshot)[0], 0)
        self.assertFalse((self.snapshot / "drafts.sqlite3-wal").exists())
        self.assertEqual(self.call("restore", self.snapshot, self.restored)[0], 0)
        with sqlite3.connect(self.restored / "drafts.sqlite3") as connection:
            self.assertEqual(
                connection.execute("SELECT COUNT(*) FROM evidence").fetchone()[0], 1
            )
            self.assertEqual(
                connection.execute("SELECT candidate FROM drafts").fetchone()[0],
                "synthetic-candidate",
            )
            self.assertEqual(
                connection.execute("SELECT COUNT(*) FROM sessions").fetchone()[0], 0
            )
        self.assertEqual(
            (self.restored / "blobs" / self.sha256).read_bytes(), self.content
        )
        self.assertEqual((self.restored / "auth-token").read_text(), SECRET)
        self.assertNotIn(SECRET, (self.snapshot / "snapshot.json").read_text())
        with sqlite3.connect(self.live / "drafts.sqlite3") as connection:
            self.assertEqual(
                connection.execute("SELECT COUNT(*) FROM sessions").fetchone()[0], 1
            )
        self.assertEqual(self.restored.stat().st_mode & 0o777, 0o700)

    def test_existing_destination_is_never_overwritten(self):
        self.snapshot.mkdir(mode=0o700)
        marker = self.snapshot / "unrelated"
        marker.write_text("preserve")
        self.assertEqual(self.call("snapshot", self.live, self.snapshot)[0], 2)
        self.assertEqual(marker.read_text(), "preserve")

    def test_v4_snapshot_keeps_jobs_notes_and_hashes_restore_revokes_all_sessions(self):
        self.connection.execute("PRAGMA user_version=4")
        self.connection.execute(
            "CREATE TABLE device_sessions (device_id TEXT, token_hash TEXT)"
        )
        self.connection.execute(
            "INSERT INTO device_sessions VALUES ('device','private-hash')"
        )
        self.connection.execute(
            "CREATE TABLE intakes (id TEXT, input TEXT, payload TEXT, result TEXT, job_key TEXT)"
        )
        intake = (
            "intake",
            "完整備註",
            "original-request",
            "raw-untrusted-result",
            "original-job",
        )
        self.connection.execute("INSERT INTO intakes VALUES (?,?,?,?,?)", intake)
        self.connection.commit()
        self.assertEqual(self.call("snapshot", self.live, self.snapshot)[0], 0)
        with sqlite3.connect(self.snapshot / "drafts.sqlite3") as connection:
            self.assertEqual(
                connection.execute("SELECT * FROM intakes").fetchone(), intake
            )
            self.assertEqual(
                connection.execute("SELECT token_hash FROM device_sessions").fetchone()[
                    0
                ],
                "private-hash",
            )
        self.assertEqual(self.call("restore", self.snapshot, self.restored)[0], 0)
        with sqlite3.connect(self.restored / "drafts.sqlite3") as connection:
            self.assertEqual(
                connection.execute("SELECT * FROM intakes").fetchone(), intake
            )
            self.assertEqual(
                connection.execute("SELECT COUNT(*) FROM sessions").fetchone()[0], 0
            )
            self.assertEqual(
                connection.execute("SELECT COUNT(*) FROM device_sessions").fetchone()[
                    0
                ],
                0,
            )

    def test_failed_final_validation_never_marks_snapshot_complete(self):
        with patch.object(
            backup, "verify", side_effect=backup.BackupError("synthetic_check_failed")
        ):
            self.assertEqual(self.call("snapshot", self.live, self.snapshot)[0], 2)
        self.assertTrue((self.snapshot / "INCOMPLETE").exists())
        self.assertEqual(self.call("verify", self.snapshot)[0], 2)

    def test_corrupted_blob_stops_snapshot_or_restore(self):
        self.assertEqual(self.call("snapshot", self.live, self.snapshot)[0], 0)
        (self.snapshot / "blobs" / self.sha256).write_bytes(b"corrupt")
        self.assertEqual(self.call("verify", self.snapshot)[0], 2)
        self.assertEqual(self.call("restore", self.snapshot, self.restored)[0], 2)
        self.assertFalse(self.restored.exists())

    def test_corrupted_database_and_manifest_are_detected(self):
        self.assertEqual(self.call("snapshot", self.live, self.snapshot)[0], 0)
        with sqlite3.connect(self.snapshot / "drafts.sqlite3") as connection:
            connection.execute("UPDATE drafts SET candidate='changed'")
        self.assertEqual(self.call("verify", self.snapshot)[0], 2)

    def test_unreferenced_blobs_not_copied_and_auth_not_in_manifest(self):
        extra = self.live / "blobs" / ("0" * 64)
        extra.write_bytes(b"orphan")
        extra.chmod(0o600)
        self.assertEqual(self.call("snapshot", self.live, self.snapshot)[0], 0)
        self.assertFalse((self.snapshot / "blobs" / extra.name).exists())
        manifest = json.loads((self.snapshot / "snapshot.json").read_text())
        self.assertEqual(manifest["unreferenced_or_later_blobs_not_copied"], 1)
        self.assertEqual(manifest["auth_files"], sorted(backup.AUTH_FILES))

    def test_source_symlink_and_unknown_schema_fail_closed(self):
        link = self.parent / "linked"
        link.symlink_to(self.live, target_is_directory=True)
        self.assertEqual(self.call("snapshot", link, self.snapshot)[0], 2)
        self.connection.execute("PRAGMA user_version=999")
        self.connection.commit()
        self.assertEqual(self.call("snapshot", self.live, self.snapshot)[0], 2)
        self.assertTrue((self.snapshot / "INCOMPLETE").exists())

    def test_v1_without_sessions_restores(self):
        self.connection.execute("PRAGMA user_version=1")
        self.connection.execute("DROP TABLE sessions")
        self.connection.commit()
        self.assertEqual(self.call("snapshot", self.live, self.snapshot)[0], 0)
        self.assertEqual(self.call("restore", self.snapshot, self.restored)[0], 0)

    def test_public_or_symlink_auth_file_refused(self):
        (self.live / "auth-token").chmod(0o644)
        self.assertEqual(self.call("snapshot", self.live, self.snapshot)[0], 2)
        self.assertFalse(self.snapshot.exists())


if __name__ == "__main__":
    unittest.main()
