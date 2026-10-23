"""Durable immutable intake and one application recognition worker's journal."""

import hashlib
import json
import time
import uuid

from .intake_models import CreateIntake
from .models import CreateDraft
from .store import StoreError, canonical, timestamp

ACTIVE = ("queued", "preparing", "submitting", "processing", "unknown")

# Read accounting state from its authoritative rows, never from the immutable
# recognition result. The latest ledger event determines active versus voided.
ACCOUNTING_COLUMNS = """d.stage AS draft_status, l.id AS ledger_id,
    CASE WHEN e.action='voided' THEN 'voided'
         WHEN e.action IS NOT NULL THEN 'active' END AS ledger_status"""
ACCOUNTING_FROM = """FROM intakes i
    LEFT JOIN drafts d ON d.id=i.draft_id
    LEFT JOIN ledger l ON l.id=d.ledger_id AND l.draft_id=d.id
    LEFT JOIN ledger_events e ON e.ledger_id=l.id AND e.revision=(
        SELECT MAX(revision) FROM ledger_events WHERE ledger_id=l.id)"""
ACCOUNTING_DETAIL = f"SELECT i.*, {ACCOUNTING_COLUMNS} {ACCOUNTING_FROM}"


class IntakeStore:
    def __init__(self, store):
        self.store = store

    @staticmethod
    def value(row):
        return {
            "id": row["id"],
            "schema": "pixelreceipt-mobile-ingest-1",
            "input": json.loads(row["input"]),
            "evidence_snapshot": json.loads(row["evidence_snapshot"]),
            "status": row["status"],
            "error_code": row["error_code"],
            "job_key": row["job_key"],
            "draft_id": row["draft_id"],
            "draft_status": row["draft_status"],
            "ledger_id": row["ledger_id"],
            "ledger_status": row["ledger_status"],
            "reference_candidate": json.loads(row["reference_candidate"])
            if row["reference_candidate"]
            else None,
            "result": json.loads(row["result"]) if row["result"] else None,
            "created_at": row["created_at"],
            "updated_at": row["updated_at"],
            "polling_finished": bool(row["polling_finished"]),
        }

    def create(self, request: CreateIntake, key):
        body = request.model_dump(mode="json", by_alias=True)
        serialized = canonical(body)
        digest = hashlib.sha256(serialized.encode()).hexdigest()
        with self.store.connection(write=True) as connection:
            previous = connection.execute(
                ACCOUNTING_DETAIL + " WHERE i.idempotency_key=?", (key,)
            ).fetchone()
            if previous:
                if previous["content_digest"] != digest:
                    raise StoreError(409, "idempotency_key_content_conflict")
                return self.value(previous), False
            snapshot = []
            for entry in request.evidence:
                row = connection.execute(
                    "SELECT * FROM evidence WHERE sha256=?", (entry.sha256,)
                ).fetchone()
                if row is None:
                    raise StoreError(422, "unknown_evidence_reference")
                metadata = self.store._evidence(row)
                if metadata["media_type"] not in {"image/jpeg", "image/png", "image/webp"}:
                    raise StoreError(422, "intake_requires_single_page_images")
                self.store._read_blob(entry.sha256)
                snapshot.append({**metadata, "role": entry.role, "notes": entry.notes})
            identifier = str(uuid.uuid4())
            now = timestamp()
            connection.execute(
                """INSERT INTO intakes (id, idempotency_key, content_digest, input,
                   evidence_snapshot, status, job_key, created_at, updated_at)
                   VALUES (?, ?, ?, ?, ?, 'queued', ?, ?, ?)""",
                (
                    identifier,
                    key,
                    digest,
                    serialized,
                    canonical(snapshot),
                    "pixelreceipt-" + uuid.uuid4().hex,
                    now,
                    now,
                ),
            )
            return self.value(
                connection.execute(ACCOUNTING_DETAIL + " WHERE i.id=?", (identifier,)).fetchone()
            ), True

    def get(self, identifier):
        with self.store.connection() as connection:
            row = connection.execute(ACCOUNTING_DETAIL + " WHERE i.id=?", (identifier,)).fetchone()
            if row is None:
                raise StoreError(404, "intake_not_found")
            return self.value(row)

    def draft_context(self, draft_id):
        # The FK is application-owned. An untrusted source.label is never a lookup key.
        with self.store.connection() as connection:
            draft = connection.execute(
                "SELECT evidence FROM drafts WHERE id=?", (draft_id,)
            ).fetchone()
            if draft is None:
                raise StoreError(404, "draft_not_found")
            row = connection.execute(
                "SELECT id,input,created_at FROM intakes WHERE draft_id=?", (draft_id,)
            ).fetchone()
            if row is None:
                return {"draft_id": draft_id, "intake": None}
            try:
                body = CreateIntake.model_validate(json.loads(row["input"]))
                expected = [{"sha256": entry.sha256, "pages": [1]} for entry in body.evidence]
                if body.purpose != "purchase" or json.loads(draft["evidence"]) != expected:
                    raise ValueError("intake relation does not match immutable evidence")
            except (ValueError, TypeError) as error:
                raise StoreError(503, "intake_context_invalid") from error
            return {
                "draft_id": draft_id,
                "intake": {
                    "id": row["id"],
                    "purpose": body.purpose,
                    "user_notes": body.user_notes,
                    "evidence": [entry.model_dump(mode="json") for entry in body.evidence],
                    "created_at": row["created_at"],
                },
            }

    def list(self, limit, offset):
        # Lists never materialize model payloads or raw outcomes. Each detail is a
        # separate bounded request; a 100-item list cannot allocate 400MiB of results.
        with self.store.connection() as connection:
            return [
                {
                    "id": row["id"],
                    "schema": "pixelreceipt-mobile-ingest-1",
                    "input": {
                        key: json.loads(row["input"])[key]
                        for key in ("purpose", "engine", "user_notes")
                    },
                    "status": row["status"],
                    "error_code": row["error_code"],
                    "job_key": row["job_key"],
                    "draft_id": row["draft_id"],
                    "draft_status": row["draft_status"],
                    "ledger_id": row["ledger_id"],
                    "ledger_status": row["ledger_status"],
                    "created_at": row["created_at"],
                    "updated_at": row["updated_at"],
                    "polling_finished": bool(row["polling_finished"]),
                }
                for row in connection.execute(
                    f"""SELECT i.id,i.input,i.status,i.error_code,i.job_key,i.draft_id,
                        i.created_at,i.updated_at,i.polling_finished,{ACCOUNTING_COLUMNS}
                        {ACCOUNTING_FROM} ORDER BY i.created_at DESC,i.id LIMIT ? OFFSET ?""",
                    (limit, offset),
                )
            ]

    def raw(self, identifier):
        with self.store.connection() as connection:
            row = connection.execute("SELECT * FROM intakes WHERE id=?", (identifier,)).fetchone()
            if row is None:
                raise StoreError(404, "intake_not_found")
            return dict(row)

    def pending(self):
        now = int(time.time())
        with self.store.connection() as connection:
            # A future admission/poll must not block another independent due intake.
            # Old unavailable/no-route rows never reached queue submission and may
            # safely resume admission. Sent/parked jobs retain their original key.
            row = connection.execute(
                """SELECT id FROM intakes WHERE next_poll_at<=? AND polling_finished=0
                   AND (status IN ('queued','preparing','submitting','processing','unknown')
                     OR (status='unavailable' AND error_code='broker_route_unavailable'
                       AND monitor_until=0 AND execution_key IS NULL AND result IS NULL))
                   ORDER BY CASE WHEN status IN ('submitting','processing','unknown')
                     THEN 0 ELSE 1 END,updated_at,created_at,id LIMIT 1""",
                (now,),
            ).fetchone()
            identifier = row["id"] if row else None
        return self.raw(identifier) if identifier else None

    def update(self, identifier, **fields):
        allowed = {
            "status",
            "error_code",
            "payload",
            "config_snapshot",
            "execution_key",
            "result",
            "next_poll_at",
            "monitor_until",
            "poll_attempts",
            "polling_finished",
        }
        if not fields or not fields.keys() <= allowed:
            raise ValueError("unsupported intake update")
        fields["updated_at"] = timestamp()
        with self.store.connection(write=True) as connection:
            connection.execute(
                f"UPDATE intakes SET {','.join(k + '=?' for k in fields)} WHERE id=?",
                (*fields.values(), identifier),
            )

    def complete(self, identifier, candidate, result):
        # Draft creation is itself durable/idempotent. Crash between this and the link
        # reuses the exact same derived key and content, never creates a second draft.
        intake = self.get(identifier)
        draft_id = None
        body = intake["input"]
        if body["purpose"] == "purchase":
            draft, _ = self.store.create_draft(
                CreateDraft.model_validate(
                    {
                        "candidate": candidate,
                        "evidence": [
                            {"sha256": e["sha256"], "pages": [1]} for e in body["evidence"]
                        ],
                        "source": {
                            "kind": "ai_candidate",
                            "label": "mobile-intake:" + identifier,
                            "provider": result.get("provider"),
                            "model": result.get("model"),
                        },
                    }
                ),
                "intake:" + identifier,
            )
            draft_id = draft["id"]
        with self.store.connection(write=True) as connection:
            connection.execute(
                """UPDATE intakes SET status='succeeded', error_code=NULL, result=?,
                   draft_id=?, reference_candidate=?, updated_at=? WHERE id=?""",
                (
                    canonical(result),
                    draft_id,
                    canonical(candidate) if body["purpose"] == "reference" else None,
                    timestamp(),
                    identifier,
                ),
            )
