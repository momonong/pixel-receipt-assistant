"""Revisioned analytical labels, separate from immutable financial candidates."""

from datetime import date
from typing import Annotated, Literal

from pydantic import BeforeValidator, Field, StringConstraints, model_validator

from .ledger_models import Position, Purpose
from .models import LONG_MAX, Sha256, StrictModel


def optional_text(value):
    return value.strip() or None if isinstance(value, str) else value


Text = Annotated[str, StringConstraints(max_length=500), BeforeValidator(optional_text)]
SmallText = Annotated[str, StringConstraints(max_length=100), BeforeValidator(optional_text)]
# Apply normalization outside the nullable union so whitespace can become None.
NullableText = Annotated[Text | None, BeforeValidator(optional_text)]
NullableSmallText = Annotated[SmallText | None, BeforeValidator(optional_text)]
Category = Literal[
    "food",
    "clothing",
    "housing",
    "transport",
    "education",
    "leisure",
    "health",
    "household",
    "other",
    "unknown",
]
Meal = Literal["breakfast", "lunch", "dinner", "late_night", "snack", "unknown", "not_applicable"]
Time = Annotated[str, StringConstraints(pattern=r"^(?:[01][0-9]|2[0-3]):[0-5][0-9]$")]


class InsightItem(StrictModel):
    item_position: Position
    category: Category = "unknown"
    subcategory: NullableSmallText = None
    meal: Meal = "unknown"
    meal_group: NullableSmallText = None


class InsightDocument(StrictModel):
    brand: NullableText = None
    branch: NullableText = None
    address: NullableText = None
    city: NullableText = None
    district: NullableText = None
    channel: Literal["in_store", "online", "unknown"] = "unknown"
    transaction_time: Annotated[Time | None, BeforeValidator(optional_text)] = None
    date_source: Literal["receipt", "user", "default_today", "unknown"] = "unknown"
    items: Annotated[list[InsightItem], Field(max_length=100)]

    @model_validator(mode="after")
    def unique_positions(self):
        positions = [item.item_position for item in self.items]
        if len(positions) != len(set(positions)):
            raise ValueError("duplicate item positions")
        return self


class StoredInsightDocument(StrictModel):
    """Private schema5 envelope; positions are valid only for this reviewed item basis."""

    schema_version: Literal["pixelreceipt-insight-binding-1"] = Field(
        default="pixelreceipt-insight-binding-1", alias="schema"
    )
    candidate_revision: Annotated[int, Field(ge=1, le=LONG_MAX)]
    item_basis: Sha256
    document: InsightDocument


class SaveInsights(StrictModel):
    expected_revision: Annotated[int, Field(ge=0, lt=LONG_MAX)]
    document: InsightDocument


class ManualEntry(StrictModel):
    receipt_date: Annotated[str, StringConstraints(pattern=r"^[0-9]{4}-[0-9]{2}-[0-9]{2}$")]
    merchant: NullableText = None
    description: NullableText = None
    amount_yuan: Annotated[int, Field(gt=0, le=LONG_MAX)]
    purpose: Purpose
    notes: NullableText = None
    analysis: InsightDocument

    @model_validator(mode="after")
    def validate_relations(self):
        date.fromisoformat(self.receipt_date)
        if self.analysis.date_source == "receipt":
            raise ValueError("manual entries have no receipt date evidence")
        if [item.item_position for item in self.analysis.items] != [0]:
            raise ValueError("manual entry requires exactly item position zero")
        return self


class RecognitionAnalysisContext(StrictModel):
    """Printed text may be proposed; semantic categories are explicitly suggestions."""

    printed_brand: NullableText = None
    printed_branch: NullableText = None
    printed_address: NullableText = None
    printed_city: NullableText = None
    printed_district: NullableText = None
    printed_transaction_time: Annotated[Time | None, BeforeValidator(optional_text)] = None
    channel: Literal["in_store", "online", "unknown"] = "unknown"
    items: Annotated[list[InsightItem], Field(max_length=100)] = []
