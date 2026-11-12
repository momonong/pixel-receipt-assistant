"""Authenticated analytical metadata and manual accounting routes."""

from fastapi import Depends, HTTPException, Request
from fastapi.responses import JSONResponse

from .insight_models import ManualEntry, SaveInsights
from .insight_store import InsightStore


def register_insight_api(api, store, auth, parse_request, key_pattern):
    insights = InsightStore(store)

    def operation_key(request):
        key = request.headers.get("idempotency-key", "")
        if not key_pattern.fullmatch(key):
            raise HTTPException(422, "valid_idempotency_key_required")
        return key

    @api.get("/v1/drafts/{draft_id}/insights")
    def draft_insights(draft_id: str):
        return insights.get(draft_id)

    @api.get("/v1/ledger/{ledger_id}/insights")
    def ledger_insights(ledger_id: str):
        return insights.get(ledger_id, ledger=True)

    @api.put("/v1/drafts/{draft_id}/insights", dependencies=[Depends(auth.reviewer)])
    async def save_draft_insights(draft_id: str, request: Request):
        key = operation_key(request)
        return insights.save(draft_id, await parse_request(request, SaveInsights), key)

    @api.put("/v1/ledger/{ledger_id}/insights", dependencies=[Depends(auth.reviewer)])
    async def save_ledger_insights(ledger_id: str, request: Request):
        key = operation_key(request)
        return insights.save(
            ledger_id, await parse_request(request, SaveInsights), key, ledger=True
        )

    @api.post("/v1/manual-entries", dependencies=[Depends(auth.reviewer)])
    async def create_manual_entry(request: Request):
        key = operation_key(request)
        value, created = insights.create_manual(await parse_request(request, ManualEntry), key)
        return JSONResponse(status_code=201 if created else 200, content=value)

    @api.get("/v1/analysis-operations/{operation_id}", dependencies=[Depends(auth.reviewer)])
    def analysis_operation(operation_id: str):
        if not key_pattern.fullmatch(operation_id):
            raise HTTPException(422, "valid_idempotency_key_required")
        return insights.operation(operation_id)
