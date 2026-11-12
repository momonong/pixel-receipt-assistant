package com.momonong.pixelreceipt.mobile

import com.google.gson.JsonObject
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

internal enum class SpendingPeriod(val label: String) {
    MonthToDate("本月"), PreviousMonth("上月"), YearToDate("今年"), All("全部"), Custom("自訂")
}
internal data class SpendingSelection(val from: LocalDate? = null, val to: LocalDate? = null, val merchantKey: String? = null, val undated: Boolean = false)

internal data class SpendingQuery(val dateFrom: LocalDate? = null, val dateTo: LocalDate? = null, val compareFrom: LocalDate? = null, val compareTo: LocalDate? = null) {
    init {
        require((dateFrom == null) == (dateTo == null) && (compareFrom == null) == (compareTo == null))
        require(listOfNotNull(dateFrom,dateTo,compareFrom,compareTo).all { it.year in 1..9999 })
        require(dateFrom == null || dateFrom <= dateTo)
        require(compareFrom == null || (dateFrom != null && compareFrom <= compareTo))
    }
    fun summaryPath(): String = "v1/spending?bucket=auto" + dates("date",dateFrom,dateTo) + dates("compare",compareFrom,compareTo)
    fun ledgerPath(selection: SpendingSelection = SpendingSelection(), offset: Int = 0): String {
        require(offset in 0..100_000)
        require(selection.merchantKey == null || selection.merchantKey.matches(Regex("[0-9a-f]{64}")))
        require((selection.from == null) == (selection.to == null))
        require(!selection.undated || (dateFrom == null && selection.from == null))
        if (selection.from != null) {
            require(selection.from <= selection.to)
            require(dateFrom == null || (selection.from >= dateFrom && selection.to!! <= dateTo))
        }
        return "v1/ledger?limit=20&offset=$offset&status=active" +
            dates("date",selection.from ?: dateFrom,selection.to ?: dateTo) +
            (selection.merchantKey?.let { "&merchant_key=$it" } ?: "") + if(selection.undated) "&undated_only=true" else ""
    }
    companion object {
        fun forPeriod(period: SpendingPeriod, today: LocalDate = LocalDate.now()): SpendingQuery {
            val previous=today.minusMonths(1)
            return when(period) {
                SpendingPeriod.MonthToDate -> SpendingQuery(today.withDayOfMonth(1),today,previous.withDayOfMonth(1),previous)
                SpendingPeriod.PreviousMonth -> { val before=previous.minusMonths(1); SpendingQuery(previous.withDayOfMonth(1),previous.withDayOfMonth(previous.lengthOfMonth()),before.withDayOfMonth(1),before.withDayOfMonth(before.lengthOfMonth())) }
                SpendingPeriod.YearToDate -> SpendingQuery(today.withDayOfYear(1),today,today.minusYears(1).withDayOfYear(1),today.minusYears(1))
                SpendingPeriod.All -> SpendingQuery()
                SpendingPeriod.Custom -> error("Custom range needs calendar dates")
            }
        }
    }
}
private fun dates(prefix: String, from: LocalDate?, to: LocalDate?): String = if(from == null) "" else "&${prefix}_from=$from&${prefix}_to=$to"

/** No URL decoding, arbitrary paths, extra keys or credentials in query strings. */
internal fun allowedSpendingRequest(path: String): Boolean = runCatching {
    require(path.length <= 512 && path.count { it=='?' }==1)
    val route=path.substringBefore('?');require(route in setOf("v1/spending","v1/ledger"))
    val pieces=path.substringAfter('?').split('&').map { part ->
        require(part.count { it=='=' }==1);part.substringBefore('=') to part.substringAfter('=')
    }
    require(pieces.map {it.first}.distinct().size==pieces.size)
    val values=pieces.toMap()
    val common=setOf("date_from","date_to")
    val allowed=if(route=="v1/spending") common+setOf("bucket","compare_from","compare_to") else common+setOf("limit","offset","status","merchant_key","undated_only")
    require(values.keys.all {it in allowed})
    fun date(name:String)=values[name]?.let { require(it.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")));LocalDate.parse(it) }
    val query=SpendingQuery(date("date_from"),date("date_to"),date("compare_from"),date("compare_to"))
    if(route=="v1/spending") require(values["bucket"]=="auto")
    else {
        require(values["limit"]=="20" && values["status"]=="active")
        val offset=values["offset"]!!;require(offset.matches(Regex("0|[1-9][0-9]{0,5}")) && offset.toInt()<=100_000)
        require(!values.containsKey("undated_only") || values["undated_only"]=="true")
        query.ledgerPath(SpendingSelection(merchantKey=values["merchant_key"],undated=values["undated_only"]=="true"),offset.toInt())
    }
    true
}.getOrDefault(false)

internal data class SpendingSummary(val printedYuan: Long, val allocatedYuan: Long, val selfYuan: Long, val giftYuan: Long, val advanceYuan: Long, val personalYuan: Long, val differenceYuan: Long, val discountYuan: Long, val receiptCount: Long, val missingItemNames: Long, val receiptsMissingNames: Long)
internal data class SpendingPoint(val from: LocalDate, val to: LocalDate, val label: String, val personalYuan: Long, val receiptCount: Long)
internal data class SpendingMerchant(val key: String, val name: String?, val personalYuan: Long, val receiptCount: Long)
internal data class SpendingComparison(val from: LocalDate, val to: LocalDate, val personalYuan: Long, val receiptCount: Long, val deltaPersonalYuan: Long)
internal data class SpendingReport(val query: SpendingQuery, val bucket: String, val generatedAt: Instant, val summary: SpendingSummary, val comparison: SpendingComparison?, val series: List<SpendingPoint>, val merchants: List<SpendingMerchant>, val undatedCount: Long, val undatedPersonalYuan: Long, val excludedUndatedCount: Long)

/** The server authors ledger totals. This reader only rejects incoherent or wrong-scope responses. */
internal fun parseSpending(raw: String, query: SpendingQuery): SpendingReport {
    val value=jsonObject(raw)
    require(value.text("scope")=="active_confirmed_ledger" && value.text("currency")=="TWD" && value.text("amount_unit")=="TWD_YUAN")
    require(spendingDate(value,"date_from")==query.dateFrom && spendingDate(value,"date_to")==query.dateTo)
    val bucket=value.text("bucket");require(bucket in setOf("day","month","year"))
    val generated=Instant.parse(value.text("generated_at"))
    val totals=value.getAsJsonObject("summary")
    val sum=SpendingSummary(
        strictLong(totals["printed_total_yuan"]),strictLong(totals["allocated_total_yuan"]),strictLong(totals["self_yuan"]),strictLong(totals["gift_yuan"]),strictLong(totals["advance_yuan"]),strictLong(totals["personal_yuan"]),signedSpendingLong(totals["difference_yuan"]),strictLong(totals["explicit_discount_yuan"]),strictLong(totals["receipt_count"]),strictLong(totals["missing_item_name_count"]),strictLong(totals["receipts_missing_item_names"]),
    )
    require(Math.addExact(sum.selfYuan,sum.giftYuan)==sum.personalYuan)
    require(Math.addExact(sum.personalYuan,sum.advanceYuan)==sum.allocatedYuan)
    require(Math.addExact(sum.allocatedYuan,sum.differenceYuan)==sum.printedYuan)
    require(sum.receiptsMissingNames<=sum.receiptCount && sum.receiptsMissingNames<=sum.missingItemNames)
    val comparison=value["comparison"].takeUnless {it==null || it.isJsonNull}?.asJsonObject?.let {
        SpendingComparison(spendingDate(it,"date_from")!!,spendingDate(it,"date_to")!!,strictLong(it["personal_yuan"]),strictLong(it["receipt_count"]),signedSpendingLong(it["delta_personal_yuan"]))
    }
    require((comparison==null)==(query.compareFrom==null))
    comparison?.let {require(it.from==query.compareFrom && it.to==query.compareTo && sum.personalYuan-it.personalYuan==it.deltaPersonalYuan)}
    require(value["series"].isJsonArray && value["merchants"].isJsonArray)
    val series=value.array("series").map { element ->
        val item=element.asJsonObject;val from=spendingDate(item,"date_from")!!;val to=spendingDate(item,"date_to")!!
        require(from<=to && (query.dateFrom==null || from>=query.dateFrom) && (query.dateTo==null || to<=query.dateTo))
        val label=item.text("label");require(label==when(bucket){"day"->from.toString();"month"->YearMonth.from(from).toString();else->from.year.toString().padStart(4,'0')})
        SpendingPoint(from,to,label,strictLong(item["personal_yuan"]),strictLong(item["receipt_count"],1))
    }
    require(series.size<=1000 && series.zipWithNext().all {(a,b)->a.to<b.from})
    val merchants=value.array("merchants").map { element ->
        val item=element.asJsonObject;val key=item.text("key");require(key.matches(Regex("[0-9a-f]{64}")))
        val name=item["name"].takeUnless {it==null || it.isJsonNull}?.also {require(it.isJsonPrimitive && it.asJsonPrimitive.isString && it.asString.length<=500)}?.asString
        SpendingMerchant(key,name,strictLong(item["personal_yuan"]),strictLong(item["receipt_count"],1))
    }
    require(merchants.map {it.key}.distinct().size==merchants.size)
    val undated=strictLong(value["undated_receipt_count"]);val undatedAmount=strictLong(value["undated_personal_yuan"])
    require(Math.addExact(exactSum(series.map {it.personalYuan}),undatedAmount)==sum.personalYuan)
    require(Math.addExact(exactSum(series.map {it.receiptCount}),undated)==sum.receiptCount)
    require(exactSum(merchants.map {it.personalYuan})==sum.personalYuan && exactSum(merchants.map {it.receiptCount})==sum.receiptCount)
    return SpendingReport(query,bucket,generated,sum,comparison,series,merchants,undated,undatedAmount,strictLong(value["excluded_undated_receipt_count"]))
}
private fun spendingDate(value: JsonObject, key: String): LocalDate? = value[key].takeUnless {it==null || it.isJsonNull}?.let {
    require(it.isJsonPrimitive && it.asJsonPrimitive.isString && it.asString.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")))
    LocalDate.parse(it.asString).also { date -> require(date.year in 1..9999) }
}
private fun exactSum(values: List<Long>): Long = values.fold(0L) {sum,n->Math.addExact(sum,n)}

/** Signed differences are legal; receipt amounts retain the existing unsigned validator. */
private fun signedSpendingLong(value: com.google.gson.JsonElement?): Long {
    require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isNumber)
    require(value.asString.matches(Regex("0|-?[1-9][0-9]*")))
    return value.asBigDecimal.toBigIntegerExact().longValueExact().also { require(it in -Long.MAX_VALUE..Long.MAX_VALUE) }
}
