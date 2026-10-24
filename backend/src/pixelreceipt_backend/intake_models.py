"""Immutable mobile intake; notes are input, never reviewer or provenance labels."""

import uuid
from typing import Annotated, Literal

from pydantic import AfterValidator, Field, StringConstraints, model_validator

from .models import LONG_MAX, Sha256, StrictModel, nonblank


def device_uuid(value: str) -> str:
    if str(uuid.UUID(value)) != value:
        raise ValueError("canonical UUID required")
    return value


DeviceId = Annotated[str, AfterValidator(device_uuid)]


class MobileLogin(StrictModel):
    device_id: DeviceId
    device_name: Annotated[
        str, StringConstraints(min_length=1, max_length=100), AfterValidator(nonblank)
    ]
    password: Annotated[str, StringConstraints(min_length=1, max_length=1024)]


class IntakeEvidence(StrictModel):
    sha256: Sha256
    role: Literal["receipt", "price_tag", "promotion", "menu", "other"]
    notes: Annotated[str, StringConstraints(max_length=1000)] | None


class CreateIntake(StrictModel):
    schema_version: Literal["pixelreceipt-mobile-ingest-1"] = Field(alias="schema")
    purpose: Literal["purchase", "reference"]
    engine: Literal["broker", "local"]
    user_notes: Annotated[str, StringConstraints(max_length=4000)] | None
    evidence: Annotated[list[IntakeEvidence], Field(min_length=1, max_length=20)]

    @model_validator(mode="after")
    def grouping(self):
        if len({entry.sha256 for entry in self.evidence}) != len(self.evidence):
            raise ValueError("duplicate evidence")
        if self.purpose == "purchase" and not any(e.role == "receipt" for e in self.evidence):
            raise ValueError("purchase requires receipt")
        return self


class DeleteIntake(StrictModel):
    # Required null distinguishes no linked draft from a stale/missing revision.
    expected_draft_revision: Annotated[int, Field(ge=1, lt=LONG_MAX)] | None
    reason: Annotated[
        str, StringConstraints(min_length=1, max_length=1000), AfterValidator(nonblank)
    ]
