"""HTTP admission precedes buffering; every rejection/timeout releases the upload slot."""

import asyncio

import httpx
from fastapi.testclient import TestClient

from pixelreceipt_backend import api, inspection
from pixelreceipt_backend.api import create_app
from pixelreceipt_backend.evidence import inspect_evidence

from .conftest import TOKEN
from .test_inspection import assert_reaped, controlled_spawn


def test_busy_does_not_read_body_stall_times_out_and_slot_is_released(store, png, monkeypatch):
    monkeypatch.setattr(api, "UPLOAD_BODY_TIMEOUT_SECONDS", 0.05)

    async def inspect(content, media_type):
        return inspect_evidence(content, media_type)

    monkeypatch.setattr(api, "inspect_evidence_isolated", inspect)
    app = create_app(store, TOKEN)

    async def run():
        entered = asyncio.Event()
        second_consumed = False

        async def stalled():
            entered.set()
            await asyncio.Event().wait()
            yield b"unreachable"

        async def second_body():
            nonlocal second_consumed
            second_consumed = True
            yield png

        async with httpx.AsyncClient(
            transport=httpx.ASGITransport(app=app),
            base_url="http://testserver",
            headers={"Authorization": "Bearer " + TOKEN, "Content-Type": "image/png"},
        ) as client:
            first = asyncio.create_task(client.post("/v1/evidence", content=stalled()))
            await entered.wait()
            busy = await client.post("/v1/evidence", content=second_body())
            assert busy.status_code == 503 and busy.json()["detail"] == "inspection_busy"
            assert not second_consumed
            response = await first
            assert response.status_code == 408 and response.json()["detail"] == "upload_timeout"
            with store.connection() as connection:
                assert connection.execute("SELECT COUNT(*) FROM evidence").fetchone()[0] == 0
            # A body-size rejection must also release its admission slot.
            assert (
                await client.post(
                    "/v1/evidence", content=b"", headers={"Content-Length": str(21 * 1024 * 1024)}
                )
            ).status_code == 413
            assert (await client.post("/v1/evidence", content=png)).status_code == 200

    asyncio.run(run())


def test_actual_inspection_timeout_is_503_reaped_and_never_persists(store, png, monkeypatch):
    processes = []
    controlled_spawn(monkeypatch, "import time; time.sleep(60)", processes)
    monkeypatch.setattr(inspection, "WALL_TIMEOUT_SECONDS", 0.05)
    app = create_app(store, TOKEN)
    with TestClient(app) as client:
        response = client.post(
            "/v1/evidence",
            content=png,
            headers={"Authorization": "Bearer " + TOKEN, "Content-Type": "image/png"},
        )
    assert response.status_code == 503 and response.json()["detail"] == "inspection_timeout"
    assert len(processes) == 1
    assert_reaped(processes[0])
    with store.connection() as connection:
        assert connection.execute("SELECT COUNT(*) FROM evidence").fetchone()[0] == 0
    assert list(store.blobs.iterdir()) == []
