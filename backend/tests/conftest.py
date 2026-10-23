import io

import pytest
from fastapi.testclient import TestClient
from PIL import Image

from pixelreceipt_backend.api import create_app
from pixelreceipt_backend.store import Store

REVIEWER_TOKEN = "synthetic-reviewer-test-token-0000000000000000"
TOKEN = "synthetic-test-token-not-a-real-secret-123456"


@pytest.fixture
def png():
    stream = io.BytesIO()
    Image.new("RGB", (10, 8), "white").save(stream, format="PNG")
    return stream.getvalue()


@pytest.fixture
def store(tmp_path):
    return Store(tmp_path / "private")


@pytest.fixture
def client(store):
    with TestClient(create_app(store, TOKEN, reviewer_token=REVIEWER_TOKEN)) as value:
        value.headers["Authorization"] = "Bearer " + REVIEWER_TOKEN
        yield value


@pytest.fixture
def body(client, png):
    uploaded = client.post("/v1/evidence", content=png, headers={"Content-Type": "image/png"})
    assert uploaded.status_code == 200
    return {
        "evidence": [{"sha256": uploaded.json()["sha256"], "pages": [1]}],
        "source": {"kind": "ai_candidate", "label": "SYNTHETIC", "provider": None, "model": None},
        "candidate": {
            "schema": "pixelreceipt-central-candidate-1",
            "currency": "TWD",
            "amount_unit": "TWD_YUAN",
            "merchant": "合成測試商店",
            "receipt_date": "2026-10-01",
            "printed_total_yuan": 100,
            "items": [
                {
                    "raw_name": "合成茶飲",
                    "quantity": None,
                    "printed_unit_price_yuan": None,
                    "printed_line_total_yuan": 100,
                    "brand": None,
                    "specification": "500ml",
                    "content_quantity": {"value_decimal": "500", "unit": "ml"},
                }
            ],
            "adjustments": [
                {
                    "raw_label": "未知適用範圍的折扣",
                    "direction": "subtract",
                    "amount_yuan": None,
                    "scope": None,
                    "item_positions": [],
                }
            ],
        },
    }
