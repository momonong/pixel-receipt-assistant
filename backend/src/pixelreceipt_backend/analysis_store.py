"""Analytical annotations share SQLite transactions, never overwrite ledger snapshots."""

import hashlib
import json
import uuid
from fractions import Fraction

from .models import LONG_MAX, Candidate
from .pricing import InvalidPriceReview, price_lines
from .pricing_models import CreateProduct, PriceDocument, ReviewPrices
from .store import Store, StoreError, canonical, timestamp


class AnalysisStore:
    def __init__(self, store: Store):
        self.store = store

    @staticmethod
    def _product(row) -> dict:
        return {"id": row["id"], **json.loads(row["body"]), "created_at": row["created_at"]}

    def create_product(self, request: CreateProduct, key: str) -> tuple[dict, bool]:
        body = request.model_dump(mode="json")
        digest = hashlib.sha256(canonical(body).encode()).hexdigest()
        with self.store.connection(write=True) as connection:
            existing = connection.execute(
                "SELECT * FROM products WHERE idempotency_key = ?", (key,)
            ).fetchone()
            if existing:
                if existing["content_digest"] != digest:
                    raise StoreError(409, "idempotency_key_content_conflict")
                return self._product(existing), False
            product_id = str(uuid.uuid4())
            search = "\n".join(
                str(body[key])
                for key in ("display_name", "brand", "specification", "purchase_unit")
                if body[key] is not None
            ).casefold()
            connection.execute(
                "INSERT INTO products VALUES (?, ?, ?, ?, ?, ?)",
                (product_id, canonical(body), search, key, digest, timestamp()),
            )
            return self._product(
                connection.execute("SELECT * FROM products WHERE id = ?", (product_id,)).fetchone()
            ), True

    def list_products(self, query: str | None, limit: int, offset: int) -> dict:
        search = query.casefold() if query is not None else None
        with self.store.connection() as connection:
            count = connection.execute(
                "SELECT COUNT(*) FROM products WHERE (? IS NULL OR instr(search_text, ?) > 0)",
                (search, search),
            ).fetchone()[0]
            rows = connection.execute(
                """SELECT * FROM products WHERE (? IS NULL OR instr(search_text, ?) > 0)
                   ORDER BY created_at DESC, id LIMIT ? OFFSET ?""",
                (search, search, limit, offset),
            ).fetchall()
            return {
                "products": [self._product(row) for row in rows],
                "query": query,
                "limit": limit,
                "offset": offset,
                "total_count": count,
            }

    def _products(self, connection, product_ids) -> dict:
        result = {}
        for product_id in set(product_ids):
            row = connection.execute(
                "SELECT * FROM products WHERE id = ?", (product_id,)
            ).fetchone()
            if row is not None:
                result[product_id] = self._product(row)
        return result

    def _price_review(self, connection, ledger_id, *, ledger=None, products=None) -> dict:
        ledger = ledger or self.store._ledger(connection, ledger_id)
        row = connection.execute(
            "SELECT * FROM price_reviews WHERE ledger_id = ?", (ledger_id,)
        ).fetchone()
        document = (
            PriceDocument.model_validate(json.loads(row["document"]))
            if row
            else PriceDocument(item_mappings=[], adjustment_decisions=[])
        )
        products = (
            self._products(connection, [m.product_id for m in document.item_mappings])
            if products is None
            else products
        )
        try:
            lines, basis = price_lines(
                Candidate.model_validate(ledger["snapshot"]["candidate"]), document, products
            )
        except InvalidPriceReview as error:
            raise StoreError(503, "price_review_integrity_failure") from error
        if ledger["status"] != "active":
            for line in lines:
                line["comparison_eligible"] = False
                line["unknown_reasons"].append("ledger_voided")
        return {
            "ledger_id": ledger_id,
            "revision": row["revision"] if row else 0,
            **document.model_dump(mode="json"),
            "line_prices": lines,
            "basis_sha256": basis,
            "updated_at": row["updated_at"] if row else None,
            "actor": row["actor"] if row else None,
        }

    def get_price_review(self, ledger_id: str) -> dict:
        with self.store.connection() as connection:
            return self._price_review(connection, ledger_id)

    def review_prices(self, ledger_id: str, request: ReviewPrices) -> dict:
        with self.store.connection(write=True) as connection:
            ledger = self.store._ledger(connection, ledger_id)
            if ledger["status"] != "active":
                raise StoreError(409, "ledger_voided")
            row = connection.execute(
                "SELECT * FROM price_reviews WHERE ledger_id = ?", (ledger_id,)
            ).fetchone()
            revision = row["revision"] if row else 0
            if revision != request.expected_revision:
                raise StoreError(409, "revision_conflict")
            document = PriceDocument.model_validate(request.document())
            try:
                price_lines(
                    Candidate.model_validate(ledger["snapshot"]["candidate"]),
                    document,
                    self._products(connection, [m.product_id for m in document.item_mappings]),
                    reject_invalid=True,
                )
            except InvalidPriceReview as error:
                raise StoreError(422, error.code) from error
            now = timestamp()
            encoded = canonical(document.model_dump(mode="json"))
            connection.execute(
                """INSERT INTO price_reviews VALUES (?, ?, ?, ?, 'reviewer')
                   ON CONFLICT(ledger_id) DO UPDATE SET revision = excluded.revision,
                   document = excluded.document, updated_at = excluded.updated_at,
                   actor = excluded.actor""",
                (ledger_id, revision + 1, encoded, now),
            )
            connection.execute(
                "INSERT INTO price_review_history VALUES (?, ?, ?, ?, 'reviewer')",
                (ledger_id, revision + 1, encoded, now),
            )
            return self._price_review(connection, ledger_id, ledger=ledger)

    def price_history(self, ledger_id: str) -> dict:
        with self.store.connection() as connection:
            self.store._ledger(connection, ledger_id)
            rows = connection.execute(
                "SELECT * FROM price_review_history WHERE ledger_id = ? ORDER BY revision",
                (ledger_id,),
            ).fetchall()
            return {
                "revisions": [
                    {
                        "revision": row["revision"],
                        **json.loads(row["document"]),
                        "actor": row["actor"],
                        "created_at": row["created_at"],
                    }
                    for row in rows
                ]
            }

    def _filtered_ledger(self, connection, *, q=None, date_from=None, date_to=None, status=None):
        # Complete-scope filters run before pagination. Search is literal, never SQL or globbing.
        search = q.casefold() if q is not None else None
        rows = connection.execute("SELECT id FROM ledger ORDER BY confirmed_at DESC, id")
        for row in rows:
            entry = self.store._ledger(connection, row["id"])
            candidate = entry["snapshot"]["candidate"]
            receipt_date = candidate["receipt_date"]
            if status is not None and entry["status"] != status:
                continue
            if date_from is not None and (receipt_date is None or receipt_date < date_from):
                continue
            if date_to is not None and (receipt_date is None or receipt_date > date_to):
                continue
            haystack = "\n".join(
                [
                    candidate["merchant"] or "",
                    *[item["raw_name"] or "" for item in candidate["items"]],
                ]
            ).casefold()
            if search is not None and search not in haystack:
                continue
            yield entry

    def list_ledger(
        self, limit, offset, *, q=None, date_from=None, date_to=None, status=None
    ) -> dict:
        with self.store.connection() as connection:
            entries = self._filtered_ledger(
                connection, q=q, date_from=date_from, date_to=date_to, status=status
            )
            page = []
            count = 0
            for entry in entries:
                if offset <= count < offset + limit:
                    page.append(entry)
                count += 1
            return {
                "entries": page,
                "limit": limit,
                "offset": offset,
                "total_count": count,
                "q": q,
                "date_from": date_from,
                "date_to": date_to,
                "status": status,
            }

    def overview(self, date_from=None, date_to=None) -> dict:
        keys = (
            "printed_total_yuan",
            "allocated_total_yuan",
            "self_yuan",
            "gift_yuan",
            "advance_yuan",
            "personal_yuan",
            "difference_yuan",
        )
        totals = dict.fromkeys(keys, 0)
        totals["explicit_discount_yuan"] = 0
        with self.store.connection() as connection:
            entries = self._filtered_ledger(
                connection, date_from=date_from, date_to=date_to, status="active"
            )
            count = 0
            for entry in entries:
                count += 1
                snapshot = entry["snapshot"]
                for key in keys:
                    totals[key] += snapshot["calculation"][key]
                totals["explicit_discount_yuan"] += sum(
                    adjustment["amount_yuan"]
                    for adjustment in snapshot["candidate"]["adjustments"]
                    if adjustment["direction"] == "subtract"
                )
            if any(abs(amount) > LONG_MAX for amount in totals.values()):
                raise StoreError(422, "summary_amount_out_of_range")
            return {
                "scope": "active_confirmed_ledger",
                "date_from": date_from,
                "date_to": date_to,
                "receipt_count": count,
                **totals,
            }

    def product_prices(
        self, product_id: str, limit: int, offset: int, date_from=None, date_to=None
    ) -> dict:
        with self.store.connection() as connection:
            row = connection.execute(
                "SELECT * FROM products WHERE id = ?", (product_id,)
            ).fetchone()
            if row is None:
                raise StoreError(404, "product_not_found")
            product = self._product(row)
            entries = self._filtered_ledger(
                connection, date_from=date_from, date_to=date_to, status="active"
            )
            page = []
            count = 0
            lowest = None
            lowest_key = None
            for entry in entries:
                review = self._price_review(connection, entry["id"], ledger=entry)
                candidate = entry["snapshot"]["candidate"]
                for line in review["line_prices"]:
                    if line["product_id"] != product_id:
                        continue
                    observation = {
                        **line,
                        "ledger_id": entry["id"],
                        "receipt_date": candidate["receipt_date"],
                        "merchant": candidate["merchant"],
                        "price_review_revision": review["revision"],
                    }
                    if offset <= count < offset + limit:
                        page.append(observation)
                    count += 1
                    if line["comparison_eligible"]:
                        key = (
                            Fraction(line["paid_line_total_yuan"], line["quantity"]),
                            observation["receipt_date"],
                            observation["ledger_id"],
                            observation["item_position"],
                        )
                        if lowest_key is None or key < lowest_key:
                            lowest, lowest_key = observation, key
            return {
                "product": product,
                "observations": page,
                "lowest_paid_observation": lowest,
                "date_from": date_from,
                "date_to": date_to,
                "limit": limit,
                "offset": offset,
                "total_count": count,
            }
