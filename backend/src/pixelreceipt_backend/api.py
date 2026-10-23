"""Authenticated local HTTP API. Uploading evidence never starts recognition."""

import asyncio
import json
import re
import sqlite3
import threading
from datetime import date
from pathlib import Path
from typing import Annotated, Literal

from fastapi import APIRouter, Depends, FastAPI, Header, HTTPException, Query, Request, Response
from fastapi.responses import FileResponse, JSONResponse
from pydantic import ValidationError

from .analysis_store import AnalysisStore
from .auth import Auth
from .evidence import MAX_UPLOAD_BYTES, SUPPORTED_MEDIA, InvalidEvidence
from .inspection import InspectionUnavailable, inspect_evidence_isolated
from .intake_models import CreateIntake
from .intake_store import IntakeStore
from .ledger_models import ConfirmReceipt, ReviewReceipt, VoidReceipt
from .mobile_release import MobileRelease
from .models import CreateDraft, ReviewDraft
from .pricing_models import CreateProduct, ReviewPrices
from .recognition import RecognitionWorker
from .store import SCHEMA_VERSION, Store, StoreError

MAX_JSON_BYTES = 256 * 1024
UPLOAD_BODY_TIMEOUT_SECONDS = 60
KEY_PATTERN = re.compile(r"[A-Za-z0-9][A-Za-z0-9._:-]{0,127}\Z")


async def bounded_body(request: Request, limit: int) -> bytes:
    if request.headers.get("content-encoding", "identity") != "identity":
        raise HTTPException(415, "content_encoding_not_supported")
    length = request.headers.get("content-length")
    if length is not None:
        try:
            if int(length) < 0:
                raise ValueError
            if int(length) > limit:
                raise HTTPException(413, "request_too_large")
        except ValueError as error:
            raise HTTPException(400, "invalid_content_length") from error
    chunks = bytearray()
    async for chunk in request.stream():
        if len(chunks) + len(chunk) > limit:
            raise HTTPException(413, "request_too_large")
        chunks.extend(chunk)
    return bytes(chunks)


def strict_json(content: bytes) -> dict:
    def pairs(entries):
        result = {}
        for key, value in entries:
            if key in result or len(result) >= 30:
                raise ValueError("duplicate keys or too many fields")
            result[key] = value
        return result

    def integer(token):
        if not re.fullmatch(r"0|[1-9][0-9]{0,18}", token):
            raise ValueError("nonnegative integer required")
        value = int(token)
        if value > 2**63 - 1:
            raise ValueError("Long overflow")
        return value

    def reject(_):
        raise ValueError("noninteger number")

    def inspect(value, depth=0):
        if depth > 8:
            raise ValueError("JSON nesting limit")
        if isinstance(value, dict):
            for child in value.values():
                inspect(child, depth + 1)
        elif isinstance(value, list):
            if len(value) > 100:
                raise ValueError("array limit")
            for child in value:
                inspect(child, depth + 1)
        elif isinstance(value, bool):
            raise ValueError("boolean not in candidate contract")
        elif isinstance(value, str):
            # Reject invalid Unicode before canonical UTF-8 storage, without echoing private data.
            value.encode("utf-8", errors="strict")

    value = json.loads(
        content.decode("utf-8"),
        object_pairs_hook=pairs,
        parse_int=integer,
        parse_float=reject,
        parse_constant=reject,
    )
    if not isinstance(value, dict):
        raise ValueError("object required")
    inspect(value)
    return value


async def parse_request(request: Request, model, limit=MAX_JSON_BYTES):
    if request.headers.get("content-type", "").split(";")[0].strip() != "application/json":
        raise HTTPException(415, "application_json_required")
    content = await bounded_body(request, limit)
    try:
        return model.model_validate(strict_json(content))
    except (ValueError, ValidationError, RecursionError) as error:
        # Pydantic default errors can echo receipt text; only a stable code crosses this boundary.
        raise HTTPException(422, "invalid_candidate_request") from error


def create_app(
    store: Store,
    token: str,
    *,
    reviewer_token: str | None = None,
    reviewer_password_hash: str | None = None,
    root_path: str = "",
    static_dir: Path | None = None,
    cookie_secure: bool = True,
    public_origin: str | None = None,
    recognition_config=None,
    recognition_unavailable_reason=None,
    recognition_transport=None,
    mobile_release_dir=None,
) -> FastAPI:
    root_path = root_path.rstrip("/")
    if root_path and not re.fullmatch(r"(?:/[A-Za-z0-9_-]+)+", root_path):
        raise ValueError("root_path must be an absolute URL path without query or fragment")
    auth = Auth(
        store,
        token,
        reviewer_token=reviewer_token,
        reviewer_password_hash=reviewer_password_hash,
        cookie_secure=cookie_secure,
        root_path=root_path,
        public_origin=public_origin,
    )
    recognition = RecognitionWorker(
        store, recognition_config, recognition_unavailable_reason, recognition_transport
    )
    intakes = IntakeStore(store)
    app = FastAPI(
        title="PixelReceipt central ledger",
        version="0.4.0",
        lifespan=recognition.lifespan,
        root_path=root_path,
        docs_url=None,
        redoc_url=None,
        openapi_url=None,
    )
    mobile_release = MobileRelease(mobile_release_dir) if mobile_release_dir else None
    app.state.recognition = recognition
    api = APIRouter(dependencies=[Depends(auth.authenticate)])
    upload_slot = threading.Lock()
    analysis = AnalysisStore(store)

    @app.middleware("http")
    async def private_response(request: Request, call_next):
        response = await call_next(request)
        response.headers["Cache-Control"] = "no-store"
        response.headers["X-Content-Type-Options"] = "nosniff"
        response.headers["Referrer-Policy"] = "same-origin"
        response.headers["Content-Security-Policy"] = (
            "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' blob:; "
            "object-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'"
        )
        return response

    @app.exception_handler(StoreError)
    async def storage_error(_request, error: StoreError):
        content = {"detail": error.code}
        if hasattr(error, "calculation"):
            content["calculation"] = error.calculation
        return JSONResponse(status_code=error.status, content=content)

    @app.exception_handler(sqlite3.Error)
    async def database_error(_request, _error):
        return JSONResponse(status_code=503, content={"detail": "database_unavailable"})

    @api.get("/health")
    def health():
        return {"status": "ok", "scope": "central_ledger", "schema_version": SCHEMA_VERSION}

    @api.post("/v1/evidence")
    async def upload(request: Request):
        media_type = request.headers.get("content-type", "").split(";")[0].strip().lower()
        if media_type not in SUPPORTED_MEDIA:
            raise HTTPException(415, "unsupported_media_type")
        if not upload_slot.acquire(blocking=False):
            raise HTTPException(503, "inspection_busy")
        try:
            try:
                async with asyncio.timeout(UPLOAD_BODY_TIMEOUT_SECONDS):
                    content = await bounded_body(request, MAX_UPLOAD_BYTES)
            except TimeoutError as error:
                raise HTTPException(408, "upload_timeout") from error
            metadata = await inspect_evidence_isolated(content, media_type)
            return store.put_evidence(content, metadata)
        except InvalidEvidence as error:
            raise HTTPException(422, "invalid_or_unsupported_evidence") from error
        except InspectionUnavailable as error:
            raise HTTPException(error.status, error.code) from error
        finally:
            upload_slot.release()

    @api.get("/v1/evidence/{sha256}")
    def evidence_metadata(sha256: str):
        return store.get_evidence(sha256)

    @api.get("/v1/evidence/{sha256}/content")
    def evidence_content(sha256: str):
        metadata, content = store.get_evidence(sha256, content=True)
        return Response(
            content=content,
            media_type=metadata["media_type"],
            headers={"Content-Disposition": 'attachment; filename="evidence"'},
        )

    @api.post("/v1/drafts")
    async def create_draft(
        request: Request,
        idempotency_key: Annotated[str | None, Header()] = None,
    ):
        if not idempotency_key or not KEY_PATTERN.fullmatch(idempotency_key):
            raise HTTPException(422, "valid_idempotency_key_required")
        body = await parse_request(request, CreateDraft)
        value, created = store.create_draft(body, idempotency_key)
        return JSONResponse(status_code=201 if created else 200, content=value)

    @api.get("/v1/drafts")
    def list_drafts(
        limit: Annotated[int, Query(ge=1, le=100)] = 20,
        offset: Annotated[int, Query(ge=0, le=100_000)] = 0,
        status: Literal["needs_review", "confirmed", "voided"] | None = None,
    ):
        return {
            "drafts": store.list_drafts(limit, offset, status),
            "limit": limit,
            "offset": offset,
            "status": status,
        }

    @api.get("/v1/drafts/{draft_id}")
    def get_draft(draft_id: str):
        return store.get_draft(draft_id)

    @api.get("/v1/drafts/{draft_id}/intake-context", dependencies=[Depends(auth.reviewer)])
    def draft_intake_context(draft_id: str):
        return intakes.draft_context(draft_id)

    @api.put("/v1/drafts/{draft_id}/review", dependencies=[Depends(auth.reviewer)])
    async def review(draft_id: str, request: Request):
        return store.review(draft_id, await parse_request(request, ReviewDraft))

    @api.get("/v1/drafts/{draft_id}/history")
    def history(draft_id: str):
        return {"revisions": store.history(draft_id)}

    @api.put("/v2/drafts/{draft_id}/review", dependencies=[Depends(auth.reviewer)])
    async def review_receipt(draft_id: str, request: Request):
        return store.review_receipt(draft_id, await parse_request(request, ReviewReceipt))

    @api.post("/v2/drafts/{draft_id}/confirm", dependencies=[Depends(auth.reviewer)])
    async def confirm(draft_id: str, request: Request):
        key = request.headers.get("idempotency-key", "")
        if not KEY_PATTERN.fullmatch(key):
            raise HTTPException(422, "valid_idempotency_key_required")
        value, created = store.confirm(draft_id, await parse_request(request, ConfirmReceipt), key)
        return JSONResponse(status_code=201 if created else 200, content=value)

    def check_dates(date_from, date_to):
        try:
            for value in (date_from, date_to):
                if value is not None:
                    if not re.fullmatch(r"[0-9]{4}-[0-9]{2}-[0-9]{2}", value):
                        raise ValueError
                    date.fromisoformat(value)
            if date_from is not None and date_to is not None and date_from > date_to:
                raise ValueError
        except ValueError as error:
            raise HTTPException(422, "invalid_date_range") from error

    @api.get("/v1/ledger")
    def ledger_list(
        limit: Annotated[int, Query(ge=1, le=100)] = 20,
        offset: Annotated[int, Query(ge=0, le=100_000)] = 0,
        q: Annotated[str | None, Query(max_length=500)] = None,
        date_from: Annotated[str | None, Query(max_length=10)] = None,
        date_to: Annotated[str | None, Query(max_length=10)] = None,
        status: Literal["active", "voided"] | None = None,
    ):
        check_dates(date_from, date_to)
        return analysis.list_ledger(
            limit, offset, q=q, date_from=date_from, date_to=date_to, status=status
        )

    @api.get("/v1/ledger/{ledger_id}")
    def ledger_detail(ledger_id: str):
        return store.get_ledger(ledger_id)

    @api.post("/v1/ledger/{ledger_id}/void", dependencies=[Depends(auth.reviewer)])
    async def void(ledger_id: str, request: Request):
        key = request.headers.get("idempotency-key", "")
        if not KEY_PATTERN.fullmatch(key):
            raise HTTPException(422, "valid_idempotency_key_required")
        value, _created = store.void(ledger_id, await parse_request(request, VoidReceipt), key)
        return value

    @api.get("/v1/products")
    def products(
        query: Annotated[str | None, Query(max_length=500)] = None,
        limit: Annotated[int, Query(ge=1, le=100)] = 20,
        offset: Annotated[int, Query(ge=0, le=100_000)] = 0,
    ):
        return analysis.list_products(query, limit, offset)

    @api.post("/v1/products", dependencies=[Depends(auth.reviewer)])
    async def create_product(request: Request):
        key = request.headers.get("idempotency-key", "")
        if not KEY_PATTERN.fullmatch(key):
            raise HTTPException(422, "valid_idempotency_key_required")
        value, created = analysis.create_product(await parse_request(request, CreateProduct), key)
        return JSONResponse(status_code=201 if created else 200, content=value)

    @api.get("/v1/ledger/{ledger_id}/price-review")
    def get_price_review(ledger_id: str):
        return analysis.get_price_review(ledger_id)

    @api.put("/v1/ledger/{ledger_id}/price-review", dependencies=[Depends(auth.reviewer)])
    async def review_prices(ledger_id: str, request: Request):
        return analysis.review_prices(ledger_id, await parse_request(request, ReviewPrices))

    @api.get("/v1/ledger/{ledger_id}/price-review/history")
    def price_history(ledger_id: str):
        return analysis.price_history(ledger_id)

    @api.get("/v1/products/{product_id}/prices")
    def product_prices(
        product_id: str,
        limit: Annotated[int, Query(ge=1, le=100)] = 20,
        offset: Annotated[int, Query(ge=0, le=100_000)] = 0,
        date_from: Annotated[str | None, Query(max_length=10)] = None,
        date_to: Annotated[str | None, Query(max_length=10)] = None,
    ):
        check_dates(date_from, date_to)
        return analysis.product_prices(product_id, limit, offset, date_from, date_to)

    @api.get("/v1/overview")
    def overview(
        date_from: Annotated[str | None, Query(max_length=10)] = None,
        date_to: Annotated[str | None, Query(max_length=10)] = None,
    ):
        check_dates(date_from, date_to)
        return analysis.overview(date_from, date_to)

    @api.get("/v1/mobile/release")
    def mobile_release_metadata():
        if mobile_release is None:
            raise HTTPException(404, "mobile_release_unavailable")
        return {**mobile_release.metadata, "download_url": root_path + "/v1/mobile/apk"}

    @api.get("/v1/mobile/apk")
    def mobile_apk():
        if mobile_release is None:
            raise HTTPException(404, "mobile_release_unavailable")
        return FileResponse(
            mobile_release.apk,
            media_type="application/vnd.android.package-archive",
            filename=f"PixelReceipt-{mobile_release.metadata['version_name']}.apk",
        )

    @api.get("/v1/recognition")
    async def recognition_capabilities():
        await recognition.refresh_readiness()
        return recognition.capabilities()

    @api.post("/v1/intakes")
    async def create_intake(request: Request):
        key = request.headers.get("idempotency-key", "")
        if not KEY_PATTERN.fullmatch(key):
            raise HTTPException(422, "valid_idempotency_key_required")
        body = await parse_request(request, CreateIntake)
        value, created = await asyncio.to_thread(intakes.create, body, key)
        return JSONResponse(status_code=201 if created else 200, content=value)

    @api.get("/v1/intakes")
    def list_intakes(
        limit: Annotated[int, Query(ge=1, le=100)] = 20,
        offset: Annotated[int, Query(ge=0, le=100_000)] = 0,
    ):
        return {"intakes": intakes.list(limit, offset), "limit": limit, "offset": offset}

    @api.get("/v1/intakes/{intake_id}")
    def get_intake(intake_id: str):
        return intakes.get(intake_id)

    @api.get("/v1/devices", dependencies=[Depends(auth.reviewer)])
    def devices():
        return {"devices": auth.list_devices()}

    @api.delete("/v1/devices/{device_id}", dependencies=[Depends(auth.reviewer)])
    def revoke_device(device_id: str):
        auth.revoke_device(device_id)
        return {"status": "revoked"}

    @app.post("/v1/mobile/login")
    async def mobile_login(request: Request):
        return await auth.mobile_login(request, parse_request)

    @app.post("/v2/mobile/login")
    async def native_mobile_login(request: Request):
        return await auth.mobile_login(request, parse_request, reviewer_session=True)

    @api.post("/v1/mobile/logout")
    @api.post("/v2/mobile/logout")
    def mobile_logout(request: Request):
        return auth.mobile_logout(request)

    @api.get("/v1/session")
    def session(request: Request):
        return auth.session_response(request)

    app.include_router(api)

    @app.get("/auth/login")
    def login_form(request: Request):
        return auth.login_form(
            next_draft=request.query_params.get("draft"),
            return_to=request.query_params.get("return_to"),
        )

    @app.post("/auth/login")
    async def login(request: Request):
        return await auth.login(request, bounded_body)

    @app.post("/auth/logout")
    def logout(request: Request):
        return auth.logout(request)

    if static_dir is not None:
        static_dir = Path(static_dir)
        if static_dir.is_symlink() or not static_dir.is_dir():
            raise ValueError("static_dir must be an existing non-symlink directory")
        allowed = {
            "index.html",
            "app.css",
            "app.js",
            "values.mjs",
            "api.mjs",
            "demo.mjs",
            "review.mjs",
            "prices.mjs",
        }

        def static_file(name: str):
            if name not in allowed:
                raise HTTPException(404, "not_found")
            path = static_dir / name
            if path.is_symlink() or not path.is_file():
                raise HTTPException(404, "not_found")
            return FileResponse(path)

        @app.get("/")
        def dashboard():
            return static_file("index.html")

        @app.get("/{name}")
        def asset(name: str):
            return static_file(name)

    return app
