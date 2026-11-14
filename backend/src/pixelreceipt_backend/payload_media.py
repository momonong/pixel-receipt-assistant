"""Lossless journal media references. Immutable evidence is never garbage-collected."""

import base64
import hashlib
import json

from .store import StoreError, canonical

SCHEMA = "pixelreceipt-payload-ref-1"
MAX_PAYLOAD_BYTES = 16 * 1024**2


def compact_payload(store, connection, payload):
    if payload is None:
        return None
    body = json.loads(payload)
    # Only canonical, application-owned provider requests are compacted. Historical
    # journals with any other serialization remain untouched, including whitespace.
    if not isinstance(body, dict) or canonical(body) != payload:
        return payload
    references = []

    def walk(value, path):
        if isinstance(value, list):
            return [walk(item, [*path, index]) for index, item in enumerate(value)]
        if not isinstance(value, dict):
            return value
        result = {key: walk(item, [*path, key]) for key, item in value.items()}
        data = value.get("data")
        if value.get("type") not in {"image", "document"} or not isinstance(data, str):
            return result
        try:
            content = base64.b64decode(data, validate=True)
        except ValueError:
            return result
        if base64.b64encode(content).decode("ascii") != data:
            return result
        digest = hashlib.sha256(content).hexdigest()
        row = connection.execute(
            "SELECT size_bytes FROM evidence WHERE sha256=?", (digest,)
        ).fetchone()
        if row is not None and row[0] == len(content):
            # Verify durable bytes before relying on them, even if SHA is indexed.
            store._read_blob(digest)
            result["data"] = None
            references.append(
                {"path": [*path, "data"], "sha256": digest, "size_bytes": len(content)}
            )
        return result

    compact = walk(body, [])
    if not references:
        return payload
    return canonical(
        {
            "schema": SCHEMA,
            "body": compact,
            "media": references,
            "payload_sha256": hashlib.sha256(payload.encode()).hexdigest(),
            "payload_bytes": len(payload.encode()),
        }
    )


def restore_payload(store, payload):
    if payload is None:
        return None
    value = json.loads(payload)
    if not isinstance(value, dict) or value.get("schema") != SCHEMA:
        return payload
    try:
        expected = value["payload_bytes"]
        if type(expected) is not int or not 0 < expected <= MAX_PAYLOAD_BYTES:
            raise ValueError("invalid length")
        body = value["body"]
        if not isinstance(value["media"], list) or len(value["media"]) > 100:
            raise ValueError("invalid references")
        size = len(canonical(body).encode())
        for reference in value["media"]:
            path = reference["path"]
            if not isinstance(path, list) or not 1 <= len(path) <= 30 or path[-1] != "data":
                raise ValueError("invalid path")
            target = body
            for key in path[:-1]:
                target = target[key]
            if (
                not isinstance(target, dict)
                or target["data"] is not None
                or target.get("type") not in {"image", "document"}
            ):
                raise ValueError("invalid media slot")
            content = store._read_blob(reference["sha256"])
            if len(content) != reference["size_bytes"]:
                raise ValueError("invalid media size")
            # A JSON null (4 bytes) becomes a quoted base64 string (N+2).
            size += 4 * ((len(content) + 2) // 3) - 2
            if size > MAX_PAYLOAD_BYTES:
                raise ValueError("payload limit")
            target["data"] = base64.b64encode(content).decode("ascii")
        restored = canonical(body)
        if (
            len(restored.encode()) != expected
            or hashlib.sha256(restored.encode()).hexdigest() != value["payload_sha256"]
        ):
            raise ValueError("payload mismatch")
        return restored
    except (ValueError, KeyError, TypeError, IndexError) as error:
        raise StoreError(503, "payload_integrity_failure") from error
