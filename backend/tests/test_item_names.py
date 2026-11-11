"""Optional names and durable name-only completion never change financial snapshots."""

import json

from .test_drafts import create
from .test_ledger import review_body


def confirmed(client, body, *, unnamed=False):
    body["candidate"]["items"][0]["raw_name"] = None if unnamed else "合成茶飲"
    draft = create(client, body).json()
    review = review_body(body)
    saved = client.put(f"/v2/drafts/{draft['id']}/review", json=review)
    assert saved.status_code == 200
    result = client.post(
        f"/v2/drafts/{draft['id']}/confirm",
        json={"expected_revision": 2},
        headers={"Idempotency-Key": "confirm-name-test"},
    )
    assert result.status_code == 201
    return result.json()


def test_missing_name_can_be_confirmed_and_is_visible_in_overview(client, body):
    entry = confirmed(client, body, unnamed=True)
    assert entry["snapshot"]["candidate"]["items"][0]["raw_name"] is None
    assert entry["snapshot"]["calculation"]["personal_yuan"] == 100
    assert entry["display_item_names"] == [None]
    assert entry["missing_item_name_count"] == 1
    overview = client.get("/v1/overview").json()
    assert overview["missing_item_name_count"] == overview["receipts_missing_item_names"] == 1


def test_name_completion_is_audited_idempotent_and_preserves_money(client, body, store):
    entry = confirmed(client, body)
    url = f"/v1/ledger/{entry['id']}/item-names"
    request = {"expected_revision": 1, "item_names": [{"item_position": 0, "name": "更正茶飲"}]}
    response = client.post(url, json=request, headers={"Idempotency-Key": "name-update-1"})
    assert response.status_code == 200
    updated = response.json()
    assert updated["revision"] == 2 and updated["status"] == "active"
    assert updated["snapshot"] == entry["snapshot"]
    assert updated["display_item_names"] == ["更正茶飲"]
    assert updated["missing_item_name_count"] == 0
    assert updated["events"][-1]["action"] == "item_names_updated"
    assert json.loads(updated["events"][-1]["reason"])["item_names"] == request["item_names"]
    assert (
        client.post(url, json=request, headers={"Idempotency-Key": "name-update-1"}).json()
        == updated
    )
    assert len(store.get_ledger(entry["id"])["events"]) == 2
    assert client.get("/v1/overview").json()["personal_yuan"] == 100
    assert client.get("/v1/ledger", params={"q": "更正茶飲"}).json()["total_count"] == 1
    assert (
        client.post(url, json=request, headers={"Idempotency-Key": "name-update-other"}).status_code
        == 409
    )
    changed = {**request, "item_names": [{"item_position": 0, "name": "不同內容"}]}
    assert (
        client.post(url, json=changed, headers={"Idempotency-Key": "name-update-1"}).status_code
        == 409
    )


def test_clear_name_and_complete_later_updates_summary_and_keeps_voided_history(client, body):
    entry = confirmed(client, body)
    url = f"/v1/ledger/{entry['id']}/item-names"
    cleared = client.post(
        url,
        json={"expected_revision": 1, "item_names": [{"item_position": 0, "name": None}]},
        headers={"Idempotency-Key": "name-clear"},
    ).json()
    assert cleared["display_item_names"] == [None]
    assert client.get("/v1/overview").json()["missing_item_name_count"] == 1
    completed = client.post(
        url,
        json={"expected_revision": 2, "item_names": [{"item_position": 0, "name": "補上品名"}]},
        headers={"Idempotency-Key": "name-complete"},
    ).json()
    assert completed["revision"] == 3 and completed["missing_item_name_count"] == 0
    assert client.get("/v1/overview").json()["missing_item_name_count"] == 0
    voided = client.post(
        f"/v1/ledger/{entry['id']}/void",
        json={"expected_revision": 3, "reason": "合成作廢"},
        headers={"Idempotency-Key": "name-void"},
    )
    assert voided.status_code == 200 and voided.json()["status"] == "voided"
    assert voided.json()["display_item_names"] == ["補上品名"]
    assert (
        client.post(
            url,
            json={"expected_revision": 4, "item_names": [{"item_position": 0, "name": "不可改"}]},
            headers={"Idempotency-Key": "voided-name"},
        ).status_code
        == 409
    )
    assert client.get("/v1/overview").json()["personal_yuan"] == 0


def test_name_endpoint_rejects_untrusted_positions_extra_money_and_ingest_role(client, body):
    from .conftest import TOKEN

    entry = confirmed(client, body)
    url = f"/v1/ledger/{entry['id']}/item-names"
    for values in [
        [{"item_position": 1, "name": "沒有這項"}],
        [{"item_position": 0, "name": "a"}, {"item_position": 0, "name": "b"}],
        [{"item_position": 0, "name": "x", "amount": 0}],
    ]:
        assert (
            client.post(
                url,
                json={"expected_revision": 1, "item_names": values},
                headers={"Idempotency-Key": "invalid-names"},
            ).status_code
            == 422
        )
    client.headers["Authorization"] = "Bearer " + TOKEN
    assert (
        client.post(
            url,
            json={"expected_revision": 1, "item_names": [{"item_position": 0, "name": "x"}]},
            headers={"Idempotency-Key": "ingest-name"},
        ).status_code
        == 403
    )
