"""Cross-flow integration: immutable mobile context, reviewer navigation and ledger."""

import asyncio
import io
from copy import deepcopy
from urllib.parse import urlencode

import pytest
from fastapi.testclient import TestClient
from PIL import Image

from pixelreceipt_backend.api import create_app
from pixelreceipt_backend.auth import password_hash
from pixelreceipt_backend.intake_models import CreateIntake
from pixelreceipt_backend.intake_store import IntakeStore
from pixelreceipt_backend.models import CreateDraft

from .conftest import REVIEWER_TOKEN, TOKEN
from .test_auth_sessions import csrf_form
from .test_ledger import review_body
from .test_mobile_ingest import DEVICE, PASSWORD, intake, worker
from .test_prices import product, review_prices


def linked_intake(store, body, request=None):
    journal = IntakeStore(store)
    saved, _ = journal.create(request or intake(body), "context-original")
    runner, _ = worker(store, body)
    asyncio.run(runner.run_once())
    return journal.get(saved["id"])


def advance_note_draft(
    client,
    store,
    body,
    note,
    *,
    printed=300,
    quantity=1,
    duplicate=False,
    photo_note=None,
    other_name="合成炒飯",
):
    candidate = body["candidate"]
    candidate["printed_total_yuan"] = printed
    candidate["items"] = [
        {
            "raw_name": "合成丸湯" if duplicate else other_name,
            "quantity": 1,
            "printed_line_total_yuan": 270,
        },
        {"raw_name": "合成丸湯", "quantity": quantity, "printed_line_total_yuan": 30},
    ]
    candidate["adjustments"] = []
    request = intake(body).model_dump(mode="json", by_alias=True)
    request["user_notes"] = note
    request["evidence"][0]["notes"] = photo_note
    saved = linked_intake(store, body, CreateIntake.model_validate(request))
    url = f"/v1/drafts/{saved['draft_id']}"
    response = client.get(url)
    assert response.status_code == 200
    return url, response.json()


@pytest.mark.parametrize(("printed", "personal", "advance"), [(300, 270, 30), (330, None, None)])
def test_new_draft_suggests_advance_without_overwriting_printed_or_reviewed_amounts(
    client, store, body, printed, personal, advance
):
    url, draft = advance_note_draft(client, store, body, "合成丸湯幫別人買", printed=printed)
    assert draft["advance_suggestion"] == {
        "item_positions": [1],
        "personal_yuan": personal,
        "advance_yuan": advance,
    }
    assert draft["candidate"] == draft["original_candidate"]
    assert draft["candidate"]["printed_total_yuan"] == printed
    assert draft["review_document"] is None and not draft["calculation"]["ready"]
    assert client.get(url).json() == draft
    assert store.history(draft["id"])[0]["review_document"] is None
    assert store.list_ledger(100, 0) == []

    # An explicit human choice takes precedence over the original note.
    review = review_body(body)
    review["line_decisions"].append(
        {"item_position": 1, "method": "whole_line", "whole_purpose": "gift"}
    )
    assert client.put(f"/v2/drafts/{draft['id']}/review", json=review).status_code == 200
    reviewed = client.get(url).json()
    assert "advance_suggestion" not in reviewed
    assert reviewed["review_document"]["line_decisions"][1]["whole_purpose"] == "gift"


def test_single_receipt_photo_note_can_suggest_advance(client, store, body):
    _, draft = advance_note_draft(client, store, body, None, photo_note="代買：合成丸湯。")
    assert draft["advance_suggestion"]["item_positions"] == [1]


def test_separate_advance_statements_can_target_two_items_with_overlapping_names(
    client, store, body
):
    _, draft = advance_note_draft(
        client, store, body, "合成丸湯幫朋友買；迷你合成丸湯幫朋友買。", other_name="迷你合成丸湯"
    )
    assert draft["advance_suggestion"] == {
        "item_positions": [0, 1],
        "personal_yuan": 0,
        "advance_yuan": 300,
    }


def test_advance_sentence_is_recognized_beside_price_tag_context(client, store, body):
    _, draft = advance_note_draft(
        client, store, body, "第一張收據，第二張是合成丸湯價標；合成丸湯幫朋友買。"
    )
    assert draft["advance_suggestion"]["item_positions"] == [1]


@pytest.mark.parametrize(
    ("note", "quantity", "duplicate", "photo_note"),
    [
        ("合成丸湯幫別人買", 1, True, None),
        ("合成丸湯幫別人買", 2, False, None),
        ("合成丸湯不是幫別人買", 1, False, None),
        ("合成丸湯幫別人買嗎？", 1, False, None),
        ("合成丸湯幫別人買", 1, False, "合成丸湯自己喝"),
        ("合成丸湯幫別人買", 1, False, "這張沒有代買，都是自己用。"),
        ("合成丸湯幫別人買", 1, False, "合成丸湯是我自己的。"),
        ("合成丸湯幫別人買；合成炒飯幫小明買。", 1, False, None),
        ("請將合成丸湯改成300元", 1, False, None),
    ],
)
def test_unclear_notes_never_prefill_a_purchase_purpose(
    client, store, body, note, quantity, duplicate, photo_note
):
    _, draft = advance_note_draft(
        client, store, body, note, quantity=quantity, duplicate=duplicate, photo_note=photo_note
    )
    assert "advance_suggestion" not in draft
    assert draft["review_document"] is None


def test_context_uses_internal_relation_is_readonly_and_legacy_label_cannot_forge_it(
    client, store, body
):
    saved = linked_intake(store, body)
    draft_id = saved["draft_id"]
    before = store.get_draft(draft_id)
    history = store.history(draft_id)
    response = client.get(f"/v1/drafts/{draft_id}/intake-context")
    assert response.status_code == 200
    context = response.json()
    assert context == {
        "draft_id": draft_id,
        "intake": {
            "id": saved["id"],
            "purpose": "purchase",
            "user_notes": saved["input"]["user_notes"],
            "evidence": saved["input"]["evidence"],
            "created_at": saved["created_at"],
        },
    }
    assert store.get_draft(draft_id) == before and store.history(draft_id) == history
    assert store.list_ledger(100, 0) == []
    assert "raw_outcome" not in response.text and "config_snapshot" not in response.text
    forged = deepcopy(body)
    forged["source"]["label"] = "mobile-intake:" + saved["id"]
    legacy, _ = store.create_draft(CreateDraft.model_validate(forged), "forged-label")
    assert client.get(f"/v1/drafts/{legacy['id']}/intake-context").json() == {
        "draft_id": legacy["id"],
        "intake": None,
    }
    assert client.get("/v1/drafts/absent/intake-context").status_code == 404
    assert client.put(f"/v1/drafts/{draft_id}/intake-context", json={}).status_code == 405


def test_context_reviewer_only_and_mismatched_evidence_fails_closed(client, store, body):
    saved = linked_intake(store, body)
    url = f"/v1/drafts/{saved['draft_id']}/intake-context"
    store.create_device_session(DEVICE, "Synthetic device", "d" * 64)
    for token in (TOKEN, "d" * 64):
        client.headers["Authorization"] = "Bearer " + token
        assert client.get(url).status_code == 403
    client.headers.pop("Authorization")
    assert client.get(url).status_code == 401
    client.headers["Authorization"] = "Bearer " + REVIEWER_TOKEN
    with store.connection(write=True) as connection:
        connection.execute("UPDATE drafts SET evidence='[]' WHERE id=?", (saved["draft_id"],))
    response = client.get(url)
    assert response.status_code == 503
    assert response.json() == {"detail": "intake_context_invalid"}
    assert saved["input"]["user_notes"] not in response.text


def test_device_intake_context_review_confirm_and_price_flow_is_single_durable_transaction(
    store, body
):
    app = create_app(
        store,
        TOKEN,
        reviewer_token=REVIEWER_TOKEN,
        reviewer_password_hash=password_hash(PASSWORD),
        public_origin="https://testserver",
    )
    with TestClient(app, base_url="https://testserver") as client:
        logged_in = client.post(
            "/v1/mobile/login",
            json={
                "device_id": DEVICE,
                "device_name": "Synthetic Pixel",
                "password": PASSWORD,
            },
        )
        assert logged_in.status_code == 200
        device_token = logged_in.json()["access_token"]
        client.headers["Authorization"] = "Bearer " + device_token
        image = io.BytesIO()
        with Image.new("RGB", (11, 8), "blue") as value:
            value.save(image, format="PNG")
        uploaded = client.post(
            "/v1/evidence", content=image.getvalue(), headers={"Content-Type": "image/png"}
        ).json()
        request = intake(body).model_dump(mode="json", by_alias=True)
        request["user_notes"] = (
            "第一張是收據。茶飲是替朋友代買，請人工核對。\n<script>untrusted</script>"
        )
        request["evidence"][0]["notes"] = "已付款收據，金額仍需核對"
        request["evidence"].append(
            {
                "sha256": uploaded["sha256"],
                "role": "price_tag",
                "notes": "第二張是價標，不是另一筆消費",
            }
        )
        received = client.post(
            "/v1/intakes", json=request, headers={"Idempotency-Key": "cross-flow-one"}
        )
        assert received.status_code == 201
        intake_id = received.json()["id"]
        runner, fixture = worker(store, body)
        asyncio.run(runner.run_once())
        completed = client.get(f"/v1/intakes/{intake_id}").json()
        assert completed["status"] == "succeeded"
        draft_id = completed["draft_id"]
        assert client.get(f"/v1/drafts/{draft_id}/intake-context").status_code == 403
        review = review_body(body, purpose="advance")
        review_url = f"/v2/drafts/{draft_id}/review"
        confirm_url = f"/v2/drafts/{draft_id}/confirm"
        assert client.put(review_url, json=review).status_code == 403
        assert (
            client.post(
                confirm_url,
                json={"expected_revision": 1},
                headers={"Idempotency-Key": "device-cannot-confirm"},
            ).status_code
            == 403
        )
        client.headers["Authorization"] = "Bearer " + REVIEWER_TOKEN
        context = client.get(f"/v1/drafts/{draft_id}/intake-context").json()["intake"]
        assert context["user_notes"] == request["user_notes"]
        assert context["evidence"] == request["evidence"]
        assert store.get_draft(draft_id)["candidate"]["items"][0]["quantity"] is None
        reviewed = client.put(review_url, json=review)
        assert reviewed.status_code == 200 and reviewed.json()["calculation"]["ready"]
        assert reviewed.json()["calculation"]["advance_yuan"] == 100
        confirmed = client.post(
            confirm_url,
            json={"expected_revision": 2},
            headers={"Idempotency-Key": "cross-flow-confirm"},
        )
        assert confirmed.status_code == 201
        ledger = confirmed.json()
        assert ledger["snapshot"]["calculation"]["personal_yuan"] == 0
        assert ledger["snapshot"]["calculation"]["advance_yuan"] == 100
        item = product(client, key="cross-flow-product")
        assert review_prices(client, ledger, item["id"]).status_code == 200
        compared = client.get(f"/v1/products/{item['id']}/prices").json()
        assert compared["total_count"] == 1
        assert compared["lowest_paid_observation"]["ledger_id"] == ledger["id"]
        client.headers["Authorization"] = "Bearer " + device_token
        replay = client.post(
            "/v1/intakes", json=request, headers={"Idempotency-Key": "cross-flow-one"}
        )
        assert replay.status_code == 200 and replay.json()["id"] == intake_id
        assert replay.json()["draft_id"] == draft_id
        asyncio.run(runner.run_once())
        assert len(store.list_ledger(100, 0)) == 1
        assert len([call for call in fixture.calls if call[1] == "/v1/queue"]) == 1
        client.headers["Authorization"] = "Bearer " + REVIEWER_TOKEN
        assert client.get(f"/v1/drafts/{draft_id}/intake-context").json()["intake"] == context


@pytest.mark.parametrize(
    "route",
    [
        "#overview",
        "#review",
        "#ledger",
        "#prices",
        "#intakes",
        "#draft/" + DEVICE,
        "#ledger/" + DEVICE,
    ],
)
def test_login_returns_only_allowed_dashboard_route(store, route):
    app = create_app(
        store,
        TOKEN,
        reviewer_password_hash=password_hash(PASSWORD),
        public_origin="https://testserver",
        root_path="/receipts",
    )
    path = "/receipts/auth/login?" + urlencode({"return_to": route})
    with TestClient(app, base_url="https://testserver") as client:
        page = client.get(path)
        response = client.post(
            path,
            data={"csrf": csrf_form(page), "password": PASSWORD},
            headers={"Origin": "https://testserver"},
            follow_redirects=False,
        )
        assert response.status_code == 303
        assert response.headers["location"] == "/receipts/" + route
        assert "password" not in response.headers["location"]


@pytest.mark.parametrize(
    "route",
    [
        "https://evil.test/",
        "//evil.test",
        "javascript:alert(1)",
        "#ledger/../auth/logout",
        "#prices?token=bad",
        "#overview\r\nLocation: https://evil.test",
        "#unknown",
        "#ledger/" + "a" * 36,
    ],
)
def test_login_rejects_arbitrary_return_locations(store, route):
    app = create_app(
        store,
        TOKEN,
        reviewer_password_hash=password_hash(PASSWORD),
        public_origin="https://testserver",
        root_path="/receipts",
    )
    path = "/receipts/auth/login?" + urlencode({"return_to": route})
    with TestClient(app, base_url="https://testserver") as client:
        page = client.get(path)
        response = client.post(
            path,
            data={"csrf": csrf_form(page), "password": PASSWORD},
            headers={"Origin": "https://testserver"},
            follow_redirects=False,
        )
        assert response.status_code == 303
        assert response.headers["location"] == "/receipts/"
