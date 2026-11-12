package com.momonong.pixelreceipt.mobile

import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import java.time.LocalDate

internal data class PriceProduct(val id:String,val name:String,val brand:String?,val specification:String,val purchaseUnit:String) {
    val identity:String get()=listOfNotNull(brand,name,specification,"每 $purchaseUnit").joinToString(" · ")
}
internal data class PriceRatio(val numerator:Long,val denominator:Long) {
    init {require(numerator>=0 && denominator>0)}
    fun display():String=if(numerator%denominator==0L)(numerator/denominator).toString() else "$numerator / $denominator"
}
internal data class PriceLine(val position:Int,val product:PriceProduct?,val rawName:String?,val quantity:Long,val printedTotal:Long,val paidTotal:Long?,val printedUnit:PriceRatio,val paidUnit:PriceRatio?,val eligible:Boolean,val reasons:List<String>,val allocationMethods:List<String>)
internal data class PriceReview(val ledgerId:String,val revision:Long,val lines:List<PriceLine>,val document:JsonObject)
internal data class PriceObservation(val ledgerId:String,val date:String?,val merchant:String?,val line:PriceLine)
internal data class ProductPricePage(val product:PriceProduct,val observations:List<PriceObservation>,val lowest:PriceObservation?,val total:Int,val next:Int?)
internal data class PriceCatalog(val products:List<PriceProduct>,val total:Int,val next:Int?)
internal data class PriceAdjustment(val position:Int,val label:String,val amount:Long,val direction:String,val positions:List<Int>,val included:Boolean)
internal data class PriceUiState(
    val open:Boolean=false,val loading:Boolean=false,val writing:Boolean=false,val error:String?=null,val message:String?=null,
    val pending:Boolean=false,val query:String="",val products:List<PriceProduct> = emptyList(),val catalogNext:Int?=null,
    val selectedProduct:PriceProduct?=null,val prices:ProductPricePage?=null,val ledgerId:String?=null,
    val review:PriceReview?=null,val adjustments:List<PriceAdjustment> = emptyList(),val voided:Boolean=false,
)
private fun JsonObject.priceText(key:String,required:Boolean=false):String? {
    val value=get(key)
    if(value==null || value.isJsonNull){require(!required);return null}
    require(value.isJsonPrimitive && value.asJsonPrimitive.isString)
    return value.asString.also {require(it.length<=500 && (!required || it.isNotBlank()))}
}
internal fun parsePriceProduct(value:JsonObject):PriceProduct {
    val id=value.priceText("id",true)!!;require(validUuid(id))
    return PriceProduct(id,value.priceText("display_name",true)!!,value.priceText("brand"),value.priceText("specification",true)!!,value.priceText("purchase_unit",true)!!)
}
internal fun parsePriceCatalog(raw:String,query:String,offset:Int):PriceCatalog {
    val value=jsonObject(raw)
    require(value.text("query")==query && strictLong(value["offset"])==offset.toLong() && strictLong(value["limit"])==20L)
    val products=value.array("products").map{parsePriceProduct(it.asJsonObject)}
    require(products.size<=20 && products.map{it.id}.distinct().size==products.size)
    val total=strictLong(value["total_count"],0,Int.MAX_VALUE.toLong()).toInt();require(offset+products.size<=total)
    require(products.isNotEmpty() || offset>=total)
    return PriceCatalog(products,total,(offset+products.size).takeIf{it<total})
}
internal fun parsePriceLine(value:JsonObject):PriceLine {
    val position=strictLong(value["item_position"],0,99).toInt();val quantity=strictLong(value["quantity"],1,Int.MAX_VALUE.toLong())
    val printed=strictLong(value["printed_line_total_yuan"]);val paid=value["paid_line_total_yuan"]?.takeUnless{it.isJsonNull}?.let{strictLong(it)}
    fun ratio(key:String,total:Long?):PriceRatio? {
        val r=value[key]?.takeUnless{it.isJsonNull}?.asJsonObject
        if(total==null){require(r==null);return null};require(r!=null)
        return PriceRatio(strictLong(r["numerator_yuan"]),strictLong(r["denominator"],1)).also {require(it.numerator==total && it.denominator==quantity)}
    }
    val product=value["product"]?.takeUnless{it.isJsonNull}?.let{parsePriceProduct(it.asJsonObject)}
    require(value.text("product_id")==product?.id.orEmpty())
    val eligible=value["comparison_eligible"].also{require(it.isJsonPrimitive && it.asJsonPrimitive.isBoolean)}.asBoolean
    val reasons=value.array("unknown_reasons").map{require(it.isJsonPrimitive && it.asJsonPrimitive.isString);it.asString}
    require(!eligible || (product!=null && paid!=null && reasons.isEmpty()))
    return PriceLine(position,product,value.priceText("raw_name"),quantity,printed,paid,ratio("printed_unit_price",printed)!!,ratio("paid_unit_price",paid),eligible,reasons,value.array("allocations").map{it.asJsonObject.text("method")})
}
internal fun parsePriceReview(raw:String,id:String):PriceReview {
    val value=jsonObject(raw);require(value.text("ledger_id")==id)
    val lines=value.array("line_prices").map{parsePriceLine(it.asJsonObject)};require(lines.size in 1..100 && lines.map{it.position}==lines.indices.toList())
    val document=JsonObject().apply {add("item_mappings",value.array("item_mappings").deepCopy());add("adjustment_decisions",value.array("adjustment_decisions").deepCopy())}
    return PriceReview(id,strictLong(value["revision"],0,Long.MAX_VALUE-1),lines,document)
}
internal fun parseProductPrices(raw:String,id:String,offset:Int):ProductPricePage {
    val value=jsonObject(raw);val product=parsePriceProduct(value.getAsJsonObject("product"));require(product.id==id)
    require(strictLong(value["offset"])==offset.toLong() && strictLong(value["limit"])==20L)
    fun observation(v:JsonObject):PriceObservation {
        val ledger=v.text("ledger_id");require(validUuid(ledger))
        val date=v.priceText("receipt_date");date?.let{require(LocalDate.parse(it).toString()==it)}
        val line=parsePriceLine(v);require(line.product?.id==id)
        return PriceObservation(ledger,date,v.priceText("merchant"),line)
    }
    val observations=value.array("observations").map{observation(it.asJsonObject)};require(observations.size<=20)
    val lowest=value["lowest_paid_observation"]?.takeUnless{it.isJsonNull}?.let{observation(it.asJsonObject)}
    require(lowest==null || lowest.line.eligible)
    val total=strictLong(value["total_count"],0,Int.MAX_VALUE.toLong()).toInt();require(offset+observations.size<=total && (observations.isNotEmpty() || offset>=total))
    return ProductPricePage(product,observations,lowest,total,(offset+observations.size).takeIf{it<total})
}
internal fun priceAdjustments(rawLedger:String,id:String):Pair<Boolean,List<PriceAdjustment>> {
    val ledger=ledgerNamesObject(rawLedger);require(ledger.text("id")==id)
    val candidate=ledger.getAsJsonObject("snapshot").getAsJsonObject("candidate");val count=candidate.array("items").size()
    return (ledger.text("status")!="active") to candidate.array("adjustments").mapIndexed{index,v->
        val a=v.asJsonObject;val scope=if(a.text("scope")=="receipt")(0 until count).toList() else a.array("item_positions").map{strictLong(it,0,(count-1).toLong()).toInt()}
        PriceAdjustment(index,a.text("raw_label").ifBlank{"加減項 ${index+1}"},strictLong(a["amount_yuan"]),a.text("direction"),scope,a.text("amount_effect")=="included_in_line_totals")
    }
}
/** Editor intent only: backend remains the sole authority for allocation arithmetic. */
internal class PriceReviewForm(private val review:PriceReview) {
    val mappings=linkedMapOf<Int,String>()
    val decisions=linkedMapOf<Int,JsonObject>()
    init {
        review.document.array("item_mappings").forEach{val v=it.asJsonObject;mappings[v["item_position"].asInt]=v.text("product_id")}
        review.document.array("adjustment_decisions").forEach{val v=it.asJsonObject;decisions[v["adjustment_position"].asInt]=v.deepCopy()}
    }
    fun proportional(position:Int){decisions[position]=JsonObject().apply{addProperty("adjustment_position",position);addProperty("method","proportional_line_amount");add("amounts_by_item",JsonNull.INSTANCE)}}
    fun explicit(position:Int,amounts:Map<Int,String>){
        val rows=JsonArray();amounts.toSortedMap().forEach{(p,s)->require(s.matches(Regex("[0-9]+")));val amount=s.toLongOrNull()?:throw IllegalArgumentException("金額過大");rows.add(JsonObject().apply{addProperty("item_position",p);addProperty("amount_yuan",amount)})}
        decisions[position]=JsonObject().apply{addProperty("adjustment_position",position);addProperty("method","explicit_amounts");add("amounts_by_item",rows)}
    }
    fun body():String=JsonObject().apply {
        addProperty("expected_revision",review.revision)
        add("item_mappings",JsonArray().apply{mappings.toSortedMap().forEach{(position,id)->require(position in review.lines.indices && validUuid(id));add(JsonObject().apply{addProperty("item_position",position);addProperty("product_id",id);addProperty("unit_match","confirmed_same_product_and_unit")})}})
        add("adjustment_decisions",JsonArray().apply{decisions.toSortedMap().values.forEach{add(it.deepCopy())}})
    }.toString()
}
