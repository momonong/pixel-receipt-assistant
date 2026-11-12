package com.momonong.pixelreceipt.mobile

import java.net.URLDecoder
import java.net.URLEncoder
import java.time.LocalDate

/** New read endpoints use fixed keys, typed values and canonical encoded search text. */
internal fun allowedAnalyticsRead(path: String): Boolean = runCatching {
    require(path.length <= 8192 && path.count { it == '?' } == 1 && '#' !in path)
    val route = path.substringBefore('?')
    val parts = path.substringAfter('?').split('&').map {
        require(it.count { c -> c == '=' } == 1)
        it.substringBefore('=') to it.substringAfter('=')
    }
    require(parts.map { it.first }.distinct().size == parts.size)
    val values = parts.toMap()
    fun pagination() {
        require(values["limit"] == "20")
        val offset = values["offset"] ?: error("Missing offset")
        require(offset.matches(Regex("0|[1-9][0-9]{0,5}")) && offset.toInt() <= 100_000)
    }
    when {
        route in setOf("v1/insights", "v1/insights/entries") -> {
            val fields = setOf("date_from", "date_to", "compare_from", "compare_to", "bucket", "category", "meal", "brand_key", "branch_key", "city_key", "district_key", "merchant_key", "channel", "day_type", "undated_only")
            require(values.keys.all { it in fields || (route.endsWith("/entries") && it in setOf("limit", "offset")) })
            require(values["bucket"] == "auto")
            require(values["undated_only"] == null || values["undated_only"] in setOf("true", "false"))
            fun day(key: String): LocalDate? = values[key]?.let {
                require(it.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}"))); LocalDate.parse(it)
            }
            val query = InsightQuery(day("date_from"), day("date_to"), day("compare_from"), day("compare_to"),
                values["category"], values["meal"], values["brand_key"], values["branch_key"], values["city_key"], values["district_key"], values["channel"], values["day_type"], values["undated_only"] == "true", merchantKey = values["merchant_key"])
            query.filters()
            require(!query.undatedOnly || query.compareFrom == null)
            if (route.endsWith("/entries")) pagination()
        }
        route == "v1/products" -> {
            require(values.keys == setOf("query", "limit", "offset")); pagination()
            val encoded = values.getValue("query")
            val decoded = URLDecoder.decode(encoded, Charsets.UTF_8.name())
            require(decoded.length <= 500 && decoded.none(Char::isISOControl))
            require(URLEncoder.encode(decoded, Charsets.UTF_8.name()) == encoded)
        }
        route.matches(Regex("v1/products/[0-9a-fA-F-]{36}/prices")) -> {
            require(validUuid(route.split('/')[2]) && values.keys == setOf("limit", "offset")); pagination()
        }
        else -> error("Unrecognized read route")
    }
    true
}.getOrDefault(false)
