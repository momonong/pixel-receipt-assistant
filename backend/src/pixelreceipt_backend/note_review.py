"""Validate untrusted semantic purpose proposals without authoring ledger values."""

import re

from .advance_suggestion import ADVANCE_PHRASE, PURPOSE_WORD, _normalized, _target
from .models import Candidate

CLAUSE = re.compile(r"[。\r\n；;，,！？!?]+")
NEGATION = re.compile(r"不是|並非|并非|不要|不算|不用|非代買|非代墊|沒有|没|別算")
PARTIAL = re.compile(r"其中|一部分|部分|一半|其餘|剩下|另一|各[一二兩三四五六七八九十\d]")
UNIT_COUNT = re.compile(r"([一二兩三四五六七八九十\d]+)(?:份|個|件|杯|碗|包|瓶|盒)")
ALL_UNITS = re.compile(r"全部|全都|整列|都是|都幫|都替|都代買|都送")
PURPOSE_EVIDENCE = {
    "advance": re.compile(rf"{ADVANCE_PHRASE}|代付|(?:幫|替)[^，,。；;]{{0,24}}買"),
    "self": re.compile(r"自用|自己|我的|我吃|我喝|本人"),
    "gift": re.compile(r"送人|送給|送禮|禮物|贈送|贈給"),
}
INTENT_KEYS = {"item_position", "item_name", "purpose", "note_quote", "whole_line"}
PURPOSES = {"self", "advance", "gift"}


def build_note_review(
    candidate: Candidate, notes: list[str | None], model_review: dict | None = None
) -> dict:
    """Return editable proposals, exact source quotes and visible unresolved notes.

    A model is allowed to map natural language to extracted items. It cannot pick
    an absent/duplicate item, fabricate a quote, split a line, or decide money.
    Legacy results use only the previous narrow whole-line advance grammar.
    """
    sources = [note for note in notes if isinstance(note, str) and note.strip()]
    clauses = [part.strip() for note in sources for part in CLAUSE.split(note) if part.strip()]
    relevant = [
        part
        for part in clauses
        if PURPOSE_WORD.search(part)
        or any(pattern.search(part) for pattern in PURPOSE_EVIDENCE.values())
    ]
    names: dict[str, list[int]] = {}
    for position, item in enumerate(candidate.items):
        if item.raw_name:
            names.setdefault(_normalized(item.raw_name), []).append(position)
    requires_review = False
    unresolved: list[str] = []
    decisions: dict[int, dict] = {}
    blocked: set[int] = set()
    covered: list[tuple[str, int]] = []

    def pending(texts):
        nonlocal requires_review
        requires_review = True
        for text in texts:
            if text and not any(text in existing for existing in unresolved):
                unresolved[:] = [existing for existing in unresolved if existing not in text]
                unresolved.append(text)

    if model_review is None:
        proposals = []
        for clause in relevant:
            target = _target(_normalized(clause).rstrip(".! "))
            positions = names.get(target, [])
            if len(positions) == 1 and candidate.items[positions[0]].quantity == 1:
                position = positions[0]
                proposals.append(
                    {
                        "item_position": position,
                        "item_name": candidate.items[position].raw_name,
                        "purpose": "advance",
                        "note_quote": clause,
                        "whole_line": True,
                    }
                )
            else:
                pending([clause])
    elif (
        not isinstance(model_review, dict)
        or set(model_review) != {"note_intents", "unresolved_note_quotes"}
        or not isinstance(model_review["note_intents"], list)
        or len(model_review["note_intents"]) > 100
        or not isinstance(model_review["unresolved_note_quotes"], list)
        or len(model_review["unresolved_note_quotes"]) > 100
    ):
        pending(relevant or sources)
        proposals = []
    else:
        proposals = model_review["note_intents"]
        for text in model_review["unresolved_note_quotes"]:
            if isinstance(text, str) and text.strip() and any(text in note for note in sources):
                pending([text])
            else:
                pending(relevant or sources)

    for proposal in proposals:
        if not isinstance(proposal, dict) or set(proposal) != INTENT_KEYS:
            pending(relevant or sources)
            continue
        position = proposal["item_position"]
        quote = proposal["note_quote"]
        purpose = proposal["purpose"]
        item_exists = type(position) is int and 0 <= position < len(candidate.items)
        valid = (
            item_exists
            and isinstance(quote, str)
            and bool(quote.strip())
            and any(quote in note for note in sources)
            and candidate.items[position].raw_name is not None
            and proposal["item_name"] == candidate.items[position].raw_name
            and len(names.get(_normalized(proposal["item_name"]), [])) == 1
            and isinstance(purpose, str)
            and purpose in PURPOSES
            and proposal["whole_line"] is True
            and PURPOSE_EVIDENCE[purpose].search(quote)
        )
        # Consider complete related source clauses too: a shortened quote cannot
        # hide a negation or partial allocation just after the quoted phrase.
        related = [
            part
            for part in clauses
            if item_exists
            and isinstance(quote, str)
            and (
                part in quote
                or quote in part
                or (candidate.items[position].raw_name or "\0") in part
            )
        ]
        if item_exists:
            # A whole-paragraph quote may mention several different items. Only
            # the clauses naming this item can establish its purpose; another
            # item's advance phrase cannot justify this item's allocation.
            named = [
                part
                for part in clauses
                if candidate.items[position].raw_name
                and _normalized(candidate.items[position].raw_name) in _normalized(part)
            ]
            if named:
                related = named
        purpose_conflict = valid and (
            not any(PURPOSE_EVIDENCE[purpose].search(part) for part in related)
            or any(
                pattern.search(part)
                for other, pattern in PURPOSE_EVIDENCE.items()
                if other != purpose
                for part in related
            )
        )
        unsafe = any(
            NEGATION.search(part)
            or PARTIAL.search(part)
            or (
                UNIT_COUNT.search(part)
                and not ALL_UNITS.search(part)
                and not (
                    item_exists
                    and candidate.items[position].quantity == 1
                    and all(count in {"一", "1"} for count in UNIT_COUNT.findall(part))
                )
            )
            for part in related
        )
        if not valid or unsafe or purpose_conflict:
            pending([quote, *related] if unsafe or purpose_conflict else relevant or sources)
            if item_exists:
                blocked.add(position)
            continue
        if position in decisions and decisions[position]["purpose"] != purpose:
            pending([decisions[position]["note_quote"], quote])
            blocked.add(position)
            continue
        decisions[position] = {"item_position": position, "purpose": purpose, "note_quote": quote}
        covered.append((quote, position))

    for position in blocked:
        decisions.pop(position, None)
    for clause in relevant:
        mentions = [
            position
            for name, positions in names.items()
            if name in _normalized(clause)
            for position in positions
        ]
        matches = [position for quote, position in covered if clause in quote or quote in clause]
        if not matches or any(position not in decisions for position in mentions):
            pending([clause])
    return {
        "status": "needs_review" if requires_review else "matched" if decisions else "none",
        "decisions": [decisions[position] for position in sorted(decisions)],
        "unresolved_notes": unresolved,
    }
