"""Deterministic, integer-only spending projections of immutable ledger snapshots."""

import calendar
import hashlib
from datetime import date

from .models import LONG_MAX
from .store import StoreError, canonical, timestamp

AMOUNT_KEYS = (
    "printed_total_yuan",
    "allocated_total_yuan",
    "self_yuan",
    "gift_yuan",
    "advance_yuan",
    "personal_yuan",
    "difference_yuan",
)


def merchant_identity(name):
    """Only empty identities coalesce; significant original text is preserved."""
    name = None if name is None or not name.strip() else name
    return hashlib.sha256(canonical(name).encode()).hexdigest(), name


def check_amounts(values):
    if any(abs(value) > LONG_MAX for value in values):
        raise StoreError(422, "summary_amount_out_of_range")


def summary(entries):
    result = dict.fromkeys(AMOUNT_KEYS, 0)
    result.update(
        explicit_discount_yuan=0,
        receipt_count=0,
        missing_item_name_count=0,
        receipts_missing_item_names=0,
    )
    for entry in entries:
        snapshot = entry["snapshot"]
        result["receipt_count"] += 1
        result["missing_item_name_count"] += entry["missing_item_name_count"]
        result["receipts_missing_item_names"] += bool(entry["missing_item_name_count"])
        for key in AMOUNT_KEYS:
            result[key] += snapshot["calculation"][key]
        result["explicit_discount_yuan"] += sum(
            adjustment["amount_yuan"]
            for adjustment in snapshot["candidate"]["adjustments"]
            if adjustment["direction"] == "subtract"
        )
    check_amounts(result[key] for key in (*AMOUNT_KEYS, "explicit_discount_yuan"))
    return result


def in_range(entry, date_from, date_to):
    value = entry["snapshot"]["candidate"]["receipt_date"]
    return (date_from is None or (value is not None and value >= date_from)) and (
        date_to is None or (value is not None and value <= date_to)
    )


def bucket_range(value, bucket):
    if bucket == "day":
        return value, value, value.isoformat()
    if bucket == "month":
        return (
            date(value.year, value.month, 1),
            date(value.year, value.month, calendar.monthrange(value.year, value.month)[1]),
            f"{value.year:04d}-{value.month:02d}",
        )
    return date(value.year, 1, 1), date(value.year, 12, 31), f"{value.year:04d}"


def resolve_bucket(entries, date_from, date_to, bucket):
    dates = [
        date.fromisoformat(entry["snapshot"]["candidate"]["receipt_date"])
        for entry in entries
        if entry["snapshot"]["candidate"]["receipt_date"] is not None
    ]
    start = date.fromisoformat(date_from) if date_from else min(dates, default=None)
    end = date.fromisoformat(date_to) if date_to else max(dates, default=None)
    # Empty or one-sided queries still have a finite envelope; no invented time series.
    start = start or end
    end = end or start
    if start is None:
        return ("day" if bucket == "auto" else bucket), None, None
    if start > end:
        start, end = (start, start) if date_from else (end, end)
    months = (end.year - start.year) * 12 + end.month - start.month + 1
    if bucket == "auto":
        bucket = "day" if (end - start).days < 90 else "month" if months <= 120 else "year"
    count = (
        (end - start).days + 1
        if bucket == "day"
        else months
        if bucket == "month"
        else end.year - start.year + 1
    )
    if count > 1000:
        raise StoreError(422, "spending_bucket_limit_exceeded")
    return bucket, start, end


def spending_projection(
    entries, *, date_from=None, date_to=None, compare_from=None, compare_to=None, bucket="auto"
):
    selected = [entry for entry in entries if in_range(entry, date_from, date_to)]
    totals = summary(selected)
    bucket, start, end = resolve_bucket(selected, date_from, date_to, bucket)
    merchants = {}
    series = {}
    undated_count = undated_amount = 0
    for entry in selected:
        candidate = entry["snapshot"]["candidate"]
        amount = entry["snapshot"]["calculation"]["personal_yuan"]
        key, name = merchant_identity(candidate["merchant"])
        merchant = merchants.setdefault(
            key, dict(key=key, name=name, personal_yuan=0, receipt_count=0)
        )
        merchant["personal_yuan"] += amount
        merchant["receipt_count"] += 1
        if candidate["receipt_date"] is None:
            undated_count += 1
            undated_amount += amount
            continue
        first, last, label = bucket_range(date.fromisoformat(candidate["receipt_date"]), bucket)
        point = series.setdefault(
            label,
            dict(
                date_from=max(first, start).isoformat(),
                date_to=min(last, end).isoformat(),
                label=label,
                personal_yuan=0,
                receipt_count=0,
            ),
        )
        point["personal_yuan"] += amount
        point["receipt_count"] += 1
    comparison = None
    if compare_from is not None:
        previous = summary(
            [entry for entry in entries if in_range(entry, compare_from, compare_to)]
        )
        delta = totals["personal_yuan"] - previous["personal_yuan"]
        check_amounts([delta])
        comparison = dict(
            date_from=compare_from,
            date_to=compare_to,
            personal_yuan=previous["personal_yuan"],
            receipt_count=previous["receipt_count"],
            delta_personal_yuan=delta,
        )
    undated_total = sum(entry["snapshot"]["candidate"]["receipt_date"] is None for entry in entries)
    return dict(
        scope="active_confirmed_ledger",
        currency="TWD",
        amount_unit="TWD_YUAN",
        date_from=date_from,
        date_to=date_to,
        bucket=bucket,
        generated_at=timestamp(),
        summary=totals,
        comparison=comparison,
        series=[series[key] for key in sorted(series)],
        merchants=sorted(merchants.values(), key=lambda m: (-m["personal_yuan"], m["key"])),
        undated_receipt_count=undated_count,
        undated_personal_yuan=undated_amount,
        excluded_undated_receipt_count=undated_total - undated_count,
    )
