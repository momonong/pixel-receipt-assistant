import json
from copy import deepcopy

import pytest

from pixelreceipt_backend.api import MAX_JSON_BYTES

from .test_drafts import create


@pytest.mark.parametrize("bad", [True, False, "100", 1.5, -1, 2**63])
def test_money_rejects_lossy_or_out_of_long_values(client, body, bad):
    body["candidate"]["printed_total_yuan"] = bad
    assert create(client, body).status_code == 422
    assert client.get("/v1/drafts").json()["drafts"] == []


@pytest.mark.parametrize("bad", [0, True, 1.0, 2**31, "2"])
def test_quantity_matches_android_int(client, body, bad):
    body["candidate"]["items"][0]["quantity"] = bad
    assert create(client, body).status_code == 422


@pytest.mark.parametrize(
    "field", ["id", "revision", "status", "net_amount", "canonical_product_id"]
)
def test_model_cannot_author_workflow_ledger_or_product_identity(client, body, field):
    body["candidate"][field] = "untrusted"
    assert create(client, body).status_code == 422


@pytest.mark.parametrize(
    "mutate",
    [
        lambda body: body["candidate"].update(currency="USD"),
        lambda body: body["candidate"].update(amount_unit="cents"),
        lambda body: body["candidate"].update(receipt_date="2026-02-30"),
        lambda body: body["candidate"].update(merchant="a" * 501),
        lambda body: body["candidate"].update(merchant="   \n\t"),
        lambda body: body["candidate"].update(items=body["candidate"]["items"] * 101),
        lambda body: body["candidate"]["items"][0].update(extra="no"),
        lambda body: body["candidate"]["items"][0]["content_quantity"].update(value_decimal="1e6"),
        lambda body: body["candidate"]["adjustments"][0].update(scope="items", item_positions=[1]),
        lambda body: body["candidate"]["adjustments"][0].update(scope="items", item_positions=[]),
        lambda body: body["candidate"]["adjustments"][0].update(scope=None, item_positions=[0]),
        lambda body: body["evidence"].append(deepcopy(body["evidence"][0])),
        lambda body: body["evidence"][0].update(pages=[1, 1]),
        lambda body: body.update(status="confirmed"),
    ],
)
def test_schema_bounds_and_relations(client, body, mutate):
    mutate(body)
    assert create(client, body).status_code == 422


@pytest.mark.parametrize(
    "raw",
    [
        b'{"candidate":{},"candidate":{}}',
        b'{"value":1e2}',
        b'{"value":-0}',
        b'{"value":NaN}',
        b'{"value":Infinity}',
        b'{"value":1.0}',
        b'{"value":"\\ud800"}',
        b'{"value":' + b"[" * 1000 + b"0" + b"]" * 1000 + b"}",
        b"{} trailing",
        b"\xff",
        b"[]",
    ],
)
def test_malformed_json_fails_without_echoing_private_input(client, raw):
    response = client.post(
        "/v1/drafts",
        content=raw,
        headers={"Content-Type": "application/json", "Idempotency-Key": "invalid-json"},
    )
    assert response.status_code == 422
    assert response.json() == {"detail": "invalid_candidate_request"}


def test_bounds_encoding_and_key(client, body):
    assert client.post("/v1/drafts", json=body).status_code == 422
    assert create(client, body, "x" * 129).status_code == 422
    headers = {"Content-Type": "application/json", "Idempotency-Key": "large-body"}
    assert (
        client.post("/v1/drafts", content=b" " * (MAX_JSON_BYTES + 1), headers=headers).status_code
        == 413
    )
    headers["Content-Encoding"] = "gzip"
    assert client.post("/v1/drafts", content=b"{}", headers=headers).status_code == 415
    assert (
        client.post(
            "/v1/drafts",
            content=json.dumps(body),
            headers={
                "Content-Type": "text/plain",
                "Idempotency-Key": "wrong-type",
            },
        ).status_code
        == 415
    )


def test_unknown_and_known_zero_are_distinct_and_upper_bounds_survive(client, body):
    body["candidate"]["printed_total_yuan"] = 2**63 - 1
    body["candidate"]["items"][0]["quantity"] = 2**31 - 1
    body["candidate"]["items"][0]["printed_unit_price_yuan"] = 0
    body["candidate"]["adjustments"][0]["direction"] = None
    body["candidate"]["adjustments"][0]["amount_yuan"] = 0
    result = create(client, body)
    assert result.status_code == 201
    candidate = result.json()["candidate"]
    assert candidate["printed_total_yuan"] == 2**63 - 1
    assert candidate["items"][0]["printed_unit_price_yuan"] == 0
    assert candidate["adjustments"][0]["amount_yuan"] == 0
    assert candidate["adjustments"][0]["scope"] is None


def test_adjustment_limit_and_amount_effect(client, body):
    body["candidate"]["adjustments"] *= 51
    assert create(client, body).status_code == 422
    body["candidate"]["adjustments"] = body["candidate"]["adjustments"][:1]
    body["candidate"]["adjustments"][0]["amount_effect"] = "already_confirmed"
    assert create(client, body).status_code == 422
