"""Prompt output schema; deterministic validation still treats every intent as untrusted."""

from typing import Annotated, Literal

from pydantic import Field, StringConstraints

from .insight_models import RecognitionAnalysisContext
from .models import Candidate, ShortText, StrictModel

NoteQuote = Annotated[str, StringConstraints(min_length=1, max_length=4000)]


class NoteIntent(StrictModel):
    item_position: Annotated[int, Field(ge=0, le=99)]
    item_name: ShortText
    purpose: Literal["self", "advance", "gift"]
    note_quote: NoteQuote
    whole_line: bool


class RecognitionDocument(StrictModel):
    candidate: Candidate
    note_intents: Annotated[list[NoteIntent], Field(max_length=100)]
    unresolved_note_quotes: Annotated[list[NoteQuote], Field(max_length=100)]
    analysis_context: RecognitionAnalysisContext | None = None
