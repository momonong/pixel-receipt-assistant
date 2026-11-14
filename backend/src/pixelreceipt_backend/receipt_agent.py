"""Bounded receipt planning and its durable, application-owned relationships.

Coordinates and purpose bindings are untrusted proposals. This module validates
them, keeps the original input, and creates independent drafts through the normal
intake adapter. It never writes ledger entries or calculates money.
"""

import hashlib
import json
import re
import uuid
from typing import Annotated, Literal

from pydantic import BeforeValidator, Field, StringConstraints, model_validator

from .advance_suggestion import _normalized
from .intake_models import CreateIntake
from .models import StrictModel
from .note_review import NEGATION, PARTIAL, PURPOSE_EVIDENCE
from .recognition_models import NoteQuote, RecognitionDocument
from .store import StoreError, canonical, timestamp

LAYOUT_VERSION = "pixelreceipt-layout-1"
READ_VERSION = "pixelreceipt-region-read-1"
MAX_RECEIPTS = 8


class Region(StrictModel):
    image_position: Annotated[int, Field(ge=1, le=20)]
    box: Annotated[list[Annotated[int, Field(ge=0, le=1000)]], Field(min_length=4, max_length=4)]

    @model_validator(mode="after")
    def positive_area(self):
        if self.box[0] >= self.box[2] or self.box[1] >= self.box[3]:
            raise ValueError("empty region")
        return self


class PlannedReceipt(StrictModel):
    receipt_id: Annotated[str, StringConstraints(pattern=r"^r[1-8]$")]
    label: Annotated[str, StringConstraints(min_length=1, max_length=80)]
    regions: Annotated[list[Region], Field(min_length=1, max_length=4)]
    note_quotes: Annotated[list[NoteQuote], Field(max_length=20)]
    purpose: Literal["self", "advance", "gift"] | None


class Layout(StrictModel):
    coverage_complete: bool
    unresolved_note_quotes: Annotated[list[NoteQuote], Field(max_length=20)]
    receipts: Annotated[list[PlannedReceipt], Field(min_length=1, max_length=MAX_RECEIPTS)]


def _coordinate(value):
    # Lossless wire normalization only. Fractions, exponents, whitespace,
    # booleans and out-of-range values remain invalid; no geometry is guessed.
    if isinstance(value, str) and re.fullmatch(r"0|[1-9][0-9]{0,3}", value):
        return int(value)
    return value


Coordinate = Annotated[int, BeforeValidator(_coordinate), Field(ge=0, le=1000)]


class LocatedRegion(StrictModel):
    """Named axes avoid provider-specific x/y bounding-box conventions."""

    image_position: Annotated[int, Field(ge=1, le=20)]
    left: Coordinate
    top: Coordinate
    right: Coordinate
    bottom: Coordinate

    def canonical_region(self):
        return {
            "image_position": self.image_position,
            "box": [self.left, self.top, self.right, self.bottom],
        }


class LocatedReceipt(PlannedReceipt):
    regions: Annotated[list[LocatedRegion], Field(min_length=1, max_length=4)]


class LayoutResponse(Layout):
    receipts: Annotated[list[LocatedReceipt], Field(min_length=1, max_length=MAX_RECEIPTS)]

    def canonical_layout(self):
        value = self.model_dump(mode="json")
        for receipt, source in zip(value["receipts"], self.receipts, strict=True):
            receipt["regions"] = [region.canonical_region() for region in source.regions]
        return value


class ReadAction(StrictModel):
    action: Literal["finish", "read_regions"]
    regions: Annotated[list[Region], Field(max_length=4)]
    issue: Annotated[str, StringConstraints(min_length=1, max_length=500)] | None
    document: RecognitionDocument | None
    printed_unit_price_header: Literal["單價", "unit price"] | None = None

    @model_validator(mode="after")
    def coherent_action(self):
        if self.action == "finish":
            if self.document is None or self.regions:
                raise ValueError("finish requires one document and no further regions")
        elif self.document is not None or not self.regions or self.issue is None:
            raise ValueError("reading requires regions and an explanation")
        return self


SPATIAL = re.compile(r"左(?:邊|側|面)?|右(?:邊|側|面)?")
# Only an entire-receipt phrase can apply to every row. Item-specific, partial,
# negative or ambiguous notes remain ordinary per-item proposals for review.
WHOLE_SUBJECT = (
    r"(?:(?:左|右)(?:邊|側|面)?(?:的)?(?:這|那)?(?:一)?(?:張|份)?(?:收據|明細|發票)?"
    r"|(?:這|那)?張(?:收據|明細|發票)|全部|全都|整張(?:收據|明細|發票)?)"
)
WHOLE_PREDICATE = {
    "advance": r"(?:都)?(?:是|都是)?(?:幫忙)?(?:代買|代墊|代付)(?:的)?",
    "self": r"(?:都)?(?:是|都是)?(?:我)?(?:自己用|自己(?:的)?|自用|我的)(?:的)?",
    "gift": r"(?:都)?(?:是|都是)?(?:送人|送禮|禮物)(?:的)?",
}


def whole_purpose(quote):
    compact = re.sub(r"[\s，,。.!！]+", "", quote)
    if NEGATION.search(compact) or PARTIAL.search(compact):
        return None
    for purpose, predicate in WHOLE_PREDICATE.items():
        if re.fullmatch(WHOLE_SUBJECT + predicate, compact):
            return purpose
    return None


def _sources(body):
    return [
        value
        for value in [body.get("user_notes"), *[entry.get("notes") for entry in body["evidence"]]]
        if value
    ]


def _clauses(note):
    # Preserve whitespace within quotes but separate explicitly different sides.
    return [
        part.strip()
        for part in re.split(r"[，,。；;\r\n!?！？]+|\s+(?=[左右](?:邊|側|面|那|這|張))", note)
        if part.strip()
    ]


def _quote_sources(quote, receipt, body):
    sources = set()
    if body.get("user_notes") and quote in body["user_notes"]:
        sources.add(0)
    for region in receipt.regions:
        position = region.image_position
        if position <= len(body["evidence"]):
            note = body["evidence"][position - 1].get("notes")
            if note and quote in note:
                sources.add(position)
    return sources


def validate_layout(value, body):
    plan = Layout.model_validate(value)
    if not plan.coverage_complete:
        raise ValueError("receipt coverage requires review")
    if len({r.receipt_id for r in plan.receipts}) != len(plan.receipts):
        raise ValueError("duplicate receipt ids")
    if sum(len(r.regions) for r in plan.receipts) > 20:
        raise ValueError("region budget exceeded")
    sources = _sources(body)
    clauses = [clause for note in sources for clause in _clauses(note)]
    all_quotes = [q for r in plan.receipts for q in r.note_quotes] + plan.unresolved_note_quotes
    if any(not any(q in source for source in sources) for q in all_quotes):
        raise ValueError("note quote not present in original input")
    # A quote cannot remove a negation/quantity or ignore the other purpose in
    # its original clause. Explicit left/right clauses may be split at spaces.
    for receipt in plan.receipts:
        positions = {region.image_position for region in receipt.regions}
        scoped_sources = [
            body.get("user_notes"),
            *[
                entry.get("notes")
                for position, entry in enumerate(body["evidence"], 1)
                if position in positions
            ],
        ]
        for quote in receipt.note_quotes:
            if not any(source and quote in source for source in scoped_sources):
                raise ValueError("note belongs to another image")
            related = [c for c in clauses if quote in c]
            if not related or any(
                (NEGATION.search(c) or PARTIAL.search(c)) and c != quote for c in related
            ):
                raise ValueError("shortened ambiguous note")
        if receipt.purpose is not None and (
            not receipt.note_quotes
            or any(whole_purpose(q) != receipt.purpose for q in receipt.note_quotes)
        ):
            raise ValueError("unproven whole-receipt purpose")
        for region in receipt.regions:
            if (
                region.image_position > len(body["evidence"])
                or body["evidence"][region.image_position - 1]["role"] != "receipt"
            ):
                raise ValueError("region must refer to a receipt image")
    for index, first in enumerate(plan.receipts):
        for second in plan.receipts[index + 1 :]:
            for a_quote in first.note_quotes:
                for b_quote in second.note_quotes:
                    if not (a_quote in b_quote or b_quote in a_quote):
                        continue
                    if not _quote_sources(a_quote, first, body) & _quote_sources(
                        b_quote, second, body
                    ):
                        continue
                    all_batch = (
                        a_quote == b_quote
                        and re.match(r"^(?:全部|全都)", a_quote.strip())
                        and whole_purpose(a_quote) is not None
                        and first.purpose == second.purpose == whole_purpose(a_quote)
                    )
                    if not all_batch:
                        raise ValueError("shared note requires group-level review")
            for a in first.regions:
                for b in second.regions:
                    if a.image_position != b.image_position:
                        continue
                    x1, y1, x2, y2 = a.box
                    u1, v1, u2, v2 = b.box
                    intersection = max(0, min(x2, u2) - max(x1, u1)) * max(
                        0, min(y2, v2) - max(y1, v1)
                    )
                    if intersection * 10 >= min((x2 - x1) * (y2 - y1), (u2 - u1) * (v2 - v1)) * 8:
                        raise ValueError("duplicate or nested receipt regions")
    if any(re.search(r"左", c) for c in clauses) and any(re.search(r"右", c) for c in clauses):
        if len(plan.receipts) < 2:
            raise ValueError("both sides requested but a receipt is missing")
    for receipt in plan.receipts:
        sides = {
            "left" if m.group().startswith("左") else "right"
            for quote in receipt.note_quotes
            for m in SPATIAL.finditer(quote)
        }
        if len(sides) > 1:
            raise ValueError("ambiguous side binding")
        for side in sides:
            region = receipt.regions[0]
            others = [
                r
                for other in plan.receipts
                if other != receipt
                for r in other.regions
                if r.image_position == region.image_position
            ]
            if not others:
                raise ValueError("spatial binding lacks a same-photo counterpart")
            center = region.box[0] + region.box[2]
            if side == "left" and any(center >= r.box[0] + r.box[2] for r in others):
                raise ValueError("left note bound to the wrong region")
            if side == "right" and any(center <= r.box[0] + r.box[2] for r in others):
                raise ValueError("right note bound to the wrong region")
    for clause in clauses:
        if any(pattern.search(clause) for pattern in PURPOSE_EVIDENCE.values()) and not any(
            clause in q or q == clause for q in all_quotes
        ):
            raise ValueError("purpose note omitted from plan")
    return plan


def validate_repair(action, receipt):
    for region in action.regions:
        if not any(
            region.image_position == original.image_position
            and original.box[0] <= region.box[0] < region.box[2] <= original.box[2]
            and original.box[1] <= region.box[1] < region.box[3] <= original.box[3]
            for original in receipt.regions
        ):
            raise ValueError("repair region outside its receipt")
    if len({canonical(r.model_dump()) for r in action.regions}) != len(action.regions):
        raise ValueError("duplicate repair regions")


def apply_receipt_purpose(document, receipt):
    """Return model proposals; the existing deterministic reviewer still checks them."""
    result = document.model_dump(mode="json", by_alias=True, exclude_none=False)
    if receipt.purpose is not None:
        # Layout has already proven the literal whole-receipt subject and its
        # spatial binding. A crop reader cannot see the neighbouring receipt;
        # uncertainty about that same subject is resolved by this stored binding.
        # Keep contradictory purposes, different quotes and invalid item mappings
        # for the ordinary reviewer instead of silently repairing them.
        quotes = {q for q in receipt.note_quotes if whole_purpose(q) == receipt.purpose}
        result["note_intents"] = [
            p
            for p in result["note_intents"]
            if not (
                p["note_quote"] in quotes
                and p["purpose"] == receipt.purpose
                and 0 <= p["item_position"] < len(result["candidate"]["items"])
                and p["item_name"] == result["candidate"]["items"][p["item_position"]]["raw_name"]
            )
        ]
        result["unresolved_note_quotes"] = [
            q for q in result["unresolved_note_quotes"] if q not in quotes
        ]
        existing = {p["item_position"] for p in result["note_intents"]}
        for position, item in enumerate(result["candidate"]["items"]):
            if position not in existing and item["raw_name"] is not None:
                result["note_intents"].append(
                    {
                        "item_position": position,
                        "item_name": item["raw_name"],
                        "purpose": receipt.purpose,
                        "note_quote": receipt.note_quotes[0],
                        "whole_line": True,
                    }
                )
    return result


def summaries(connection, identifier):
    from .intake_store import ACCOUNTING_COLUMNS, ACCOUNTING_FROM

    rows = connection.execute(
        f"""SELECT r.receipt_id,r.specification,r.child_intake_id,i.status,i.error_code,
            i.draft_id,{ACCOUNTING_COLUMNS} {ACCOUNTING_FROM}
            JOIN receipt_regions r ON r.child_intake_id=i.id
            WHERE r.parent_id=? ORDER BY r.ordinal""",
        (identifier,),
    ).fetchall()
    return [
        {
            **{
                key: row[key]
                for key in (
                    "receipt_id",
                    "status",
                    "error_code",
                    "draft_id",
                    "draft_status",
                    "ledger_id",
                    "ledger_status",
                )
            },
            "intake_id": row["child_intake_id"],
            **{
                key: json.loads(row["specification"])[key]
                for key in ("label", "regions", "purpose")
            },
        }
        for row in rows
    ]


def group_fields(connection, identifier):
    receipts = summaries(connection, identifier)
    return {"receipts": receipts} if receipts else {}


def _ambiguous_item_quotes(connection, parent_id, plan, body):
    """Recheck semantic bindings once all same-source receipt rows are known."""
    rows = connection.execute(
        """SELECT r.receipt_id,d.candidate,i.result FROM receipt_regions r
        JOIN intakes i ON i.id=r.child_intake_id LEFT JOIN drafts d ON d.id=i.draft_id
        WHERE r.parent_id=?""",
        (parent_id,),
    ).fetchall()
    candidates = {
        row["receipt_id"]: json.loads(row["candidate"]) if row["candidate"] else None
        for row in rows
    }
    results = {
        row["receipt_id"]: json.loads(row["result"]) if row["result"] else {} for row in rows
    }
    unresolved = []
    for receipt in plan.receipts:
        for quote in receipt.note_quotes:
            if (
                whole_purpose(quote)
                or SPATIAL.search(quote)
                or not any(pattern.search(quote) for pattern in PURPOSE_EVIDENCE.values())
            ):
                continue
            origins = _quote_sources(quote, receipt, body)
            related = [
                other for other in plan.receipts if origins & _quote_sources(quote, other, body)
            ]
            if len(related) < 2:
                continue
            if any(candidates.get(other.receipt_id) is None for other in related):
                unresolved.append(quote)
                continue
            named = {
                _normalized(item["raw_name"])
                for item in candidates[receipt.receipt_id]["items"]
                if item["raw_name"] and _normalized(item["raw_name"]) in _normalized(quote)
            }
            model_review = results[receipt.receipt_id].get("model_note_review") or {}
            for intent in model_review.get("note_intents") or []:
                if isinstance(intent, dict) and intent.get("note_quote") == quote:
                    name = intent.get("item_name")
                    if isinstance(name, str):
                        named.add(_normalized(name))
            if not named or any(
                sum(
                    any(
                        item["raw_name"] and _normalized(item["raw_name"]) == name
                        for item in candidates[other.receipt_id]["items"]
                    )
                    for other in related
                )
                > 1
                for name in named
            ):
                unresolved.append(quote)
    return unresolved


def retain_group_ambiguity(connection, identifier, review):
    """Group-level ambiguity survives splitting; it cannot become an item intent."""
    row = connection.execute(
        """SELECT g.plan,g.intake_id,i.input FROM receipt_groups g
        JOIN intakes i ON i.id=g.intake_id
        JOIN receipt_regions r ON r.parent_id=g.intake_id
        LEFT JOIN receipt_attempts a ON a.owner_id=r.child_intake_id
        WHERE r.child_intake_id=? OR a.intake_id=?""",
        (identifier, identifier),
    ).fetchone()
    if row:
        plan = Layout.model_validate_json(row["plan"])
        unresolved = [
            *plan.unresolved_note_quotes,
            *_ambiguous_item_quotes(connection, row["intake_id"], plan, json.loads(row["input"])),
        ]
        if unresolved:
            return {
                **review,
                "status": "needs_review",
                "decisions": [
                    d
                    for d in review["decisions"]
                    if not any(
                        d["note_quote"] in quote or quote in d["note_quote"] for quote in unresolved
                    )
                ],
                "personal_yuan": None,
                "advance_yuan": None,
                "unresolved_notes": list(dict.fromkeys([*review["unresolved_notes"], *unresolved])),
            }
    return review


def refresh_parent(connection, identifier):
    attempt = connection.execute(
        "SELECT owner_id FROM receipt_attempts WHERE intake_id=?", (identifier,)
    ).fetchone()
    if attempt:
        row = connection.execute(
            "SELECT status,error_code,polling_finished FROM intakes WHERE id=?", (identifier,)
        ).fetchone()
        if row["status"] != "succeeded":
            connection.execute(
                """UPDATE intakes SET status=?,error_code=?,polling_finished=?,
                updated_at=? WHERE id=? AND status NOT IN ('deleted','succeeded')""",
                (
                    row["status"],
                    row["error_code"],
                    row["polling_finished"],
                    timestamp(),
                    attempt[0],
                ),
            )
        identifier = attempt[0]
    link = connection.execute(
        "SELECT parent_id FROM receipt_regions WHERE child_intake_id=?", (identifier,)
    ).fetchone()
    if not link:
        return
    states = [
        row[0]
        for row in connection.execute(
            "SELECT i.status FROM receipt_regions r JOIN intakes i ON i.id=r.child_intake_id "
            "WHERE r.parent_id=?",
            (link[0],),
        )
    ]
    if all(state == "succeeded" for state in states):
        status, error = "succeeded", None
    elif any(state in {"queued", "preparing", "submitting", "processing"} for state in states):
        status, error = "processing", None
    elif "unknown" in states:
        status, error = "unknown", "multi_receipt_reconciliation_required"
    else:
        status, error = "failed", "multi_receipt_partial"
    connection.execute(
        "UPDATE intakes SET status=?,error_code=?,updated_at=? WHERE id=?",
        (status, error, timestamp(), link[0]),
    )


class AgentStore:
    def __init__(self, store):
        self.store = store

    def role(self, identifier):
        with self.store.connection() as connection:
            link = connection.execute(
                """SELECT r.*,a.intake_id AS attempt_id
                FROM receipt_regions r LEFT JOIN receipt_attempts a ON a.owner_id=r.child_intake_id
                WHERE r.child_intake_id=? OR a.intake_id=?""",
                (identifier, identifier),
            ).fetchone()
            return dict(link) if link else None

    def grouped(self, identifier):
        with self.store.connection() as connection:
            return (
                connection.execute(
                    "SELECT 1 FROM receipt_groups WHERE intake_id=?", (identifier,)
                ).fetchone()
                is not None
            )

    def _insert(self, connection, identifier, body, key):
        body = CreateIntake.model_validate(body).model_dump(mode="json", by_alias=True)
        serialized = canonical(body)
        snapshot = []
        for entry in body["evidence"]:
            row = connection.execute(
                "SELECT * FROM evidence WHERE sha256=?", (entry["sha256"],)
            ).fetchone()
            if row is None:
                raise StoreError(422, "unknown_evidence_reference")
            snapshot.append(
                {**self.store._evidence(row), "role": entry["role"], "notes": entry["notes"]}
            )
        now = timestamp()
        connection.execute(
            """INSERT INTO intakes(id,idempotency_key,content_digest,input,
            evidence_snapshot,status,job_key,created_at,updated_at)
            VALUES (?,?,?,?,?,'queued',?,?,?)""",
            (
                identifier,
                key,
                hashlib.sha256(serialized.encode()).hexdigest(),
                serialized,
                canonical(snapshot),
                "pixelreceipt-" + uuid.uuid4().hex,
                now,
                now,
            ),
        )

    def layout_parent(self, identifier):
        with self.store.connection() as connection:
            row = connection.execute(
                """SELECT a.owner_id,a.action FROM receipt_attempts a
                LEFT JOIN receipt_regions r ON r.child_intake_id=a.owner_id
                WHERE a.intake_id=? AND r.child_intake_id IS NULL""",
                (identifier,),
            ).fetchone()
            return row[0] if row and json.loads(row[1]).get("layout_retry") is True else None

    def materialize_layout_retry(self, identifier):
        with self.store.connection(write=True) as connection:
            old = connection.execute(
                "SELECT intake_id FROM receipt_attempts WHERE owner_id=?", (identifier,)
            ).fetchone()
            if old:
                return old[0]
            parent = connection.execute(
                "SELECT * FROM intakes WHERE id=?", (identifier,)
            ).fetchone()
            if parent is None or parent["status"] == "deleted":
                raise StoreError(409, "intake_deleted")
            if not parent["result"] or not json.loads(parent["result"]).get("layout_retry"):
                raise StoreError(409, "layout_retry_requires_completed_result")
            child_id = str(uuid.uuid5(uuid.UUID(identifier), "layout-retry-1"))
            self._insert(
                connection, child_id, json.loads(parent["input"]), "layout-retry:" + child_id
            )
            connection.execute(
                "INSERT INTO receipt_attempts VALUES (?,?,?)",
                (identifier, child_id, canonical({"layout_retry": True})),
            )
            connection.execute(
                """UPDATE intakes SET status='processing',error_code=NULL,
                updated_at=? WHERE id=?""",
                (timestamp(), identifier),
            )
            return child_id

    def materialize(self, identifier, plan, crops):
        serialized = canonical(plan.model_dump(mode="json"))
        with self.store.connection(write=True) as connection:
            parent = connection.execute(
                "SELECT * FROM intakes WHERE id=?", (identifier,)
            ).fetchone()
            if parent is None or parent["status"] == "deleted":
                raise StoreError(409, "intake_deleted")
            old = connection.execute(
                "SELECT plan FROM receipt_groups WHERE intake_id=?", (identifier,)
            ).fetchone()
            if old:
                if old[0] != serialized:
                    raise StoreError(409, "receipt_plan_conflict")
                return
            body = json.loads(parent["input"])
            validate_layout(plan.model_dump(), body)
            if parent["draft_id"] is not None:
                raise StoreError(409, "intake_already_has_draft")
            connection.execute(
                "INSERT INTO receipt_groups VALUES (?,?,?)", (identifier, serialized, timestamp())
            )
            for ordinal, receipt in enumerate(plan.receipts):
                child_id = str(uuid.uuid5(uuid.UUID(identifier), receipt.receipt_id))
                notes = list(dict.fromkeys(receipt.note_quotes))
                child = {
                    **body,
                    "evidence": crops[receipt.receipt_id],
                    "user_notes": "\n".join(notes) or None,
                }
                self._insert(connection, child_id, child, "receipt-region:" + child_id)
                connection.execute(
                    "INSERT INTO receipt_regions VALUES (?,?,?,?,?)",
                    (
                        identifier,
                        receipt.receipt_id,
                        ordinal,
                        child_id,
                        canonical(receipt.model_dump(mode="json")),
                    ),
                )
            connection.execute(
                """UPDATE intakes SET status='processing',error_code=NULL,
                next_poll_at=0,updated_at=? WHERE id=?""",
                (timestamp(), identifier),
            )

    def materialize_repair(self, identifier, action, crops):
        with self.store.connection(write=True) as connection:
            region = connection.execute(
                "SELECT * FROM receipt_regions WHERE child_intake_id=?", (identifier,)
            ).fetchone()
            if region is None:
                raise StoreError(409, "receipt_repair_budget_exhausted")
            validate_repair(action, PlannedReceipt.model_validate_json(region["specification"]))
            old = connection.execute(
                "SELECT intake_id FROM receipt_attempts WHERE owner_id=?", (identifier,)
            ).fetchone()
            if old:
                return old[0]
            owner = connection.execute("SELECT * FROM intakes WHERE id=?", (identifier,)).fetchone()
            if owner["status"] == "deleted":
                raise StoreError(409, "intake_deleted")
            body = json.loads(owner["input"])
            evidence = {e["sha256"]: e for e in [*body["evidence"], *crops]}
            body["evidence"] = list(evidence.values())
            attempt_id = str(uuid.uuid5(uuid.UUID(identifier), "regional-read-1"))
            self._insert(connection, attempt_id, body, "receipt-repair:" + attempt_id)
            connection.execute(
                "INSERT INTO receipt_attempts VALUES (?,?,?)",
                (identifier, attempt_id, canonical(action.model_dump(mode="json"))),
            )
            connection.execute(
                "UPDATE intakes SET status='processing',updated_at=? WHERE id=?",
                (timestamp(), identifier),
            )
            return attempt_id
