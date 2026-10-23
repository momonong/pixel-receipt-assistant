"""Real Store v3/v4 round trip; historical filename retained. Synthetic data only."""

import importlib.util
import io
import json
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[2] / "deploy" / "receipt_backup.py"
SPEC = importlib.util.spec_from_file_location("receipt_backup_store", SCRIPT)
backup = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(backup)
BACKEND_INSTALLED = importlib.util.find_spec("pixelreceipt_backend") is not None


@unittest.skipUnless(
    BACKEND_INSTALLED, "run using an installed v3 or v4 backend Python environment"
)
class RealStoreBackupTests(unittest.TestCase):
    def test_full_store_round_trip_preserves_analysis_and_immutable_ledger(self):
        from PIL import Image
        from pixelreceipt_backend.analysis_store import AnalysisStore
        from pixelreceipt_backend.evidence import inspect_evidence
        from pixelreceipt_backend.ledger_models import (
            ConfirmReceipt,
            ReviewReceipt,
            VoidReceipt,
        )
        from pixelreceipt_backend.models import CreateDraft
        from pixelreceipt_backend.pricing_models import CreateProduct, ReviewPrices
        from pixelreceipt_backend.store import SCHEMA_VERSION, Store, StoreError

        self.assertIn(
            SCHEMA_VERSION, (3, 4), "round trip supports actual v3/v4 Store only"
        )
        with tempfile.TemporaryDirectory(
            prefix="pixelreceipt-store-backup-synthetic-"
        ) as temporary:
            private = Path(temporary)
            private.chmod(0o700)
            store = Store(private / "live")
            with store.connection() as connection:
                self.assertEqual(
                    connection.execute("PRAGMA user_version").fetchone()[0],
                    SCHEMA_VERSION,
                )
            stream = io.BytesIO()
            Image.new("RGB", (3, 2), (11, 22, 33)).save(stream, format="PNG")
            content = stream.getvalue()
            evidence = store.put_evidence(
                content, inspect_evidence(content, "image/png")
            )
            for name in backup.AUTH_FILES:
                path = store.data_dir / name
                path.write_text("synthetic-private-value-" + name)
                path.chmod(0o600)
            session_token = "synthetic-session-opaque-value"
            store.create_session(session_token, "synthetic-csrf-value")

            def create_receipt(key, *, second=False):
                candidate = {
                    "schema": "pixelreceipt-central-candidate-1",
                    "currency": "TWD",
                    "amount_unit": "TWD_YUAN",
                    "merchant": "合成乙店" if second else "合成甲店",
                    "receipt_date": "2026-10-19" if second else "2026-10-20",
                    "printed_total_yuan": 100 if second else 180,
                    "items": [
                        {
                            "raw_name": "合成茶飲",
                            "quantity": 2,
                            "printed_line_total_yuan": 100 if second else 120,
                        }
                    ],
                    "adjustments": [],
                }
                if not second:
                    candidate["items"].append(
                        {
                            "raw_name": "合成肥皂",
                            "quantity": 1,
                            "printed_line_total_yuan": 80,
                        }
                    )
                    candidate["adjustments"] = [
                        {
                            "raw_label": "合成整單折扣",
                            "direction": "subtract",
                            "amount_yuan": 20,
                            "scope": "receipt",
                            "item_positions": [],
                            "amount_effect": "separate_from_line_totals",
                        }
                    ]
                draft, created = store.create_draft(
                    CreateDraft.model_validate(
                        {
                            "evidence": [{"sha256": evidence["sha256"], "pages": [1]}],
                            "source": {
                                "kind": "ai_candidate",
                                "label": "SYNTHETIC backup integration",
                            },
                            "candidate": candidate,
                        }
                    ),
                    key,
                )
                self.assertTrue(created)
                decisions = [
                    {
                        "item_position": 0,
                        "method": "whole_line",
                        "whole_purpose": "advance" if second else "self",
                    }
                ]
                if not second:
                    decisions.append(
                        {
                            "item_position": 1,
                            "method": "whole_line",
                            "whole_purpose": "gift",
                        }
                    )
                reviewed = store.review_receipt(
                    draft["id"],
                    ReviewReceipt.model_validate(
                        {
                            "expected_revision": 1,
                            "candidate": candidate,
                            "assembly_status": "complete_by_user",
                            "line_decisions": decisions,
                            "adjustment_decisions": []
                            if second
                            else [
                                {
                                    "adjustment_position": 0,
                                    "method": "proportional_amount",
                                }
                            ],
                            "tolerance_acknowledgement": "none",
                        }
                    ),
                )
                self.assertTrue(reviewed["calculation"]["ready"])
                ledger, created = store.confirm(
                    draft["id"], ConfirmReceipt(expected_revision=2), key + "-confirm"
                )
                self.assertTrue(created)
                return draft["id"], ledger

            draft_id, active = create_receipt("synthetic-first")
            _, later_voided = create_receipt("synthetic-second", second=True)
            original_snapshot = json.dumps(active["snapshot"], sort_keys=True)
            analysis = AnalysisStore(store)
            tea, _ = analysis.create_product(
                CreateProduct(
                    display_name="合成茶500mL",
                    purchase_unit="瓶",
                    brand="合成品牌",
                    specification="500mL",
                ),
                "synthetic-tea",
            )
            soap, _ = analysis.create_product(
                CreateProduct(
                    display_name="合成肥皂100g",
                    specification="100g",
                    purchase_unit="塊",
                ),
                "synthetic-soap",
            )
            mappings = [
                {
                    "item_position": position,
                    "product_id": product["id"],
                    "unit_match": "confirmed_same_product_and_unit",
                }
                for position, product in enumerate((tea, soap))
            ]
            first_review = {
                "expected_revision": 0,
                "item_mappings": mappings,
                "adjustment_decisions": [
                    {"adjustment_position": 0, "method": "proportional_line_amount"}
                ],
            }
            analysis.review_prices(
                active["id"], ReviewPrices.model_validate(first_review)
            )
            first_review["expected_revision"] = 1
            first_review["adjustment_decisions"] = [
                {
                    "adjustment_position": 0,
                    "method": "explicit_amounts",
                    "amounts_by_item": [
                        {"item_position": 0, "amount_yuan": 12},
                        {"item_position": 1, "amount_yuan": 8},
                    ],
                }
            ]
            analysis.review_prices(
                active["id"], ReviewPrices.model_validate(first_review)
            )
            analysis.review_prices(
                later_voided["id"],
                ReviewPrices.model_validate(
                    {
                        "expected_revision": 0,
                        "item_mappings": mappings[:1],
                        "adjustment_decisions": [],
                    }
                ),
            )
            self.assertEqual(
                analysis.product_prices(tea["id"], 20, 0)["total_count"], 2
            )
            store.void(
                later_voided["id"],
                VoidReceipt(expected_revision=1, reason="SYNTHETIC void"),
                "synthetic-void",
            )
            self.assertEqual(
                json.dumps(store.get_ledger(active["id"])["snapshot"], sort_keys=True),
                original_snapshot,
            )
            self.assertEqual(analysis.overview()["receipt_count"], 1)
            self.assertEqual(
                analysis.product_prices(tea["id"], 20, 0)["total_count"], 1
            )

            def table_rows(database_store):
                with database_store.connection() as connection:
                    tables = [
                        row[0]
                        for row in connection.execute(
                            "SELECT name FROM sqlite_master WHERE type='table' "
                            "AND name NOT LIKE 'sqlite_%' ORDER BY name"
                        )
                    ]
                    return {
                        table: [
                            tuple(row)
                            for row in connection.execute(
                                'SELECT * FROM "'
                                + table.replace('"', '""')
                                + '" ORDER BY rowid'
                            )
                        ]
                        for table in tables
                        if table != "sessions"
                    }

            before = table_rows(store)
            self.assertTrue(
                {
                    "products",
                    "price_reviews",
                    "price_review_history",
                    "ledger",
                    "ledger_events",
                }
                <= set(before)
            )
            self.assertEqual(len(before["price_review_history"]), 3)
            expected_overview = analysis.overview()
            expected_prices = analysis.product_prices(tea["id"], 20, 0)
            expected_history = analysis.price_history(active["id"])
            expected_void = store.get_ledger(later_voided["id"])
            snapshot = private / "snapshot"
            restored = private / "restored"
            self.assertEqual(
                backup.snapshot(store.data_dir, snapshot)["database_version"],
                SCHEMA_VERSION,
            )
            verification = backup.verify(snapshot)
            self.assertEqual(verification["database_version"], SCHEMA_VERSION)
            self.assertEqual(verification["evidence_count"], 1)
            restoration = backup.restore(snapshot, restored)
            self.assertEqual(restoration["database_version"], SCHEMA_VERSION)
            self.assertTrue(restoration["sessions_invalidated"])
            reopened = Store(restored)
            with reopened.connection() as connection:
                self.assertEqual(
                    connection.execute("PRAGMA user_version").fetchone()[0],
                    SCHEMA_VERSION,
                )
            recovered_analysis = AnalysisStore(reopened)
            self.assertEqual(table_rows(reopened), before)
            self.assertEqual(recovered_analysis.overview(), expected_overview)
            self.assertEqual(
                recovered_analysis.product_prices(tea["id"], 20, 0), expected_prices
            )
            self.assertEqual(
                recovered_analysis.price_history(active["id"]), expected_history
            )
            self.assertEqual(reopened.get_ledger(later_voided["id"]), expected_void)
            self.assertEqual(
                json.dumps(
                    reopened.get_ledger(active["id"])["snapshot"], sort_keys=True
                ),
                original_snapshot,
            )
            self.assertEqual(
                reopened.get_evidence(evidence["sha256"], content=True)[1], content
            )
            self.assertIsNone(reopened.get_session(session_token))
            self.assertIsNotNone(store.get_session(session_token))
            self.assertFalse((restored / "INCOMPLETE").exists())
            self.assertFalse((snapshot / "INCOMPLETE").exists())
            for name in backup.AUTH_FILES:
                self.assertEqual(
                    (restored / name).read_bytes(), (store.data_dir / name).read_bytes()
                )
            with self.assertRaises(StoreError) as blocked:
                reopened.review_receipt(
                    draft_id,
                    ReviewReceipt.model_validate(
                        {
                            "expected_revision": reopened.get_draft(draft_id)[
                                "revision"
                            ],
                            "candidate": active["snapshot"]["candidate"],
                            "assembly_status": "complete_by_user",
                            "line_decisions": [],
                            "adjustment_decisions": [],
                        }
                    ),
                )
            self.assertEqual(blocked.exception.status, 409)
            self.assertEqual(
                json.dumps(
                    reopened.get_ledger(active["id"])["snapshot"], sort_keys=True
                ),
                original_snapshot,
            )


if __name__ == "__main__":
    unittest.main()
