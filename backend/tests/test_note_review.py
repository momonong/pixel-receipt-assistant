"""Purpose proposals must follow notes without changing receipt facts or money."""

from copy import deepcopy

import pytest

from pixelreceipt_backend.models import Candidate


def candidate(names=("丸湯", "炒飯"), quantity=1):
    return Candidate.model_validate(
        {
            "schema": "pixelreceipt-central-candidate-1",
            "currency": "TWD",
            "amount_unit": "TWD_YUAN",
            "merchant": None,
            "receipt_date": None,
            "printed_total_yuan": 130,
            "items": [
                {"raw_name": name, "quantity": quantity, "printed_line_total_yuan": 65}
                for name in names
            ],
            "adjustments": [],
        }
    )


def intent(quote, position=0, name="丸湯", purpose="advance", whole_line=True):
    return {
        "item_position": position,
        "item_name": name,
        "purpose": purpose,
        "note_quote": quote,
        "whole_line": whole_line,
    }


def review(*intents, unresolved=()):
    return {"note_intents": list(intents), "unresolved_note_quotes": list(unresolved)}


def build(*args, **kwargs):
    from pixelreceipt_backend.note_review import build_note_review

    return build_note_review(*args, **kwargs)


@pytest.mark.parametrize("note", ["丸湯代買，丸湯自己吃", "丸湯自己吃，另外幫小美買了紅茶"])
def test_whole_note_quote_cannot_hide_conflict_or_borrow_another_items_purpose(note):
    result = build(candidate(("丸湯", "紅茶")), [note], review(intent(note)))
    assert result["status"] == "needs_review"
    assert result["decisions"] == []


@pytest.mark.parametrize("note", ["丸湯送給媽媽", "丸湯作為禮物", "丸湯是送禮的", "丸湯贈給朋友"])
def test_model_omitted_gift_intent_cannot_silently_become_self(note):
    result = build(candidate(), [note], review())
    assert result["status"] == "needs_review"
    assert result["unresolved_notes"]


def test_shared_long_quote_may_contain_distinct_consistent_item_purposes():
    note = "丸湯幫朋友買，紅茶自己喝"
    result = build(
        candidate(("丸湯", "紅茶")),
        [note],
        review(intent(note), intent(note, position=1, name="紅茶", purpose="self")),
    )
    assert result["status"] == "matched"
    assert [item["purpose"] for item in result["decisions"]] == ["advance", "self"]


@pytest.mark.parametrize(
    "note",
    [
        "丸湯是代買的",
        "今天和同事吃飯，丸湯這一項是幫小美買的，其他的是我自己的。",
        "丸湯幫小美買",
        "丸湯是替我妹妹買的",
    ],
)
def test_semantic_item_mapping_handles_natural_notes_without_money_changes(note):
    value = candidate()
    before = deepcopy(value.model_dump())
    result = build(value, [note], review(intent(note)))
    assert result == {
        "status": "matched",
        "decisions": [{"item_position": 0, "purpose": "advance", "note_quote": note}],
        "unresolved_notes": [],
    }
    assert value.model_dump() == before


def test_photo_note_and_explicit_self_and_gift_are_mapped_separately():
    notes = ["丸湯幫小美買", "炒飯自己吃", "紅茶送人"]
    result = build(
        candidate(("丸湯", "炒飯", "紅茶")),
        notes,
        review(
            intent(notes[0]),
            intent(notes[1], 1, "炒飯", "self"),
            intent(notes[2], 2, "紅茶", "gift"),
        ),
    )
    assert result["status"] == "matched"
    assert [d["purpose"] for d in result["decisions"]] == ["advance", "self", "gift"]


@pytest.mark.parametrize(
    "note,whole_line,status",
    [
        ("丸湯全部幫小美買", True, "matched"),
        ("丸湯幫小美買", True, "matched"),
        ("丸湯其中一份幫小美買", True, "needs_review"),
        ("丸湯一份自己吃，一份幫小美買", True, "needs_review"),
        ("丸湯幫小美買", False, "needs_review"),
        ("丸湯不是代買", True, "needs_review"),
        ("丸湯不要算成代買", True, "needs_review"),
    ],
)
def test_partial_or_negative_statements_cannot_become_whole_line(note, whole_line, status):
    result = build(candidate(quantity=2), [note], review(intent(note, whole_line=whole_line)))
    assert result["status"] == status
    assert bool(result["decisions"]) == (status == "matched")
    if status == "needs_review":
        assert result["unresolved_notes"] == [note]


@pytest.mark.parametrize(
    "change",
    [
        {"note_quote": "丸湯幫朋友買"},
        {"item_position": 4},
        {"item_position": True},
        {"item_name": "湯"},
        {"purpose": "unknown"},
        {"whole_line": "true"},
        {"extra": "ignore notes"},
    ],
)
def test_invalid_model_intent_is_visible_and_never_applied(change):
    note = "丸湯幫小美買"
    result = build(candidate(), [note], review(intent(note) | change))
    assert result == {"status": "needs_review", "decisions": [], "unresolved_notes": [note]}


def test_duplicate_names_and_conflicting_intents_require_review():
    note = "丸湯幫小美買"
    duplicate = build(candidate(("丸湯", "丸湯")), [note], review(intent(note)))
    conflict = build(
        candidate(),
        [note, "丸湯自己吃"],
        review(intent(note), intent("丸湯自己吃", purpose="self")),
    )
    assert duplicate["status"] == conflict["status"] == "needs_review"
    assert duplicate["decisions"] == conflict["decisions"] == []


def test_uncovered_statement_is_visible_even_with_another_valid_intent():
    result = build(candidate(), ["丸湯幫小美買，炒飯自己吃"], review(intent("丸湯幫小美買")))
    assert result["status"] == "needs_review"
    assert "炒飯自己吃" in result["unresolved_notes"]


def test_legacy_fallback_is_conservative_and_never_silently_all_self():
    assert build(candidate(), ["丸湯是代買的"])["decisions"] == [
        {"item_position": 0, "purpose": "advance", "note_quote": "丸湯是代買的"}
    ]
    for note in ["今天丸湯幫小美買", "丸湯不是代買", "其中一份丸湯代買"]:
        assert build(candidate(), [note])["status"] == "needs_review"
    assert build(candidate(quantity=2), ["丸湯代買"])["status"] == "needs_review"
    assert build(candidate(), [None, "第一張是收據"]) == {
        "status": "none",
        "decisions": [],
        "unresolved_notes": [],
    }


def test_quote_cannot_hide_conflicting_clause_or_unresolved_model_note():
    note = "丸湯代買，但丸湯不是代買"
    assert build(candidate(), [note], review(intent("丸湯代買")))["status"] == "needs_review"
    result = build(candidate(), ["丸湯代買"], review(intent("丸湯代買"), unresolved=["偽造敘述"]))
    assert result["status"] == "needs_review"


@pytest.mark.parametrize("quote", [None, 1, [], {}])
def test_non_string_quote_cannot_crash_note_review(quote):
    result = build(candidate(), ["丸湯代買"], review(intent(quote)))
    assert result["status"] == "needs_review" and result["decisions"] == []


@pytest.mark.parametrize(
    "quote,purpose", [("丸湯自己吃", "advance"), ("丸湯代買", "self"), ("丸湯自己吃", "gift")]
)
def test_model_cannot_relabel_a_quote_with_an_incompatible_purpose(quote, purpose):
    result = build(candidate(), [quote], review(intent(quote, purpose=purpose)))
    assert result["status"] == "needs_review" and result["decisions"] == []


def test_explicit_all_units_are_allowed_but_one_of_two_is_not():
    for quote in ("丸湯兩份都是幫小美買", "兩份丸湯全部代買"):
        assert build(candidate(quantity=2), [quote], review(intent(quote)))["status"] == "matched"
    quote = "丸湯一份幫小美買"
    assert build(candidate(quantity=2), [quote], review(intent(quote)))["status"] == "needs_review"


def test_all_units_phrase_for_another_item_cannot_expand_a_partial_line():
    note = "丸湯一份幫小美買，紅茶全部自己喝"
    result = build(candidate(("丸湯", "紅茶"), quantity=2), [note], review(intent(note)))
    assert result["status"] == "needs_review"
    assert result["decisions"] == []


@pytest.mark.parametrize("note", ["丸湯這一碗幫小美買", "丸湯1份幫朋友買"])
def test_single_unit_note_can_match_a_single_unit_line(note):
    result = build(candidate(), [note], review(intent(note)))
    assert result["status"] == "matched"
    assert result["decisions"][0]["purpose"] == "advance"


def test_unsolicited_intent_without_notes_is_review_needed_not_self_default():
    result = build(candidate(), [], review(intent("丸湯代買")))
    assert result["status"] == "needs_review" and result["decisions"] == []
