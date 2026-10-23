"""Authenticated, immutable APK delivery; fixed filenames and streaming downloads."""

import hashlib
import json
import os
import re
import stat
from pathlib import Path

MAX_APK_BYTES = 128 * 1024 * 1024
APPROVED_VERSIONS = ((4, "0.4.2"), (5, "0.4.3"), (6, "0.4.4"), (7, "0.5.0"))


class MobileRelease:
    def __init__(self, directory):
        supplied = Path(directory)
        if not supplied.is_absolute():
            raise ValueError("mobile release requires an absolute directory")
        # The deployment-managed `mobile-current` is the only permitted symlink entry.
        if supplied.is_symlink() and supplied != Path("/srv/pixelreceipt/mobile-current"):
            raise ValueError("unexpected mobile release symlink")
        directory = supplied.resolve(strict=True)
        for path in (directory, *directory.parents):
            info = path.lstat()
            if (
                info.st_uid not in {0, os.getuid()}
                or stat.S_ISLNK(info.st_mode)
                or (info.st_mode & 0o022 and not info.st_mode & stat.S_ISVTX)
            ):
                raise ValueError("unsafe mobile release directory")
        self.apk = directory / "app.apk"
        metadata = directory / "release.json"
        for path in (self.apk, metadata):
            info = path.lstat()
            if (
                not stat.S_ISREG(info.st_mode)
                or info.st_uid not in {0, os.getuid()}
                or info.st_mode & 0o222
            ):
                raise ValueError("mobile release files must be immutable regular files")
        if metadata.stat().st_size > 4096:
            raise ValueError("mobile release metadata too large")
        value = json.loads(metadata.read_text(encoding="ascii"))
        expected = {
            "version_code",
            "version_name",
            "package_name",
            "sha256",
            "size_bytes",
            "signer_sha256",
        }
        if not isinstance(value, dict) or set(value) != expected:
            raise ValueError("invalid mobile release metadata")
        if (
            type(value["version_code"]) is not int
            or (value["version_code"], value["version_name"]) not in APPROVED_VERSIONS
            or value["package_name"] != "com.momonong.pixelreceipt.mobile"
            or type(value["size_bytes"]) is not int
            or not 1 <= value["size_bytes"] <= MAX_APK_BYTES
            or any(
                not isinstance(value[k], str) or not re.fullmatch(r"[0-9a-f]{64}", value[k])
                for k in ("sha256", "signer_sha256")
            )
        ):
            raise ValueError("invalid mobile release metadata")
        digest = hashlib.sha256()
        total = 0
        with self.apk.open("rb") as stream:
            while chunk := stream.read(1024 * 1024):
                total += len(chunk)
                if total > MAX_APK_BYTES:
                    raise ValueError("mobile APK too large")
                digest.update(chunk)
        if total != value["size_bytes"] or digest.hexdigest() != value["sha256"]:
            raise ValueError("mobile release integrity failure")
        self.metadata = value
