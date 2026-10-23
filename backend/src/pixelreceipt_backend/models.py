"""External v1 contract, deliberately separate from Android's private DraftCodec."""

from datetime import date
from typing import Annotated, Literal

from pydantic import (
    AfterValidator,
    BaseModel,
    ConfigDict,
    Field,
    StringConstraints,
    model_validator,
)

LONG_MAX = 2**63 - 1
INT_MAX = 2**31 - 1
Money = Annotated[int, Field(ge=0, le=LONG_MAX)]
Quantity = Annotated[int, Field(ge=1, le=INT_MAX)]


def nonblank(value: str) -> str:
    if not value.strip():
        raise ValueError("unknown text must be null, not whitespace")
    return value


ShortText = Annotated[
    str, StringConstraints(min_length=1, max_length=500), AfterValidator(nonblank)
]
Sha256 = Annotated[str, StringConstraints(pattern=r"^[0-9a-f]{64}$")]


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True, frozen=True)


class ContentQuantity(StrictModel):
    # A printed package size, not an inferred purchase quantity or floating-point money.
    value_decimal: Annotated[
        str, StringConstraints(pattern=r"^(0|[1-9][0-9]{0,8})(\.[0-9]{1,6})?$")
    ]
    unit: Literal["g", "kg", "ml", "l", "piece"]


class Item(StrictModel):
    raw_name: ShortText | None
    quantity: Quantity | None
    printed_unit_price_yuan: Money | None = None
    printed_line_total_yuan: Money | None
    brand: ShortText | None = None
    specification: ShortText | None = None
    content_quantity: ContentQuantity | None = None


class Adjustment(StrictModel):
    raw_label: ShortText | None
    direction: Literal["add", "subtract"] | None
    amount_yuan: Money | None
    amount_effect: Literal["separate_from_line_totals", "included_in_line_totals"] | None = None
    scope: Literal["receipt", "items"] | None
    item_positions: Annotated[list[Annotated[int, Field(ge=0, le=99)]], Field(max_length=100)] = []

    @model_validator(mode="after")
    def validate_scope(self):
        if self.scope != "items" and self.item_positions:
            raise ValueError("item_positions requires items scope")
        if self.scope == "items" and not self.item_positions:
            raise ValueError("unknown item relation must use null scope")
        if len(set(self.item_positions)) != len(self.item_positions):
            raise ValueError("duplicate item positions")
        return self


class Candidate(StrictModel):
    schema_version: Literal["pixelreceipt-central-candidate-1"] = Field(alias="schema")
    currency: Literal["TWD"]
    amount_unit: Literal["TWD_YUAN"]
    merchant: ShortText | None
    receipt_date: Annotated[str, StringConstraints(pattern=r"^[0-9]{4}-[0-9]{2}-[0-9]{2}$")] | None
    printed_total_yuan: Money | None
    items: Annotated[list[Item], Field(max_length=100)]
    adjustments: Annotated[list[Adjustment], Field(max_length=50)]

    @model_validator(mode="after")
    def validate_relations(self):
        if self.receipt_date is not None:
            date.fromisoformat(self.receipt_date)
        for adjustment in self.adjustments:
            if any(position >= len(self.items) for position in adjustment.item_positions):
                raise ValueError("adjustment refers to an absent item")
        return self


class EvidenceReference(StrictModel):
    sha256: Sha256
    # Deliberate grouping by the caller. PDF pages do not imply separate/merged transactions.
    pages: Annotated[list[Annotated[int, Field(ge=1, le=100)]], Field(min_length=1, max_length=100)]

    @model_validator(mode="after")
    def unique_pages(self):
        if len(set(self.pages)) != len(self.pages):
            raise ValueError("duplicate pages")
        return self


class Source(StrictModel):
    kind: Literal["manual", "imported", "ai_candidate"]
    label: ShortText | None = None
    provider: ShortText | None = None
    model: ShortText | None = None


class CreateDraft(StrictModel):
    evidence: Annotated[list[EvidenceReference], Field(min_length=1, max_length=20)]
    source: Source
    candidate: Candidate

    @model_validator(mode="after")
    def unique_evidence(self):
        hashes = [reference.sha256 for reference in self.evidence]
        if len(set(hashes)) != len(hashes):
            raise ValueError("group pages of the same evidence into one reference")
        return self


class ReviewDraft(StrictModel):
    expected_revision: Annotated[int, Field(ge=1, lt=LONG_MAX)]
    candidate: Candidate
    review_note: Annotated[str, StringConstraints(max_length=1000)] | None = None
