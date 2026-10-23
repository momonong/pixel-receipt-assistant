#!/usr/bin/env python3
"""Deterministic allowlisted review bundle. No deployment, runtime, or validation claim."""

import argparse
import gzip
import hashlib
import io
import json
import os
import re
import stat
import sys
import tarfile
from pathlib import Path

PACKAGE = "backend/src/pixelreceipt_backend"
MODULES = (
    "__init__.py",
    "analysis_store.py",
    "api.py",
    "auth.py",
    "cli.py",
    "domain.py",
    "evidence.py",
    "inspection.py",
    "inspection_worker.py",
    "intake_models.py",
    "intake_store.py",
    "ledger_models.py",
    "models.py",
    "mobile_release.py",
    "pricing.py",
    "pricing_models.py",
    "recognition.py",
    "store.py",
    "thumbnail_worker.py",
)
STATIC_FILES = (
    "index.html",
    "app.css",
    "app.js",
    "api.mjs",
    "values.mjs",
    "demo.mjs",
    "review.mjs",
    "prices.mjs",
)
ALLOWLIST = tuple(
    sorted(
        [
            *(f"{PACKAGE}/{name}" for name in MODULES),
            *(f"{PACKAGE}/static/{name}" for name in STATIC_FILES),
            "README.md",
            "backend/README.md",
            "backend/pyproject.toml",
            "backend/uv.lock",
            "backend/create-draft.schema.json",
            "backend/review-draft.schema.json",
            "backend/review-receipt.schema.json",
            "backend/confirm-receipt.schema.json",
            "backend/void-ledger.schema.json",
            "backend/price-api-contract-examples.json",
            "backend/examples/synthetic-candidate.json",
            "docs/MOBILE_APP.md",
            "docs/MOBILE_INGEST_CONTRACT.md",
            "docs/PRICE_ANALYSIS_CONTRACT.md",
            "docs/ASUS_DEPLOYMENT.md",
            "docs/DOT_INTEGRATION.md",
            "docs/RELEASE_v0.5.0.md",
            "CHANGELOG.md",
            "deploy/receipt_backup.py",
            "deploy/pixelreceipt.service",
            "deploy/pixelreceipt-forward.service",
            "deploy/ssh_config.example",
            "deploy/receipts.caddy.example",
            "scripts/receipt_api.py",
            "scripts/package_release.py",
        ]
    )
)
EXECUTABLES = {
    "deploy/receipt_backup.py",
    "scripts/receipt_api.py",
    "scripts/package_release.py",
}
MANIFEST_NAME = "RELEASE-MANIFEST.json"
IDENTIFIER = re.compile(r"[a-z0-9][a-z0-9-]{0,63}\Z")
MAX_FILE_BYTES = 4 * 1024 * 1024
MAX_TOTAL_BYTES = 32 * 1024 * 1024


class PackageError(Exception):
    pass


def canonical(value):
    return (
        json.dumps(
            value, sort_keys=True, ensure_ascii=False, separators=(",", ":")
        ).encode()
        + b"\n"
    )


def digest(content):
    return hashlib.sha256(content).hexdigest()


def read_regular(path, limit):
    if path.parent.resolve() != path.parent:
        raise PackageError("symlink_source_path_refused")
    descriptor = os.open(
        path, os.O_RDONLY | os.O_NONBLOCK | getattr(os, "O_NOFOLLOW", 0)
    )
    with os.fdopen(descriptor, "rb") as stream:
        before = os.fstat(stream.fileno())
        if not stat.S_ISREG(before.st_mode):
            raise PackageError("regular_source_file_required")
        content = stream.read(limit + 1)
        after = os.fstat(stream.fileno())
    if len(content) > limit:
        raise PackageError("allowlisted_file_too_large")
    if (before.st_size, before.st_mtime_ns) != (after.st_size, after.st_mtime_ns):
        raise PackageError("source_changed_during_read")
    return content


def private_review_directory(path, *, create=False):
    path = path.expanduser().absolute()
    if (
        Path("/tmp") not in path.parents
        or path.parent.resolve() != path.parent
        or path.is_symlink()
    ):
        raise PackageError("new_private_tmp_review_directory_required")
    if create:
        path.mkdir(mode=0o700)
    metadata = path.stat()
    if (
        not stat.S_ISDIR(metadata.st_mode)
        or stat.S_IMODE(metadata.st_mode) != 0o700
        or metadata.st_uid != os.geteuid()
    ):
        raise PackageError("owned_700_review_directory_required")
    return path


def mode_for(path):
    return 0o755 if path in EXECUTABLES else 0o644


def collect(source):
    source = source.expanduser().absolute()
    if source.resolve() != source or not source.is_dir():
        raise PackageError("nonsymlink_source_checkout_required")
    # Detect new imports/assets that would otherwise produce a silently incomplete runtime.
    modules = {
        path.name for path in (source / PACKAGE).iterdir() if path.suffix == ".py"
    }
    static = {
        path.name
        for path in (source / PACKAGE / "static").iterdir()
        if path.suffix in {".js", ".mjs", ".css", ".html"}
    }
    if modules != set(MODULES) or static != set(STATIC_FILES):
        raise PackageError("runtime_allowlist_needs_review")
    files = {
        relative: read_regular(source / relative, MAX_FILE_BYTES)
        for relative in ALLOWLIST
    }
    if sum(map(len, files.values())) > MAX_TOTAL_BYTES:
        raise PackageError("review_bundle_too_large")
    return files


def write_private(path, content):
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "wb") as stream:
        stream.write(content)
        stream.flush()
        os.fsync(stream.fileno())


def build(source, output, release_id):
    if not IDENTIFIER.fullmatch(release_id):
        raise PackageError("invalid_review_identifier")
    files = collect(source)
    output = private_review_directory(output, create=True)
    write_private(output / "INCOMPLETE", b"")
    manifest = {
        "schema": "pixelreceipt-review-release-1",
        "artifact_kind": "review_candidate",
        "release_id": release_id,
        "validation_status": "not_attested",
        "runtime_setup": "target-host uv sync --locked; do not copy a venv",
        "files": [
            {
                "path": path,
                "sha256": digest(content),
                "size_bytes": len(content),
                "mode": mode_for(path),
            }
            for path, content in files.items()
        ],
    }
    encoded_manifest = canonical(manifest)
    write_private(output / MANIFEST_NAME, encoded_manifest)
    name = f"pixelreceipt-{release_id}.tar.gz"
    descriptor = os.open(output / name, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "wb") as stream:
        with (
            gzip.GzipFile(
                filename="", fileobj=stream, mode="wb", mtime=0
            ) as compressed,
            tarfile.open(
                fileobj=compressed, mode="w", format=tarfile.USTAR_FORMAT
            ) as archive,
        ):
            for relative, content in sorted(
                {**files, MANIFEST_NAME: encoded_manifest}.items()
            ):
                entry = tarfile.TarInfo(relative)
                entry.size = len(content)
                entry.mode = mode_for(relative)
                entry.uid = entry.gid = entry.mtime = 0
                entry.uname = entry.gname = ""
                archive.addfile(entry, io.BytesIO(content))
        stream.flush()
        os.fsync(stream.fileno())
    archive_sha = digest(read_regular(output / name, MAX_TOTAL_BYTES + 1024 * 1024))
    checksums = (
        f"{archive_sha}  {name}\n{digest(encoded_manifest)}  {MANIFEST_NAME}\n".encode()
    )
    write_private(output / "SHA256SUMS", checksums)
    result = verify(output, allow_incomplete=True)
    (output / "INCOMPLETE").unlink()
    descriptor = os.open(output, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)
    return result


def verify(output, *, allow_incomplete=False):
    output = private_review_directory(output)
    encoded = read_regular(output / MANIFEST_NAME, 64 * 1024)
    manifest = json.loads(encoded)
    if (
        not isinstance(manifest, dict)
        or manifest.get("schema") != "pixelreceipt-review-release-1"
        or manifest.get("artifact_kind") != "review_candidate"
        or manifest.get("validation_status") != "not_attested"
        or not isinstance(manifest.get("release_id"), str)
        or not IDENTIFIER.fullmatch(manifest["release_id"])
    ):
        raise PackageError("invalid_review_manifest")
    entries = manifest.get("files")
    if not isinstance(entries, list) or [
        entry.get("path") for entry in entries
    ] != list(ALLOWLIST):
        raise PackageError("manifest_allowlist_mismatch")
    expected = {entry["path"]: entry for entry in entries}
    name = f"pixelreceipt-{manifest['release_id']}.tar.gz"
    inventory = {MANIFEST_NAME, name, "SHA256SUMS"} | (
        {"INCOMPLETE"} if allow_incomplete else set()
    )
    if {path.name for path in output.iterdir()} != inventory:
        raise PackageError("review_directory_inventory_mismatch")
    content = read_regular(output / name, MAX_TOTAL_BYTES + 1024 * 1024)
    archive_sha = digest(content)
    checksums = f"{archive_sha}  {name}\n{digest(encoded)}  {MANIFEST_NAME}\n".encode()
    if read_regular(output / "SHA256SUMS", 1024) != checksums:
        raise PackageError("review_checksums_mismatch")
    seen = set()
    with tarfile.open(fileobj=io.BytesIO(content), mode="r:gz") as archive:
        for member in archive:
            if (
                member.name in seen
                or member.name not in expected | {MANIFEST_NAME: None}
                or not member.isreg()
                or member.size > MAX_FILE_BYTES
                or member.size < 0
                or member.mode != mode_for(member.name)
                or member.uid
                or member.gid
                or member.uname
                or member.gname
                or member.mtime
            ):
                raise PackageError("unsafe_or_unlisted_archive_member")
            seen.add(member.name)
            extracted = archive.extractfile(member)
            if extracted is None:
                raise PackageError("invalid_archive_member")
            member_content = extracted.read(MAX_FILE_BYTES + 1)
            if member.name == MANIFEST_NAME:
                valid = member_content == encoded
            else:
                entry = expected[member.name]
                valid = (
                    entry.get("sha256") == digest(member_content)
                    and entry.get("size_bytes") == len(member_content)
                    and entry.get("mode") == member.mode
                )
            if not valid:
                raise PackageError("archive_content_mismatch")
    if seen != set(expected) | {MANIFEST_NAME}:
        raise PackageError("archive_inventory_mismatch")
    return {
        "status": "review_candidate_verified",
        "validation_status": "not_attested",
        "file_count": len(expected),
        "archive_sha256": archive_sha,
    }


def main(argv=None):
    parser = argparse.ArgumentParser(
        description="Build/verify an allowlisted candidate in a new private /tmp directory"
    )
    commands = parser.add_subparsers(dest="command", required=True)
    build_command = commands.add_parser("build")
    build_command.add_argument("--source-root", type=Path, required=True)
    build_command.add_argument("--release-id", required=True)
    build_command.add_argument("--output-dir", type=Path, required=True)
    verify_command = commands.add_parser("verify")
    verify_command.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        result = (
            build(args.source_root, args.output_dir, args.release_id)
            if args.command == "build"
            else verify(args.output_dir)
        )
    except (
        PackageError,
        OSError,
        ValueError,
        TypeError,
        AttributeError,
        tarfile.TarError,
    ) as error:
        code = (
            str(error)
            if isinstance(error, PackageError)
            else "local_review_packaging_failed"
        )
        print(
            json.dumps({"error": code, "partial_output_must_not_be_used": True}),
            file=sys.stderr,
        )
        return 2
    print(json.dumps(result))
    return 0


if __name__ == "__main__":
    sys.exit(main())
