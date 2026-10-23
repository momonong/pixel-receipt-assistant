"""Independent human product identities and analytical price annotations."""

from typing import Annotated, Literal

from pydantic import Field, StringConstraints, model_validator

from .ledger_models import Position
from .models import LONG_MAX, ContentQuantity, Money, ShortText, StrictModel

ProductId = Annotated[
    str, StringConstraints(pattern=r"^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$")
]


class CreateProduct(StrictModel):
    display_name: ShortText
    brand: ShortText | None = None
    specification: ShortText
    purchase_unit: ShortText
    content_quantity: ContentQuantity | None = None


class ItemMapping(StrictModel):
    item_position: Position
    product_id: ProductId
    unit_match: Literal["confirmed_same_product_and_unit"]


class ItemPriceAllocation(StrictModel):
    item_position: Position
    amount_yuan: Money


class PriceAdjustmentDecision(StrictModel):
    adjustment_position: Annotated[int, Field(ge=0, le=49)]
    method: Literal["proportional_line_amount", "explicit_amounts"]
    amounts_by_item: (
        Annotated[list[ItemPriceAllocation], Field(min_length=1, max_length=100)] | None
    ) = None

    @model_validator(mode="after")
    def validate_method(self):
        if (self.method == "explicit_amounts") != (self.amounts_by_item is not None):
            raise ValueError("only explicit allocation requires item amounts")
        if self.amounts_by_item is not None:
            positions = [allocation.item_position for allocation in self.amounts_by_item]
            if len(set(positions)) != len(positions):
                raise ValueError("duplicate allocation positions")
        return self


class PriceDocument(StrictModel):
    item_mappings: Annotated[list[ItemMapping], Field(max_length=100)]
    adjustment_decisions: Annotated[list[PriceAdjustmentDecision], Field(max_length=50)]

    @model_validator(mode="after")
    def unique_positions(self):
        positions = [mapping.item_position for mapping in self.item_mappings]
        adjustments = [decision.adjustment_position for decision in self.adjustment_decisions]
        if len(set(positions)) != len(positions) or len(set(adjustments)) != len(adjustments):
            raise ValueError("duplicate analytical decisions")
        return self


class ReviewPrices(PriceDocument):
    expected_revision: Annotated[int, Field(ge=0, lt=LONG_MAX)]

    def document(self) -> dict:
        return self.model_dump(mode="json", exclude={"expected_revision"})
