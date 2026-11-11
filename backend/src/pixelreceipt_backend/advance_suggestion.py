"""Conservative draft defaults from intake notes, separate from receipt facts."""

import re
import unicodedata

from .domain import calculate
from .ledger_models import AdjustmentDecision, LineDecision, ReviewDocument
from .models import Candidate

ADVANCE_PHRASE = r"(?:(?:幫|替)(?:別人|朋友|同事|人)(?:代)?買|幫忙代買|代買|代墊)"
SUFFIX_NOTE = re.compile(rf"(.+?)\s*(?:是|:)?\s*{ADVANCE_PHRASE}(?:的)?")
PREFIX_NOTE = re.compile(r"(?:代買|代墊)(?:\s*:\s*|\s+)(.+)")
NOTE_BOUNDARY = re.compile(r"[。\r\n；;]+")
# Broader than the exact advance grammar so uncertain purpose statements veto
# a preview instead of silently defaulting their items to personal spend.
PURPOSE_WORD = re.compile(
    rf"{ADVANCE_PHRASE}|代付|自用|自己|我的|送人|贈送|分攤|"
    r"(?:幫|替)[^,，?!？！]{0,24}買"
)


def _normalized(text: str) -> str:
    return " ".join(unicodedata.normalize("NFKC", text).split())


def _target(note: str) -> str | None:
    match = PREFIX_NOTE.fullmatch(note) or SUFFIX_NOTE.fullmatch(note)
    if match is None:
        return None
    name = match[1].strip()
    if name.startswith("「") and name.endswith("」"):
        name = name[1:-1].strip()
    return name


def suggest_advance(candidate: Candidate, notes: list[str | None]) -> dict | None:
    names: dict[str, list[int]] = {}
    for position, item in enumerate(candidate.items):
        if item.raw_name:
            names.setdefault(_normalized(item.raw_name), []).append(position)
    positions: set[int] = set()
    for text in notes:
        for sentence in NOTE_BOUNDARY.split(text or ""):
            sentence = _normalized(sentence).rstrip(".!").rstrip()
            target = _target(sentence)
            if target is None:
                if PURPOSE_WORD.search(sentence):
                    # An unparsed purpose statement can contradict a clear one.
                    # Keep the entire estimate for human review in that case.
                    return None
                continue
            matches = names.get(target, [])
            if len(matches) != 1 or candidate.items[matches[0]].quantity != 1:
                return None
            positions.add(matches[0])
    if not positions:
        return None

    document = ReviewDocument(
        assembly_status="unknown",
        line_decisions=[
            LineDecision(
                item_position=i,
                method="whole_line",
                whole_purpose="advance" if i in positions else "self",
            )
            for i in range(len(candidate.items))
        ],
        adjustment_decisions=[
            AdjustmentDecision(adjustment_position=i, method="proportional_amount")
            for i, adjustment in enumerate(candidate.adjustments)
            if adjustment.amount_effect == "separate_from_line_totals"
        ],
    )
    totals = calculate(candidate, document)
    balanced = totals["reconciliation_status"] == "balanced" and totals["expenses_ready"]
    return {
        "item_positions": sorted(positions),
        "personal_yuan": totals["personal_yuan"] if balanced else None,
        "advance_yuan": totals["advance_yuan"] if balanced else None,
    }
