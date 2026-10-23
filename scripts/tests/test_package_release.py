"""Synthetic release trees only. No real repository contents or private credentials."""

import contextlib
import hashlib
import importlib.util
import io
import json
import os
import tarfile
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "package_release.py"
SPEC = importlib.util.spec_from_file_location("package_release", SCRIPT)
packaging = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(packaging)
SECRET = "synthetic-private-file-never-packaged"


class ReleasePackagingTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(
            prefix="pixelreceipt-package-tests-"
        )
        self.addCleanup(self.temporary.cleanup)
        self.parent = Path(self.temporary.name)
        self.source = self.parent / "synthetic-source"
        for relative in packaging.ALLOWLIST:
            path = self.source / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("synthetic allowlisted source: " + relative + "\n")
        self.output = self.parent / "review"

    def call(self, action, output=None):
        output = output or self.output
        args = [action, "--output-dir", str(output)]
        if action == "build":
            args += [
                "--source-root",
                str(self.source),
                "--release-id",
                "synthetic-review",
            ]
        stdout, stderr = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            code = packaging.main(args)
        self.assertNotIn(SECRET, stdout.getvalue() + stderr.getvalue())
        return code, stdout.getvalue(), stderr.getvalue()

    def test_only_allowlisted_sources_and_manifest_are_packaged(self):
        for relative in [
            ".git/config",
            ".receipt-lab/private.jpg",
            "backend/.venv/token",
            "backend/.pixelreceipt-private/auth-token",
            "secrets.env",
            "backend/tests/private-output.json",
            "docs/images/private.png",
            "deploy/pixelreceipt_deploy.py",
            "deploy/mobile_artifact_transport.py",
            "deploy/reconcile_daily_recognition.py",
            "deploy/tests/test_mobile_artifact_transport.py",
            "docs/CENTRAL_BACKEND_PLAN.md",
            "docs/DEVELOPMENT_GOAL.md",
            "docs/MOBILE_UPGRADE.md",
            "docs/DEPLOYMENT_2026-10-21.md",
        ]:
            path = self.source / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(SECRET)
        self.assertEqual(self.call("build")[0], 0)
        self.assertEqual(self.call("verify")[0], 0)
        archive = self.output / "pixelreceipt-synthetic-review.tar.gz"
        with tarfile.open(archive, "r:gz") as bundle:
            self.assertEqual(
                set(bundle.getnames()),
                set(packaging.ALLOWLIST) | {packaging.MANIFEST_NAME},
            )
            for member in bundle:
                self.assertTrue(member.isreg())
                self.assertNotIn(SECRET.encode(), bundle.extractfile(member).read())
        manifest = json.loads((self.output / packaging.MANIFEST_NAME).read_text())
        self.assertEqual(
            {path for path in packaging.ALLOWLIST if path.endswith(".md")},
            {
                "README.md",
                "backend/README.md",
                "docs/MOBILE_APP.md",
                "docs/MOBILE_INGEST_CONTRACT.md",
                "docs/PRICE_ANALYSIS_CONTRACT.md",
                "docs/ASUS_DEPLOYMENT.md",
                "docs/DOT_INTEGRATION.md",
                "docs/RELEASE_v0.5.0.md",
                "CHANGELOG.md",
            },
        )
        self.assertEqual(manifest["artifact_kind"], "review_candidate")
        self.assertEqual(manifest["validation_status"], "not_attested")
        self.assertEqual(self.output.stat().st_mode & 0o777, 0o700)

    def test_reproducible_archive_ignores_source_mtime(self):
        self.assertEqual(self.call("build")[0], 0)
        for relative in packaging.ALLOWLIST:
            os.utime(self.source / relative, (1_000_000, 1_000_000))
        other = self.parent / "second-review"
        self.assertEqual(self.call("build", other)[0], 0)
        name = "pixelreceipt-synthetic-review.tar.gz"
        self.assertEqual((self.output / name).read_bytes(), (other / name).read_bytes())

    def test_missing_or_new_runtime_file_requires_review(self):
        (self.source / packaging.PACKAGE / "new_runtime.py").write_text("synthetic")
        self.assertEqual(self.call("build")[0], 2)
        self.assertFalse(self.output.exists())
        (self.source / packaging.PACKAGE / "new_runtime.py").unlink()
        (self.source / "backend/uv.lock").unlink()
        self.assertEqual(self.call("build")[0], 2)
        self.assertFalse(self.output.exists())

    def test_symlink_source_refused_without_reading_target(self):
        path = self.source / "backend/uv.lock"
        path.unlink()
        private = self.parent / "synthetic-secret"
        private.write_text(SECRET)
        path.symlink_to(private)
        self.assertEqual(self.call("build")[0], 2)
        self.assertFalse(self.output.exists())

    def test_existing_review_directory_is_preserved(self):
        self.output.mkdir(mode=0o700)
        marker = self.output / "unrelated"
        marker.write_text("preserve")
        self.assertEqual(self.call("build")[0], 2)
        self.assertEqual(marker.read_text(), "preserve")

    def test_archive_tamper_and_unsafe_members_rejected(self):
        self.assertEqual(self.call("build")[0], 0)
        path = self.output / "pixelreceipt-synthetic-review.tar.gz"
        content = path.read_bytes()
        path.write_bytes(content[:-1] + bytes([content[-1] ^ 1]))
        self.assertEqual(self.call("verify")[0], 2)
        with tarfile.open(path, "w:gz") as archive:
            link = tarfile.TarInfo("README.md")
            link.type = tarfile.SYMTYPE
            link.linkname = "/synthetic/private-secret"
            archive.addfile(link)
        manifest = (self.output / packaging.MANIFEST_NAME).read_bytes()
        checksums = (
            f"{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.name}\n"
            f"{hashlib.sha256(manifest).hexdigest()}  {packaging.MANIFEST_NAME}\n"
        )
        (self.output / "SHA256SUMS").write_text(checksums)
        self.assertEqual(self.call("verify")[0], 2)

    def test_incomplete_or_extra_output_is_not_accepted(self):
        self.assertEqual(self.call("build")[0], 0)
        (self.output / "INCOMPLETE").write_text("")
        self.assertEqual(self.call("verify")[0], 2)


if __name__ == "__main__":
    unittest.main()
