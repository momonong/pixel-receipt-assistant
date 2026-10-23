"""Human-review contract; v1 extraction candidates remain unchanged."""

from typing import Annotated, Literal

from pydantic import Field, StringConstraints, model_validator

from .models import INT_MAX, LONG_MAX, Candidate, Money, StrictModel

Position = Annotated[int, Field(ge=0, le=99)]
ReviewNote = Annotated[str, StringConstraints(max_length=1000)]
Purpose = Literal["self", "advance", "gift"]


class PurposeAmounts(StrictModel):
    self: Money
    advance: Money
    gift: Money


class PurposeQuantities(StrictModel):
    self: Annotated[int, Field(ge=0, le=INT_MAX)]
    advance: Annotated[int, Field(ge=0, le=INT_MAX)]
    gift: Annotated[int, Field(ge=0, le=INT_MAX)]


class LineDecision(StrictModel):
    item_position: Position
    method: Literal["whole_line", "by_quantity", "explicit_amounts"]
    whole_purpose: Purpose | None = None
    quantities: PurposeQuantities | None = None
    amounts_yuan: PurposeAmounts | None = None

    @model_validator(mode="after")
    def method_fields(self):
        if self.method == "whole_line":
            if (
                self.whole_purpose is None
                or self.quantities is not None
                or self.amounts_yuan is not None
            ):
                raise ValueError("whole-line decision requires only a purpose")
        else:
            if self.whole_purpose is not None or self.quantities is None:
                raise ValueError("split decisions require quantities and no whole purpose")
            if (self.method == "explicit_amounts") != (self.amounts_yuan is not None):
                raise ValueError("only explicit-amount decisions require amounts")
        return self


class AdjustmentDecision(StrictModel):
    adjustment_position: Annotated[int, Field(ge=0, le=49)]
    method: Literal["proportional_amount", "explicit_amounts"]
    amounts_yuan: PurposeAmounts | None = None

    @model_validator(mode="after")
    def method_fields(self):
        if (self.method == "explicit_amounts") != (self.amounts_yuan is not None):
            raise ValueError("only explicit-amount decisions require amounts")
        return self


class ReviewDocument(StrictModel):
    schema_version: Literal["pixelreceipt-central-review-2"] = Field(
        default="pixelreceipt-central-review-2", alias="schema"
    )
    assembly_status: Literal["unknown", "incomplete", "complete_by_user"]
    line_decisions: Annotated[list[LineDecision], Field(max_length=100)]
    adjustment_decisions: Annotated[list[AdjustmentDecision], Field(max_length=50)]
    tolerance_acknowledgement: Literal["none", "accept_within_tolerance"] = "none"


class ReviewReceipt(ReviewDocument):
    expected_revision: Annotated[int, Field(ge=1, lt=LONG_MAX)]
    candidate: Candidate
    review_note: ReviewNote | None = None

    @model_validator(mode="after")
    def references(self):
        positions = [decision.item_position for decision in self.line_decisions]
        if len(set(positions)) != len(positions) or any(
            position >= len(self.candidate.items) for position in positions
        ):
            raise ValueError("invalid line decisions")
        adjustments = [decision.adjustment_position for decision in self.adjustment_decisions]
        if len(set(adjustments)) != len(adjustments) or any(
            position >= len(self.candidate.adjustments) for position in adjustments
        ):
            raise ValueError("invalid adjustment decisions")
        if any(
            self.candidate.adjustments[position].amount_effect == "included_in_line_totals"
            for position in adjustments
        ):
            raise ValueError("included adjustments cannot be allocated again")
        return self

    def document(self) -> dict:
        return self.model_dump(
            mode="json", by_alias=True, exclude={"expected_revision", "candidate", "review_note"}
        )


class ConfirmReceipt(StrictModel):
    expected_revision: Annotated[int, Field(ge=1, lt=LONG_MAX)]


class VoidReceipt(StrictModel):
    expected_revision: Annotated[int, Field(ge=1, lt=LONG_MAX)]
    reason: Annotated[str, StringConstraints(min_length=1, max_length=1000)]

    @model_validator(mode="after")
    def reason_not_blank(self):
        if not self.reason.strip():
            raise ValueError("reason required")
        return self
