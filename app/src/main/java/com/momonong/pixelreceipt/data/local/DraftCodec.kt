package com.momonong.pixelreceipt.data.local

import com.google.gson.*
import com.google.gson.reflect.TypeToken
import com.momonong.pixelreceipt.domain.model.*
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type

/** Private, versioned database format. Never use this codec for external/AI input.
 * Stable allowlisted tags (never Class.forName) retain generic Fact values and provenance.
 */
class DraftCodec {
    private val gson = GsonBuilder()
        .registerTypeAdapter(Fact::class.java, Tagged(
            "known" to Fact.Known::class.java, "unknown" to Fact.Unknown::class.java,
            "conflicting" to Fact.Conflicting::class.java, "notApplicable" to Fact.NotApplicable::class.java,
        ))
        .registerTypeAdapter(FactProvenance::class.java, Tagged(
            "extracted" to FactProvenance.Extracted::class.java,
            "user" to FactProvenance.UserConfirmed::class.java,
            "derived" to FactProvenance.Derived::class.java,
        ))
        .registerTypeAdapter(EvidenceLinkTarget::class.java, Tagged(
            "receipt" to EvidenceLinkTarget.Receipt::class.java,
            "line" to EvidenceLinkTarget.ReceiptLine::class.java,
            "adjustment" to EvidenceLinkTarget.Adjustment::class.java,
            "offer" to EvidenceLinkTarget.PromotionOffer::class.java,
        ))
        .registerTypeAdapter(ReceiptAdjustmentScope::class.java, Tagged(
            "line" to ReceiptAdjustmentScope.Line::class.java,
            "lines" to ReceiptAdjustmentScope.LineSet::class.java,
            "order" to ReceiptAdjustmentScope.Order::class.java,
        ))
        .registerTypeAdapter(PromotionEligibility::class.java, Tagged(
            "products" to PromotionEligibility.ExplicitProducts::class.java,
            "group" to PromotionEligibility.DescribedGroup::class.java,
            "store" to PromotionEligibility.StoreWide::class.java,
        ))
        .registerTypeAdapter(PromotionTerms::class.java, Tagged(
            "unit" to PromotionTerms.FixedUnitPrice::class.java,
            "multi" to PromotionTerms.MultiBuy::class.java,
            "buyX" to PromotionTerms.BuyXGetY::class.java,
            "percent" to PromotionTerms.PercentageOff::class.java,
            "discount" to PromotionTerms.FixedDiscount::class.java,
            "threshold" to PromotionTerms.BasketThreshold::class.java,
            "opaque" to PromotionTerms.Opaque::class.java,
        )).create()

    fun encode(draft: ReceiptDraft): String = envelope(gson.toJsonTree(draft), 2)
    fun decode(payload: String): ReceiptDraft {
        val root = JsonParser.parseString(payload).asJsonObject
        val format = root["format"].asInt
        require(format in 1..2) { "Unsupported persisted format" }
        val value = root["value"].asJsonObject
        // Gson bypasses Kotlin constructor defaults. Explicitly migrate v1 in memory;
        // the next CAS write persists v2 without altering the SQL schema or revision here.
        if (format == 1 && !value.has("transactionDate")) {
            value.add("transactionDate", gson.toJsonTree(
                Fact.Unknown(UnknownFactReason.NotObserved), Fact::class.java,
            ))
        }
        require(value.has("transactionDate") && !value["transactionDate"].isJsonNull) {
            "Missing persisted transaction date fact"
        }
        return gson.fromJson(value, ReceiptDraft::class.java)
    }
    fun encodeAsset(asset: EvidenceAsset): String = envelope(gson.toJsonTree(asset))
    fun decodeAsset(payload: String): EvidenceAsset = gson.fromJson(body(payload), EvidenceAsset::class.java)

    private fun envelope(value: JsonElement, format: Int = 1): String = JsonObject().apply {
        addProperty("format", format)
        add("value", value)
    }.toString()

    private fun body(payload: String): JsonElement = JsonParser.parseString(payload).asJsonObject.let {
        require(it["format"].asInt == 1) { "Unsupported persisted format" }
        it["value"]
    }
}

private class Tagged(vararg entries: Pair<String, Class<*>>) : JsonSerializer<Any>, JsonDeserializer<Any> {
    private val types = entries.toMap()
    private fun concrete(type: Type, subtype: Class<*>): Type =
        if (subtype.typeParameters.isNotEmpty() && type is ParameterizedType) {
            TypeToken.getParameterized(subtype, *type.actualTypeArguments).type
        } else subtype

    override fun serialize(src: Any, type: Type, context: JsonSerializationContext): JsonElement {
        val tag = types.entries.single { it.value == src.javaClass }.key
        return JsonObject().apply {
            addProperty("tag", tag)
            add("data", context.serialize(src, concrete(type, src.javaClass)))
        }
    }

    override fun deserialize(json: JsonElement, type: Type, context: JsonDeserializationContext): Any {
        val obj = json.asJsonObject
        val subtype = types[obj["tag"].asString] ?: throw JsonParseException("Unknown persisted tag")
        return when (subtype) {
            ReceiptAdjustmentScope.Order::class.java -> ReceiptAdjustmentScope.Order
            PromotionEligibility.StoreWide::class.java -> PromotionEligibility.StoreWide
            else -> context.deserialize(obj["data"], concrete(type, subtype))
        }
    }
}
