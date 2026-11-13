package com.momonong.pixelreceipt.mobile

import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonArray
import java.time.LocalDate
import java.time.Instant

internal val insightCategories=linkedMapOf("food" to "食", "clothing" to "衣", "housing" to "住", "transport" to "行", "education" to "育", "leisure" to "樂", "health" to "醫療", "household" to "日用", "other" to "其他", "unknown" to "未分類")
internal val insightMeals=linkedMapOf("breakfast" to "早餐","lunch" to "午餐","dinner" to "晚餐","late_night" to "宵夜","snack" to "點心","unknown" to "餐別待補","not_applicable" to "非餐飲")
internal data class InsightQuery(
    val dateFrom:LocalDate?=null,val dateTo:LocalDate?=null,val compareFrom:LocalDate?=null,val compareTo:LocalDate?=null,
    val category:String?=null,val meal:String?=null,val brandKey:String?=null,val branchKey:String?=null,val cityKey:String?=null,val districtKey:String?=null,
    val channel:String?=null,val dayType:String?=null,val undatedOnly:Boolean=false,val merchantKey:String?=null,
){
    fun filters():JsonObject {
        require((dateFrom==null)==(dateTo==null) && (compareFrom==null)==(compareTo==null))
        require(dateFrom==null || dateFrom<=dateTo); require(compareFrom==null || compareFrom<=compareTo)
        require(listOfNotNull(dateFrom,dateTo,compareFrom,compareTo).all{it.year in 1..9999})
        require(category==null || category in insightCategories);require(meal==null || meal in insightMeals)
        require(listOfNotNull(brandKey,branchKey,cityKey,districtKey,merchantKey).all{it.matches(Regex("[0-9a-f]{64}"))})
        require(channel==null || channel in setOf("in_store","online","unknown"));require(dayType==null || dayType in setOf("weekday","weekend"))
        require(!undatedOnly || dateFrom==null)
        return JsonObject().apply {
            listOf("date_from" to dateFrom?.toString(),"date_to" to dateTo?.toString(),"compare_from" to compareFrom?.toString(),"compare_to" to compareTo?.toString(),"category" to category,"meal" to meal,"brand_key" to brandKey,"branch_key" to branchKey,"city_key" to cityKey,"district_key" to districtKey,"channel" to channel,"day_type" to dayType,"merchant_key" to merchantKey).forEach{(k,v)->addProperty(k,v)}
            addProperty("bucket","auto");addProperty("undated_only",undatedOnly)
        }
    }
    fun withPeriod(period:SpendingPeriod,today:LocalDate=LocalDate.now()):InsightQuery {
        val dates=SpendingQuery.forPeriod(period,today)
        return copy(dateFrom=dates.dateFrom,dateTo=dates.dateTo,compareFrom=dates.compareFrom,compareTo=dates.compareTo,undatedOnly=false)
    }
    fun path(entries:Boolean=false,offset:Int=0):String {
        require(offset in 0..100000)
        return (if(entries)"v1/insights/entries?limit=20&offset=$offset&" else "v1/insights?")+filters().entrySet().filter{!it.value.isJsonNull}.joinToString("&"){"${it.key}=${it.value.asString}"}
    }
    companion object { fun month(today:LocalDate=LocalDate.now()):InsightQuery {val old=today.minusMonths(1);return InsightQuery(today.withDayOfMonth(1),today,old.withDayOfMonth(1),old)} }
}
internal data class InsightGroup(val key:String,val label:String?,val personal:Long,val receipts:Long,val items:Long,val meals:Long?=null,val average:String?=null,val from:LocalDate?=null,val to:LocalDate?=null)
internal data class InsightReport(val query:InsightQuery,val summary:Map<String,Long>,val comparisonDelta:Long?,val groups:Map<String,List<InsightGroup>>,val coverage:Map<String,Long>,val series:List<InsightGroup>,val bucket:String="day")
internal fun insightLong(value:JsonElement?,signed:Boolean=false):Long {
    require(value?.isJsonPrimitive==true && value.asJsonPrimitive.isNumber)
    require(value.asString.matches(Regex(if(signed)"0|-?[1-9][0-9]*" else "0|[1-9][0-9]*")))
    return value.asBigDecimal.toBigIntegerExact().longValueExact().also{require(it>=-Long.MAX_VALUE)}
}
internal fun checkInsightFilters(value:JsonObject,query:InsightQuery){
    val filters=value.getAsJsonObject("filters") ?: error("Missing filters")
    query.filters().entrySet().forEach{(key,expected)-> require((filters[key] ?: JsonNull.INSTANCE)==expected){"Wrong $key scope"} }
}
internal fun parseInsightReport(raw:String,query:InsightQuery):InsightReport {
    val value=jsonObject(raw)
    require(value.text("scope")=="active_confirmed_ledger" && value.text("currency")=="TWD" && value.text("amount_unit")=="TWD_YUAN")
    checkInsightFilters(value,query);Instant.parse(value.text("generated_at"));require(value.text("bucket") in setOf("day","month","year"))
    val summaryKeys=listOf("personal_yuan","self_yuan","gift_yuan","advance_yuan","receipt_count","item_count","explicit_discount_yuan","missing_item_name_count")
    val summary=summaryKeys.associateWith{insightLong(value.getAsJsonObject("summary")[it])}
    require(Math.addExact(summary.getValue("self_yuan"),summary.getValue("gift_yuan"))==summary.getValue("personal_yuan"))
    val groups=value.getAsJsonObject("groups").entrySet().associate{(dimension,array)->
        require(array.isJsonArray && array.asJsonArray.size()<=10000)
        dimension to array.asJsonArray.map { element ->
            val g=element.asJsonObject;val key=g.text("key");require(key.length in 1..100)
            when(dimension){"categories"->require(key in insightCategories || key=="unallocated");"meals"->require(key in insightMeals || key=="unallocated");"brands","branches","cities","districts","merchants"->require(key.matches(Regex("[0-9a-f]{64}")) || key=="unallocated")}
            val label=g["label"]?.takeUnless{it.isJsonNull}?.asString?.also{require(it.length<=500)}
            val average=g["average_yuan"]?.takeUnless{it.isJsonNull}?.asJsonObject?.let{a-> val n=insightLong(a["numerator"]);val d=insightLong(a["denominator"]);require(d>0);java.math.BigDecimal(n).divide(java.math.BigDecimal(d),1,java.math.RoundingMode.HALF_UP).toPlainString()}
            InsightGroup(key,label,insightLong(g["personal_yuan"],key=="unallocated"),insightLong(g["receipt_count"]),insightLong(g["item_count"]),g["meal_count"]?.takeUnless{it.isJsonNull}?.let{insightLong(it)},average)
        }.also{rows-> require(rows.map{it.key}.distinct().size==rows.size)}
    }
    require(groups.containsKey("categories"))
    groups.values.forEach { rows->require(rows.fold(0L){total,g->Math.addExact(total,g.personal)}==summary.getValue("personal_yuan")) { "Group total differs from report scope" } }
    val coverage=value.getAsJsonObject("coverage").entrySet().associate{(k,v)->k to insightLong(v,k=="unallocated_personal_yuan")}
    val series=value.array("series").map{
        val p=it.asJsonObject;val from=LocalDate.parse(p.text("date_from"));val to=LocalDate.parse(p.text("date_to"))
        require(from<=to && (query.dateFrom==null || from>=query.dateFrom) && (query.dateTo==null || to<=query.dateTo))
        InsightGroup(from.toString(),p.text("label"),insightLong(p["personal_yuan"]),insightLong(p["receipt_count"]),0,from=from,to=to)
    }
    require(series.size<=1000 && series.zipWithNext().all{(a,b)->a.to!!<b.from!!})
    val comparison=value["comparison"]?.takeUnless{it.isJsonNull}?.asJsonObject
    require((comparison==null)==(query.compareFrom==null))
    val delta=comparison?.let{
        require(it.text("date_from")==query.compareFrom.toString() && it.text("date_to")==query.compareTo.toString())
        val previous=insightLong(it["personal_yuan"]);insightLong(it["receipt_count"])
        insightLong(it["delta_personal_yuan"],true).also{difference->require(Math.subtractExact(summary.getValue("personal_yuan"),previous)==difference)}
    }
    require(Math.addExact(series.fold(0L){sum,g->Math.addExact(sum,g.personal)},coverage.getValue("undated_personal_yuan"))==summary.getValue("personal_yuan"))
    return InsightReport(query,summary,delta,groups,coverage,series,value.text("bucket"))
}
internal fun defaultInsightDocument():JsonObject=JsonObject().apply {
    listOf("brand","branch","address","city","district","transaction_time").forEach{add(it,JsonNull.INSTANCE)}
    addProperty("channel","unknown");addProperty("date_source","default_today")
    add("items",JsonArray().apply{add(JsonObject().apply{addProperty("item_position",0);addProperty("category","unknown");add("subcategory",JsonNull.INSTANCE);addProperty("meal","not_applicable");add("meal_group",JsonNull.INSTANCE)})})
}
internal fun validateInsightDocument(document:JsonObject):JsonObject {
    val d=document.deepCopy()
    listOf("brand","branch","address","city","district","transaction_time").forEach{k-> val text=d[k]?.takeUnless{it.isJsonNull}?.asString?.trim().orEmpty();require(text.length<=500);if(text.isEmpty())d.add(k,JsonNull.INSTANCE) else d.addProperty(k,text)}
    val time=d["transaction_time"];require(time.isJsonNull || time.asString.matches(Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]")))
    require(d.text("channel") in setOf("in_store","online","unknown"));require(d.text("date_source") in setOf("receipt","user","default_today","unknown"))
    val positions=mutableSetOf<Long>()
    d.array("items").forEach{e->val i=e.asJsonObject;require(positions.add(insightLong(i["item_position"])));require(i.text("category") in insightCategories && i.text("meal") in insightMeals);listOf("subcategory","meal_group").forEach{k->val t=i[k]?.takeUnless{it.isJsonNull}?.asString?.trim().orEmpty();require(t.length<=100);if(t.isEmpty())i.add(k,JsonNull.INSTANCE)else i.addProperty(k,t)}}
    return d
}
internal fun manualBody(date:String,merchant:String,description:String,amount:String,purpose:String,notes:String,analysis:JsonObject):String {
    require(LocalDate.parse(date).year in 1..9999);require(amount.matches(Regex("[1-9][0-9]*")));val money=amount.toLong();require(purpose in setOf("self","gift","advance"));require(merchant.length<=500 && description.length<=500 && notes.length<=4000)
    val d=validateInsightDocument(analysis);require(d.text("date_source")!="receipt");require(d.array("items").size()==1 && d.array("items")[0].asJsonObject["item_position"].asInt==0)
    return JsonObject().apply{addProperty("receipt_date",date);addProperty("merchant",merchant.trim().ifEmpty{null});addProperty("description",description.trim().ifEmpty{null});addProperty("amount_yuan",money);addProperty("purpose",purpose);addProperty("notes",notes.trim().ifEmpty{null});add("analysis",d)}.toString()
}
internal fun insightMoney(amount:Long)="NT$ ${java.text.NumberFormat.getIntegerInstance(java.util.Locale.TAIWAN).format(amount)}"
