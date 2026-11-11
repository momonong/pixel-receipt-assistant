"""Intake notes survive autosave, and unresolved uses require explicit review."""

from .test_ledger import review_body
from .test_review_context import advance_note_draft

MODEL_REVIEW = {
    "note_intents": [
        {
            "item_position": 1,
            "item_name": "合成丸湯",
            "purpose": "advance",
            "note_quote": "合成丸湯是代買的",
            "whole_line": True,
        }
    ],
    "unresolved_note_quotes": [],
}


def test_note_review_remains_visible_after_all_self_autosave(client, store, body):
    url, draft = advance_note_draft(
        client, store, body, "合成丸湯是代買的", model_review=MODEL_REVIEW
    )
    review = review_body(body)
    review["line_decisions"].append(
        {"item_position": 1, "method": "whole_line", "whole_purpose": "self"}
    )
    autosaved = client.put(f"/v2/drafts/{draft['id']}/review", json=review)
    assert autosaved.status_code == 200
    assert autosaved.json()["note_review"]["status"] == "matched"
    saved = client.get(url).json()
    assert saved["revision"] == 2
    assert saved["note_review"]["status"] == "matched"
    assert saved["note_review"]["decisions"][0]["purpose"] == "advance"
    assert saved["review_document"]["line_decisions"][1]["whole_purpose"] == "self"
    request = {"expected_revision": 2}
    assert (
        client.post(
            f"/v2/drafts/{draft['id']}/confirm",
            json=request,
            headers={"Idempotency-Key": "all-self-unchecked"},
        ).status_code
        == 409
    )
    request["notes_acknowledgement"] = "checked"
    accepted = client.post(
        f"/v2/drafts/{draft['id']}/confirm",
        json=request,
        headers={"Idempotency-Key": "all-self-reviewed"},
    )
    assert accepted.status_code == 201


def test_matched_advance_does_not_require_extra_acknowledgement(client, store, body):
    _, draft = advance_note_draft(
        client, store, body, "合成丸湯是代買的", model_review=MODEL_REVIEW
    )
    review = review_body(body)
    review["line_decisions"].append(
        {"item_position": 1, "method": "whole_line", "whole_purpose": "advance"}
    )
    saved = client.put(f"/v2/drafts/{draft['id']}/review", json=review)
    assert saved.json()["calculation"]["personal_yuan"] == 270
    accepted = client.post(
        f"/v2/drafts/{draft['id']}/confirm",
        json={"expected_revision": 2},
        headers={"Idempotency-Key": "matched-advance-confirm"},
    )
    assert accepted.status_code == 201


def test_unresolved_description_is_visible_and_not_silently_confirmed_as_self(client, store, body):
    _, draft = advance_note_draft(client, store, body, "裡面那個湯我是幫媽媽買的，其他自己用")
    assert draft["note_review"]["status"] == "needs_review"
    review = review_body(body)
    review["line_decisions"].append(
        {"item_position": 1, "method": "whole_line", "whole_purpose": "self"}
    )
    client.put(f"/v2/drafts/{draft['id']}/review", json=review)
    response = client.post(
        f"/v2/drafts/{draft['id']}/confirm",
        json={"expected_revision": 2},
        headers={"Idempotency-Key": "unresolved-confirm"},
    )
    assert response.status_code == 409 and response.json()["detail"] == "notes_review_required"
