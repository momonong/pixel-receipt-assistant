"""Read-only dimensions of immutable ledger burdens and revisioned annotations."""

import hashlib
import re
from datetime import date

from .insights_allocation import analysis_lines
from .models import LONG_MAX
from .spending import bucket_range, merchant_identity, resolve_bucket
from .store import StoreError, canonical, timestamp

CATEGORIES = {
    "food": "食",
    "clothing": "衣",
    "housing": "住",
    "transport": "行",
    "education": "育",
    "leisure": "樂",
    "health": "醫療",
    "household": "日用",
    "other": "其他",
    "unknown": "未分類",
    "unallocated": "待分配",
}
MEALS = {
    "breakfast": "早餐",
    "lunch": "午餐",
    "dinner": "晚餐",
    "late_night": "宵夜",
    "snack": "點心",
    "unknown": "餐別未知",
    "not_applicable": "非餐飲",
}
FILTER_DEFAULTS = dict.fromkeys(
    [
        "date_from",
        "date_to",
        "compare_from",
        "compare_to",
        "category",
        "meal",
        "brand_key",
        "branch_key",
        "city_key",
        "district_key",
        "merchant_key",
        "channel",
        "day_type",
    ]
)
FILTER_DEFAULTS.update(bucket="auto", undated_only=False)
GROUPS = (
    "categories",
    "subcategories",
    "meals",
    "brands",
    "branches",
    "cities",
    "districts",
    "merchants",
    "weekdays",
    "times",
)


def identity_key(value):
    return hashlib.sha256(canonical(value).encode()).hexdigest()


def city_label(value):
    # Only known orthographic aliases, never derive a location from a merchant or GPS.
    return {"台北市": "臺北市", "台中市": "臺中市", "台南市": "臺南市", "台東縣": "臺東縣"}.get(
        value, value
    )


def normalize_filters(query):
    if set(query) - set(FILTER_DEFAULTS):
        raise StoreError(422, "invalid_insight_filters")
    values = FILTER_DEFAULTS | query
    for prefix in ("date", "compare"):
        first, last = values[prefix + "_from"], values[prefix + "_to"]
        for value in (first, last):
            if value is not None:
                if not isinstance(value, str) or not re.fullmatch(
                    r"[0-9]{4}-[0-9]{2}-[0-9]{2}", value
                ):
                    raise StoreError(422, "invalid_date_range")
                try:
                    date.fromisoformat(value)
                except ValueError as error:
                    raise StoreError(422, "invalid_date_range") from error
        if first is not None and last is not None and first > last:
            raise StoreError(422, "invalid_date_range")
        if prefix == "compare" and (first is None) != (last is None):
            raise StoreError(422, "comparison_dates_required")
    if values["bucket"] not in {"auto", "day", "month", "year"}:
        raise StoreError(422, "invalid_insight_bucket")
    for name, options in [
        ("category", CATEGORIES),
        ("meal", MEALS),
        ("channel", {"in_store", "online", "unknown"}),
        ("day_type", {"weekday", "weekend"}),
    ]:
        if values[name] is not None and values[name] not in options:
            raise StoreError(422, "invalid_insight_filters")
    for name in ("brand_key", "branch_key", "city_key", "district_key", "merchant_key"):
        if values[name] is not None:
            if not isinstance(values[name], str) or not re.fullmatch("[0-9a-f]{64}", values[name]):
                raise StoreError(422, "invalid_insight_filters")
    if type(values["undated_only"]) is not bool:
        raise StoreError(422, "invalid_insight_filters")
    if values["undated_only"] and any(
        values[k] is not None for k in ("date_from", "date_to", "compare_from", "compare_to")
    ):
        raise StoreError(422, "undated_only_date_conflict")
    return values


def dimension_values(entry):
    doc = entry["insights"]["document"]
    return {
        "brand_key": identity_key(doc["brand"]),
        "city_key": identity_key(city_label(doc["city"])),
        "merchant_key": merchant_identity(entry["snapshot"]["candidate"]["merchant"])[0],
        "branch_key": identity_key(
            {"brand": doc["brand"], "branch": doc["branch"]} if doc["branch"] else None
        ),
        "district_key": identity_key(
            {"city": city_label(doc["city"]), "district": doc["district"]}
            if doc["district"]
            else None
        ),
    }


def selected_entries(entries, filters):
    selected = []
    for entry in entries:
        if entry["status"] != "active":
            continue
        value = entry["snapshot"]["candidate"]["receipt_date"]
        if filters["undated_only"] and value is not None:
            continue
        if filters["date_from"] is not None and (value is None or value < filters["date_from"]):
            continue
        if filters["date_to"] is not None and (value is None or value > filters["date_to"]):
            continue
        if filters["day_type"]:
            if (
                value is None
                or ("weekend" if date.fromisoformat(value).weekday() >= 5 else "weekday")
                != filters["day_type"]
            ):
                continue
        doc = entry["insights"]["document"]
        if filters["channel"] is not None and doc["channel"] != filters["channel"]:
            continue
        if any(
            filters[name] is not None and key != filters[name]
            for name, key in dimension_values(entry).items()
        ):
            continue
        lines = [
            line
            for line in analysis_lines(entry)
            if (filters["category"] is None or line["category"] == filters["category"])
            and (filters["meal"] is None or line["meal"] == filters["meal"])
        ]
        if lines:
            selected.append((entry, lines))
    return selected


def exact(value):
    if abs(value) > LONG_MAX:
        raise StoreError(422, "summary_amount_out_of_range")
    return value


def summarize(selected):
    result = dict.fromkeys(
        [
            "personal_yuan",
            "self_yuan",
            "gift_yuan",
            "advance_yuan",
            "receipt_count",
            "item_count",
            "explicit_discount_yuan",
            "missing_item_name_count",
        ],
        0,
    )
    for entry, lines in selected:
        result["receipt_count"] += 1
        for purpose in ("personal", "self", "gift", "advance"):
            result[purpose + "_yuan"] += sum(line[purpose] for line in lines)
        result["item_count"] += sum(line["position"] is not None for line in lines)
        result["missing_item_name_count"] += sum(line["missing_name"] for line in lines)
        result["explicit_discount_yuan"] += sum(
            a["amount_yuan"]
            for a in entry["snapshot"]["candidate"]["adjustments"]
            if a["direction"] == "subtract"
        )
    for value in result.values():
        exact(value)
    return result


def meal_identity(entry, line):
    receipt_date = entry["snapshot"]["candidate"]["receipt_date"]
    # One receipt + meal is one meal before considering cross-receipt labels.
    # A single label also covers its unlabelled rows. Conflicting labels preserve
    # the original one meal instead of choosing one arbitrarily or splitting it.
    shared = {
        item["meal_group"]
        for item in entry["insights"]["document"]["items"]
        if item["category"] == "food" and item["meal"] == line["meal"] and item["meal_group"]
    }
    if receipt_date and len(shared) == 1:
        return ("shared", receipt_date, next(iter(shared)), line["meal"])
    return ("receipt", entry["id"], line["meal"])


def time_group(value):
    if value is None:
        return "unknown", "時間未知"
    hour = int(value[:2])
    return (
        ("night", "凌晨")
        if hour < 6
        else ("morning", "上午")
        if hour < 12
        else ("afternoon", "下午")
        if hour < 18
        else ("evening", "晚上")
    )


def grouped(selected):
    groups = {name: {} for name in GROUPS}
    for entry, lines in selected:
        doc = entry["insights"]["document"]
        receipt_date = entry["snapshot"]["candidate"]["receipt_date"]
        weekday = date.fromisoformat(receipt_date).weekday() if receipt_date else None
        keys = dimension_values(entry)
        for line in lines:
            fields = {
                "categories": (line["category"], CATEGORIES[line["category"]]),
                "subcategories": ("unallocated", "待分配")
                if line["category"] == "unallocated"
                else (
                    identity_key([line["category"], line["subcategory"]]),
                    line["subcategory"] or CATEGORIES[line["category"]] + "／未細分",
                ),
                "meals": ("unallocated", "待分配")
                if line["category"] == "unallocated"
                else (line["meal"], MEALS[line["meal"]]),
                "brands": (keys["brand_key"], doc["brand"]),
                "branches": (
                    keys["branch_key"],
                    " ".join(filter(None, [doc["brand"], doc["branch"]]))
                    if doc["branch"]
                    else None,
                ),
                "cities": (keys["city_key"], city_label(doc["city"])),
                "districts": (
                    keys["district_key"],
                    " ".join(filter(None, [city_label(doc["city"]), doc["district"]]))
                    if doc["district"]
                    else None,
                ),
                "merchants": (*merchant_identity(entry["snapshot"]["candidate"]["merchant"]),),
                "weekdays": (
                    str(weekday) if weekday is not None else "unknown",
                    "週" + "一二三四五六日"[weekday] if weekday is not None else "日期未知",
                ),
                "times": time_group(doc["transaction_time"]),
            }
            for name, (key, label) in fields.items():
                group = groups[name].setdefault(
                    key,
                    dict(
                        key=key,
                        label=label,
                        personal_yuan=0,
                        receipts=set(),
                        item_count=0,
                        meals=set(),
                    ),
                )
                group["personal_yuan"] += line["personal"]
                group["receipts"].add(entry["id"])
                group["item_count"] += line["position"] is not None
                if (
                    name == "meals"
                    and line["category"] == "food"
                    and line["meal"] != "not_applicable"
                    and (line["personal_participation"] or line["personal"] > 0)
                ):
                    group["meals"].add(meal_identity(entry, line))
    result = {}
    for name, values in groups.items():
        if len(values) > 10000:
            raise StoreError(422, "insight_group_limit_exceeded")
        rows = []
        for group in values.values():
            amount = exact(group["personal_yuan"])
            count = len(group["receipts"])
            row = {
                "key": group["key"],
                "label": group["label"],
                "personal_yuan": amount,
                "receipt_count": count,
                "item_count": group["item_count"],
            }
            denominator = len(group["meals"]) if name == "meals" else count
            row["average_yuan"] = (
                {"numerator": amount, "denominator": denominator}
                if denominator and amount >= 0 and group["key"] != "unallocated"
                else None
            )
            if name == "meals":
                row["meal_count"] = denominator
            rows.append(row)
        result[name] = sorted(rows, key=lambda g: (-g["personal_yuan"], g["key"]))
    return result


def coverage(selected):
    result = dict.fromkeys(
        [
            "uncategorized_personal_yuan",
            "unknown_city_personal_yuan",
            "unknown_meal_personal_yuan",
            "undated_receipt_count",
            "undated_personal_yuan",
            "estimated_date_receipt_count",
            "pending_price_receipt_count",
            "unallocated_personal_yuan",
        ],
        0,
    )
    for entry, lines in selected:
        amount = sum(line["personal"] for line in lines)
        doc = entry["insights"]["document"]
        result["uncategorized_personal_yuan"] += sum(
            line["personal"] for line in lines if line["category"] == "unknown"
        )
        result["unknown_meal_personal_yuan"] += sum(
            line["personal"]
            for line in lines
            if line["category"] == "food" and line["meal"] == "unknown"
        )
        result["unallocated_personal_yuan"] += sum(
            line["personal"] for line in lines if line["category"] == "unallocated"
        )
        result["unknown_city_personal_yuan"] += amount if doc["city"] is None else 0
        if entry["snapshot"]["candidate"]["receipt_date"] is None:
            result["undated_receipt_count"] += 1
            result["undated_personal_yuan"] += amount
        result["estimated_date_receipt_count"] += doc["date_source"] == "default_today"
        result["pending_price_receipt_count"] += entry.get("pending_price", False)
    return {key: exact(value) for key, value in result.items()}


def project_insights(entries, query):
    filters = normalize_filters(query)
    selected = selected_entries(entries, filters)
    totals = summarize(selected)
    selected_ledger = [entry for entry, _ in selected]
    bucket, start, end = resolve_bucket(
        selected_ledger, filters["date_from"], filters["date_to"], filters["bucket"]
    )
    series = {}
    for entry, lines in selected:
        value = entry["snapshot"]["candidate"]["receipt_date"]
        if value is None:
            continue
        first, last, label = bucket_range(date.fromisoformat(value), bucket)
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
        point["personal_yuan"] = exact(
            point["personal_yuan"] + sum(line["personal"] for line in lines)
        )
        point["receipt_count"] += 1
    comparison = None
    if filters["compare_from"] is not None:
        previous_filters = filters | dict(
            date_from=filters["compare_from"],
            date_to=filters["compare_to"],
            compare_from=None,
            compare_to=None,
        )
        previous = summarize(selected_entries(entries, previous_filters))
        comparison = {
            "date_from": filters["compare_from"],
            "date_to": filters["compare_to"],
            "personal_yuan": previous["personal_yuan"],
            "receipt_count": previous["receipt_count"],
            "delta_personal_yuan": exact(totals["personal_yuan"] - previous["personal_yuan"]),
        }
    return {
        "scope": "active_confirmed_ledger",
        "currency": "TWD",
        "amount_unit": "TWD_YUAN",
        "filters": filters,
        "generated_at": timestamp(),
        "bucket": bucket,
        "summary": totals,
        "comparison": comparison,
        "series": [series[key] for key in sorted(series)],
        "groups": grouped(selected),
        "coverage": coverage(selected),
        "discount_scope": "matched_receipts_including_advances",
    }


def filter_insight_entries(entries, query, limit=20, offset=0):
    if (
        type(limit) is not int
        or type(offset) is not int
        or not 1 <= limit <= 100
        or not 0 <= offset <= 100000
    ):
        raise StoreError(422, "invalid_pagination")
    filters = normalize_filters(query)
    selected = selected_entries(entries, filters)
    page = [
        {**entry, "matched_personal_yuan": exact(sum(line["personal"] for line in lines))}
        for entry, lines in selected[offset : offset + limit]
    ]
    return {
        "entries": page,
        "total_count": len(selected),
        "limit": limit,
        "offset": offset,
        "filters": filters,
    }
