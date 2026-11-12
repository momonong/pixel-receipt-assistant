"""Pure-read suggestions, CAS labels and one-transaction manual ledger creation."""

import hashlib
import json
import re
import uuid

from .insight_models import (
    InsightDocument,
    ManualEntry,
    RecognitionAnalysisContext,
    SaveInsights,
    StoredInsightDocument,
)
from .ledger_models import LineDecision, ReviewDocument
from .models import LONG_MAX, Candidate
from .store import StoreError, canonical, timestamp

OPERATIONS = {"insight_draft", "insight_ledger", "manual_entry", "product_create", "price_review"}
MEAL_WORDS = {
    "breakfast": ("早餐", "早飯"),
    "lunch": ("午餐", "午飯"),
    "dinner": ("晚餐", "晚飯"),
    "late_night": ("宵夜", "消夜"),
    "snack": ("點心", "下午茶"),
}


class InsightStore:
    def __init__(self, store):
        self.store = store

    @staticmethod
    def _positions(document, candidate):
        if sorted(item.item_position for item in document.items) != list(
            range(len(candidate["items"]))
        ):
            raise StoreError(422, "insight_items_must_cover_candidate")

    @staticmethod
    def _sources(document, source):
        return {
            **{key: source for key in document if key != "items"},
            "items": [
                {
                    "item_position": item["item_position"],
                    **{key: source for key in item if key != "item_position"},
                }
                for item in document["items"]
            ],
        }

    def _suggest(self, connection, draft, notes=None):
        candidate = draft["candidate"]
        document = InsightDocument(items=[]).model_dump(mode="json")
        sources = self._sources(document, "unknown")
        if candidate["receipt_date"] is not None:
            original_date = draft["original_candidate"]["receipt_date"]
            receipt_fact = (
                draft["source"]["kind"] != "manual"
                and original_date is not None
                and original_date == candidate["receipt_date"]
            )
            # The wire review does not preserve an accepted-today marker. A newly
            # supplied or changed date is user input, never a printed receipt fact.
            document["date_source"] = "receipt" if receipt_fact else "user"
            sources["date_source"] = document["date_source"]
        context = None
        row = connection.execute(
            "SELECT input,result FROM intakes WHERE draft_id=?", (draft["id"],)
        ).fetchone()
        if row:
            body = json.loads(row["input"])
            notes = [
                body.get("user_notes"),
                *(item.get("notes") for item in body.get("evidence", [])),
            ]
            result = json.loads(row["result"]) if row["result"] else {}
            if result.get("analysis_context") is not None:
                try:
                    context = RecognitionAnalysisContext.model_validate(result["analysis_context"])
                except ValueError as error:
                    raise StoreError(503, "insight_context_invalid") from error
        if context:
            for key in ("brand", "branch", "address", "city", "district", "transaction_time"):
                value = getattr(context, "printed_" + key)
                # City/district proposals require explicit printed address evidence.
                if value is not None and (
                    key not in ("city", "district") or context.printed_address
                ):
                    document[key], sources[key] = value, "receipt"
            if context.channel != "unknown":
                document["channel"], sources["channel"] = context.channel, "rule"
        text = "\n".join(note for note in (notes or []) if isinstance(note, str))
        meal_hits = [
            meal for meal, words in MEAL_WORDS.items() if any(word in text for word in words)
        ]
        # Negation or multiple meals requires human input, never guesses a chosen meal.
        explicit_meal = (
            meal_hits[0]
            if len(meal_hits) == 1 and not re.search(r"不是|非|不算|不要", text)
            else None
        )
        # Recognition positions refer to the immutable initial candidate, not a later
        # reviewer edit. Reusing those numeric positions would transfer model labels too.
        context_items_match = self._item_basis(candidate) == self._item_basis(
            draft["original_candidate"]
        )
        suggestions = (
            {item.item_position: item for item in context.items}
            if context and context_items_match
            else {}
        )
        named_positions = {
            position
            for position, item in enumerate(candidate["items"])
            if item["raw_name"] and item["raw_name"] in text
        }
        for position, item in enumerate(candidate["items"]):
            proposal = suggestions.get(position)
            name = item["raw_name"] or ""
            category = proposal.category if proposal else "unknown"
            category_source = "rule" if proposal and category != "unknown" else "unknown"
            if category == "unknown":
                if re.search(r"衛生紙|洗衣|洗碗|肥皂|牙膏|垃圾袋|清潔", name):
                    category, category_source = "household", "rule"
                elif re.search(r"飯|麵|茶|咖啡|飲|餐|餅|奶|便當|水果|蔬菜", name):
                    category, category_source = "food", "rule"
            item_meal = (
                explicit_meal if not named_positions or position in named_positions else None
            )
            if item_meal and category in ("food", "unknown"):
                category, category_source = "food", "notes"
            meal = "unknown" if category in ("food", "unknown") else "not_applicable"
            meal_source = "unknown" if meal == "unknown" else "rule"
            if category == "food" and item_meal:
                meal, meal_source = item_meal, "notes"
            elif category == "food" and document["transaction_time"]:
                hour = int(document["transaction_time"][:2])
                meal = (
                    "breakfast"
                    if 5 <= hour < 10
                    else "lunch"
                    if 11 <= hour < 14
                    else "dinner"
                    if 17 <= hour < 21
                    else "late_night"
                    if hour >= 21 or hour < 5
                    else "snack"
                )
                meal_source = "rule"
            document["items"].append(
                {
                    "item_position": position,
                    "category": category,
                    "subcategory": proposal.subcategory if proposal else None,
                    "meal": meal,
                    "meal_group": None,
                }
            )
            sources["items"].append(
                {
                    "item_position": position,
                    "category": category_source,
                    "subcategory": "rule" if proposal and proposal.subcategory else "unknown",
                    "meal": meal_source,
                    "meal_group": "unknown",
                }
            )
        return document, sources

    @staticmethod
    def _item_basis(candidate):
        return hashlib.sha256(canonical({"items": candidate["items"]}).encode()).hexdigest()

    def _item_changes(self, connection, draft, binding):
        """Count only item edits after this label save, including A -> B -> A edits."""
        basis = binding.item_basis
        current_basis = self._item_basis(draft["candidate"])
        if draft["revision"] == binding.candidate_revision:
            return int(current_basis != basis)
        if draft["revision"] < binding.candidate_revision:
            raise StoreError(503, "insight_binding_revision_invalid")
        changes = 0
        # Inspect only the review suffix since labels were saved, never the full history.
        # Confirm/void and purpose-only edits repeat the same item basis and add no change.
        rows = connection.execute(
            """SELECT candidate FROM reviews WHERE draft_id=? AND revision>? AND revision<=?
               ORDER BY revision""",
            (draft["id"], binding.candidate_revision, draft["revision"]),
        )
        for row in rows:
            next_basis = self._item_basis(json.loads(row["candidate"]))
            if next_basis != basis:
                changes += 1
                basis = next_basis
        if current_basis != basis:
            changes += 1
        return changes

    def draft_document(self, connection, draft, *, notes=None):
        row = connection.execute(
            "SELECT * FROM insight_annotations WHERE draft_id=?", (draft["id"],)
        ).fetchone()
        revision = 0
        if row:
            try:
                binding = StoredInsightDocument.model_validate(json.loads(row["document"]))
            except ValueError as error:
                raise StoreError(503, "insight_binding_invalid") from error
            document = binding.document.model_dump(mode="json")
            sources = json.loads(row["sources"])
            changes = self._item_changes(connection, draft, binding)
            revision = row["revision"] + changes
            if revision > LONG_MAX:
                raise StoreError(409, "revision_exhausted")
            if changes:
                # A numeric position is not an identity. Invalidate all item labels after
                # item edits, even if the candidate later returns to the original shape.
                current, current_sources = self._suggest(connection, draft, notes)
                document["items"] = current["items"]
                sources["items"] = current_sources["items"]
        else:
            document, sources = self._suggest(connection, draft, notes)
        return {
            "draft_id": draft["id"],
            "ledger_id": draft["ledger_id"],
            "revision": revision,
            "document": document,
            "sources": sources,
            "updated_at": row["updated_at"] if row else None,
        }

    def document(self, connection, entry):
        return self.draft_document(connection, entry["snapshot"])

    def get(self, target_id, ledger=False):
        with self.store.connection() as connection:
            return (
                self.document(connection, self.store._ledger(connection, target_id))
                if ledger
                else self.draft_document(connection, self._draft(connection, target_id))
            )

    def _draft(self, connection, draft_id):
        row = connection.execute("SELECT * FROM drafts WHERE id=?", (draft_id,)).fetchone()
        if row is None:
            raise StoreError(404, "draft_not_found")
        return self.store._draft(row)

    def _write(self, connection, draft, revision, document, sources):
        now = timestamp()
        draft_id = draft["id"]
        binding = StoredInsightDocument(
            candidate_revision=draft["revision"],
            item_basis=self._item_basis(draft["candidate"]),
            document=InsightDocument.model_validate(document),
        )
        encoded = canonical(binding.model_dump(mode="json", by_alias=True))
        provenance = canonical(sources)
        connection.execute(
            """INSERT INTO insight_annotations VALUES (?, ?, ?, ?, ?)
            ON CONFLICT(draft_id) DO UPDATE SET revision=excluded.revision,
            document=excluded.document,sources=excluded.sources,updated_at=excluded.updated_at""",
            (draft_id, revision, encoded, provenance, now),
        )
        connection.execute(
            "INSERT INTO insight_history VALUES (?, ?, ?, ?, ?, 'reviewer')",
            (draft_id, revision, encoded, provenance, now),
        )

    def save(self, target_id, request: SaveInsights, key, ledger=False):
        operation = "insight_ledger" if ledger else "insight_draft"
        with self.store.connection(write=True) as connection:
            replay, digest = self.store._replay(
                connection, operation, target_id, request.model_dump(mode="json"), key
            )
            if replay is not None:
                return replay
            if ledger:
                entry = self.store._ledger(connection, target_id)
                if entry["status"] != "active":
                    raise StoreError(409, "ledger_voided")
                draft = entry["snapshot"]
            else:
                draft = self._draft(connection, target_id)
                if draft["status"] == "voided":
                    raise StoreError(409, "ledger_voided")
            current = self.draft_document(connection, draft)
            if current["revision"] != request.expected_revision:
                raise StoreError(409, "revision_conflict")
            self._positions(request.document, draft["candidate"])
            if (
                draft["source"]["kind"] == "manual"
                and not draft["evidence"]
                and request.document.date_source == "receipt"
            ):
                raise StoreError(422, "manual_entry_has_no_receipt_date_evidence")
            document = request.document.model_dump(mode="json")
            self._write(
                connection,
                draft,
                current["revision"] + 1,
                document,
                self._sources(document, "user"),
            )
            value = self.draft_document(connection, draft)
            connection.execute(
                "INSERT INTO mutations VALUES (?, ?, ?, ?, ?)",
                (key, operation, target_id, digest, canonical(value)),
            )
            return value

    def create_manual(self, request: ManualEntry, key):
        body = request.model_dump(mode="json")
        with self.store.connection(write=True) as connection:
            replay, digest = self.store._replay(connection, "manual_entry", "manual", body, key)
            if replay is not None:
                return replay, False
            draft_id, ledger_id, now = str(uuid.uuid4()), str(uuid.uuid4()), timestamp()
            candidate = Candidate.model_validate(
                {
                    "schema": "pixelreceipt-central-candidate-1",
                    "currency": "TWD",
                    "amount_unit": "TWD_YUAN",
                    "merchant": request.merchant or "手動記帳",
                    "receipt_date": request.receipt_date,
                    "printed_total_yuan": request.amount_yuan,
                    "items": [
                        {
                            "raw_name": request.description,
                            "quantity": 1,
                            "printed_unit_price_yuan": request.amount_yuan,
                            "printed_line_total_yuan": request.amount_yuan,
                        }
                    ],
                    "adjustments": [],
                }
            )
            review = ReviewDocument(
                assembly_status="complete_by_user",
                line_decisions=[
                    LineDecision(
                        item_position=0, method="whole_line", whole_purpose=request.purpose
                    )
                ],
                adjustment_decisions=[],
            )
            encoded = canonical(candidate.model_dump(mode="json", by_alias=True))
            reviewed = canonical(review.model_dump(mode="json", by_alias=True))
            connection.execute(
                """INSERT INTO drafts (id,idempotency_key,content_digest,original,candidate,
                   evidence,source,revision,created_at,updated_at,stage,review_document,ledger_id)
                   VALUES (?,?,?,?,?,'[]',?,3,?,?,'confirmed',?,?)""",
                (
                    draft_id,
                    "manual:" + key,
                    hashlib.sha256(canonical(body).encode()).hexdigest(),
                    encoded,
                    encoded,
                    canonical(
                        {"kind": "manual", "label": "手動記帳", "provider": None, "model": None}
                    ),
                    now,
                    now,
                    reviewed,
                    ledger_id,
                ),
            )
            for revision, action in ((2, "reviewer"), (3, "confirmed")):
                connection.execute(
                    """INSERT INTO reviews (draft_id,revision,candidate,review_note,created_at,
                       review_document,action) VALUES (?,?,?,?,?,?,?)""",
                    (
                        draft_id,
                        revision,
                        encoded,
                        request.notes if revision == 2 else None,
                        now,
                        reviewed,
                        action,
                    ),
                )
            draft = self._draft(connection, draft_id)
            if not draft["calculation"]["ready"]:
                raise StoreError(422, "manual_calculation_not_ready")
            connection.execute(
                "INSERT INTO ledger VALUES (?,?,?,?, 'reviewer')",
                (ledger_id, draft_id, canonical(draft), now),
            )
            connection.execute(
                "INSERT INTO ledger_events VALUES (?,1,'confirmed','reviewer',NULL,?)",
                (ledger_id, now),
            )
            document = request.analysis.model_dump(mode="json")
            self._write(connection, draft, 1, document, self._sources(document, "user"))
            value = self.store._ledger(connection, ledger_id)
            connection.execute(
                "INSERT INTO mutations VALUES (?, 'manual_entry', 'manual', ?, ?)",
                (key, digest, canonical(value)),
            )
            return value, True

    def operation(self, key):
        with self.store.connection() as connection:
            row = connection.execute(
                "SELECT * FROM mutations WHERE idempotency_key=?", (key,)
            ).fetchone()
            if row is None or row["operation"] not in OPERATIONS:
                raise StoreError(404, "analysis_operation_not_found")
            value = json.loads(row["response"])
            target = (
                value["id"]
                if row["operation"] in ("manual_entry", "product_create")
                else row["target"]
            )
            return {
                "operation_id": key,
                "kind": row["operation"],
                "target_id": target,
                "result": value,
            }
