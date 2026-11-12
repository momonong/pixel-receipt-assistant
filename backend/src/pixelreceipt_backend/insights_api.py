"""Authenticated multi-dimensional read API, with one SQLite snapshot per query."""

from typing import Annotated

from fastapi import Depends, Query, Request

from .analysis_store import AnalysisStore
from .insight_store import InsightStore
from .insights_projection import filter_insight_entries, normalize_filters, project_insights
from .store import StoreError


def insight_query(
    request: Request,
    date_from: Annotated[str | None, Query(max_length=10)] = None,
    date_to: Annotated[str | None, Query(max_length=10)] = None,
    compare_from: Annotated[str | None, Query(max_length=10)] = None,
    compare_to: Annotated[str | None, Query(max_length=10)] = None,
    bucket: Annotated[str, Query(max_length=10)] = "auto",
    category: Annotated[str | None, Query(max_length=32)] = None,
    meal: Annotated[str | None, Query(max_length=32)] = None,
    brand_key: Annotated[str | None, Query(max_length=64)] = None,
    branch_key: Annotated[str | None, Query(max_length=64)] = None,
    city_key: Annotated[str | None, Query(max_length=64)] = None,
    district_key: Annotated[str | None, Query(max_length=64)] = None,
    merchant_key: Annotated[str | None, Query(max_length=64)] = None,
    channel: Annotated[str | None, Query(max_length=16)] = None,
    day_type: Annotated[str | None, Query(max_length=16)] = None,
    undated_only: bool = False,
):
    filters = dict(
        date_from=date_from,
        date_to=date_to,
        compare_from=compare_from,
        compare_to=compare_to,
        bucket=bucket,
        category=category,
        meal=meal,
        brand_key=brand_key,
        branch_key=branch_key,
        city_key=city_key,
        district_key=district_key,
        merchant_key=merchant_key,
        channel=channel,
        day_type=day_type,
        undated_only=undated_only,
    )
    allowed = set(filters) | (
        {"limit", "offset"} if request.url.path.endswith("/entries") else set()
    )
    supplied = request.query_params.multi_items()
    if any(name not in allowed for name, _ in supplied) or len(supplied) != len(
        {name for name, _ in supplied}
    ):
        raise StoreError(422, "invalid_insight_filters")
    return normalize_filters(filters)


def load_entries(store, connection):
    analysis = AnalysisStore(store)
    metadata = InsightStore(store)
    entries = []
    for entry in analysis._filtered_ledger(connection, status="active"):
        entry["insights"] = metadata.document(connection, entry)
        price = analysis._price_review(connection, entry["id"], ledger=entry)
        entry["pending_price"] = any(
            not line["comparison_eligible"] for line in price["line_prices"]
        )
        entries.append(entry)
    return entries


def register_insights_api(api, store):
    @api.get("/v1/insights")
    def insights(filters: Annotated[dict, Depends(insight_query)]):
        with store.connection() as connection:
            return project_insights(load_entries(store, connection), filters)

    @api.get("/v1/insights/entries")
    def entries(
        filters: Annotated[dict, Depends(insight_query)],
        limit: Annotated[int, Query(ge=1, le=100)] = 20,
        offset: Annotated[int, Query(ge=0, le=100_000)] = 0,
    ):
        with store.connection() as connection:
            return filter_insight_entries(load_entries(store, connection), filters, limit, offset)
