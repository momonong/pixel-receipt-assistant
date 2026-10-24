package com.momonong.pixelreceipt.mobile

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.time.LocalDate

/** Raw editor strings (including incomplete numbers) are durable and never treated as ledger values. */
data class MobileReview(
    val serverJson: String,
    val editorJson: String,
    val editRevision: Long = 0,
    val savedEditRevision: Long = -1,
    val saveAttempt: FrozenReviewSave? = null,
    val confirmation: FrozenConfirmation? = null,
    val conflict: Boolean = false,
    val error: String? = null,
    val comparisonJson: String? = null,
    val retainedEditorJson: String? = null,
    val finalReviewDispatched: Boolean = false,
    val confirmationDispatched: Boolean = false,
) {
    val draft: JsonObject get() = draftObject(serverJson)
    val editor: JsonObject get() = jsonObject(editorJson)
    val dirty: Boolean get() = savedEditRevision != editRevision
    val locked: Boolean get() = confirmation != null || draft.text("status") != "needs_review"
}
data class FrozenReviewSave(val body: String, val revision: Long, val editRevision: Long, val bodySha256: String = sha256(body.toByteArray(Charsets.UTF_8))) {
    override fun toString() = "FrozenReviewSave(revision=$revision; body redacted)"
}
data class FrozenConfirmation(val key: String, val body: String, val reviewBody: String, val baseRevision: Long, val bodySha256: String = sha256(body.toByteArray(Charsets.UTF_8)), val reviewSha256: String = sha256(reviewBody.toByteArray(Charsets.UTF_8))) {
    override fun toString() = "FrozenConfirmation(body and key redacted)"
}
internal data class ReviewPrepared(val body: String?, val errors: Map<String, String>)
internal fun jsonObject(json: String): JsonObject = JsonParser.parseString(json).asJsonObject
internal fun JsonObject.text(key: String): String = get(key)?.takeUnless { it.isJsonNull }?.asString.orEmpty()
internal fun JsonObject.array(key: String): JsonArray = getAsJsonArray(key) ?: JsonArray()
internal fun strictLong(value: JsonElement?, minimum: Long = 0, maximum: Long = Long.MAX_VALUE): Long {
    check(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isNumber)
    check(value.asString.matches(Regex("[0-9]+")))
    return value.asBigDecimal.toBigIntegerExact().longValueExact().also { check(it in minimum..maximum) }
}
internal fun draftObject(json: String): JsonObject = jsonObject(json).also {
    check(validUuid(it.text("id")))
    strictLong(it["revision"], 1, Long.MAX_VALUE - 2)
    check(it.text("status") in setOf("needs_review", "confirmed", "voided", "deleted"))
    check(it.getAsJsonObject("candidate").text("schema") == "pixelreceipt-central-candidate-1")
    check(it.getAsJsonObject("calculation") != null)
    val candidate = it.getAsJsonObject("candidate")
    check(candidate.text("currency") == "TWD" && candidate.text("amount_unit") == "TWD_YUAN")
    check(candidate.array("items").size() <= 100 && candidate.array("adjustments").size() <= 50)
    listOf("printed_total_yuan").forEach { key -> candidate[key]?.takeUnless { value -> value.isJsonNull }?.let { value -> strictLong(value) } }
    candidate.array("items").forEach { entry ->
        val item = entry.asJsonObject
        item["quantity"]?.takeUnless { value -> value.isJsonNull }?.let { value -> strictLong(value, 1, Int.MAX_VALUE.toLong()) }
        listOf("printed_unit_price_yuan", "printed_line_total_yuan").forEach { key -> item[key]?.takeUnless { value -> value.isJsonNull }?.let { value -> strictLong(value) } }
    }
    candidate.array("adjustments").forEach { entry ->
        val adjustment = entry.asJsonObject
        adjustment["amount_yuan"]?.takeUnless { value -> value.isJsonNull }?.let { value -> strictLong(value) }
        adjustment.array("item_positions").forEach { position -> strictLong(position, 0, (candidate.array("items").size() - 1).toLong()) }
    }
    it["review_document"]?.takeIf { value -> value.isJsonObject }?.asJsonObject?.let { document ->
        document.array("line_decisions").forEach { decision -> strictLong(decision.asJsonObject["item_position"], 0, (candidate.array("items").size() - 1).toLong()) }
        document.array("adjustment_decisions").forEach { decision -> strictLong(decision.asJsonObject["adjustment_position"], 0, (candidate.array("adjustments").size() - 1).toLong()) }
    }
}
internal fun ledgerObject(json: String): JsonObject = jsonObject(json).also {
    check(validUuid(it.text("id")) && validUuid(it.text("draft_id")))
    check(it.text("status") in setOf("active", "voided")); strictLong(it["revision"], 1)
    check(draftObject(it["snapshot"].toString()).text("id") == it.text("draft_id"))
}
internal val purposeLabels = linkedMapOf("self" to "自己用", "gift" to "送人", "advance" to "代買")

internal fun createReview(serverJson: String): MobileReview {
    val draft = draftObject(serverJson)
    val document = draft["review_document"]?.takeIf { it.isJsonObject }?.asJsonObject
    val candidate = draft.getAsJsonObject("candidate").deepCopy()
    val lines = document?.array("line_decisions")?.map { it.asJsonObject }?.associateBy { it["item_position"].asInt }.orEmpty()
    val adjustments = document?.array("adjustment_decisions")?.map { it.asJsonObject }?.associateBy { it["adjustment_position"].asInt }.orEmpty()
    val editor = JsonObject().apply {
        add("candidate", candidate)
        addProperty("assembly_status", if (document?.text("assembly_status") == "incomplete") "incomplete" else "unknown")
        addProperty("tolerance_acknowledgement", document?.text("tolerance_acknowledgement").orEmpty().ifBlank { "none" })
        add("line_decisions", JsonArray().apply {
            candidate.array("items").forEachIndexed { index, _ ->
                val existing = lines[index]?.deepCopy()
                // Only untouched whole-line decisions receive the default. Preserve every split/exception.
                add(if (existing == null || (existing.text("method") == "whole_line" && existing.text("whole_purpose").isBlank() &&
                    existing["quantities"].let { it == null || it.isJsonNull } && existing["amounts_yuan"].let { it == null || it.isJsonNull }))
                    wholeDecision(index, "self") else existing)
            }
        })
        add("adjustment_decisions", JsonArray().apply {
            candidate.array("adjustments").forEachIndexed { index, value ->
                if (value.asJsonObject.text("amount_effect") != "included_in_line_totals") add(adjustments[index]?.deepCopy() ?: JsonObject().apply {
                    addProperty("adjustment_position", index); addProperty("method", "proportional_amount"); add("amounts_yuan", JsonNull.INSTANCE)
                })
            }
        })
        add("review_note", JsonNull.INSTANCE)
    }
    return MobileReview(serverJson, editor.toString())
}
internal fun wholeDecision(index: Int, purpose: String) = JsonObject().apply {
    addProperty("item_position", index); addProperty("method", "whole_line"); addProperty("whole_purpose", purpose)
    add("quantities", JsonNull.INSTANCE); add("amounts_yuan", JsonNull.INSTANCE)
}

/** Converts observed facts to the existing integer-yuan contract. Never calculates totals or allocations. */
internal fun prepareReview(review: MobileReview, complete: Boolean = false): ReviewPrepared {
    val errors = linkedMapOf<String, String>()
    val value = review.editor.deepCopy()
    val candidate = value.getAsJsonObject("candidate")
    fun text(obj: JsonObject, key: String, path: String, required: Boolean = false, limit: Int = 500) {
        val input = obj.text(key).trim()
        if (input.length > limit || (required && input.isEmpty())) errors[path] = if (input.isEmpty()) "請填寫這個欄位。" else "最多 $limit 字。"
        obj.add(key, input.takeIf { it.isNotEmpty() }?.let { com.google.gson.JsonPrimitive(it) } ?: JsonNull.INSTANCE)
    }
    fun number(obj: JsonObject, key: String, path: String, minimum: Long = 0, maximum: Long = Long.MAX_VALUE, required: Boolean = false) {
        val input = obj.text(key).trim()
        val number = input.takeIf { it.matches(Regex("[0-9]+")) }?.toLongOrNull()?.takeIf { it in minimum..maximum }
        if ((input.isNotEmpty() && number == null) || (required && number == null)) errors[path] = "請填 ${if (minimum == 1L) "大於 0 的" else "非負"}整數，不接受小數。"
        obj.add(key, number?.let { com.google.gson.JsonPrimitive(it) } ?: JsonNull.INSTANCE)
    }
    text(candidate, "merchant", "merchant")
    text(candidate, "receipt_date", "receipt_date")
    if (candidate.text("receipt_date").isNotBlank() && runCatching { LocalDate.parse(candidate.text("receipt_date")) }.isFailure) errors["receipt_date"] = "請填有效日期，例如 2026-10-23。"
    number(candidate, "printed_total_yuan", "printed_total_yuan")
    candidate.array("items").forEachIndexed { index, element ->
        val item = element.asJsonObject
        listOf("raw_name", "brand", "specification").forEach { text(item, it, "item.$index.$it") }
        number(item, "quantity", "item.$index.quantity", 1, Int.MAX_VALUE.toLong())
        listOf("printed_unit_price_yuan", "printed_line_total_yuan").forEach { number(item, it, "item.$index.$it") }
    }
    candidate.array("adjustments").forEachIndexed { index, element ->
        val adjustment = element.asJsonObject
        text(adjustment, "raw_label", "adjustment.$index.raw_label")
        number(adjustment, "amount_yuan", "adjustment.$index.amount_yuan")
        if (adjustment.text("scope") != "items") adjustment.add("item_positions", JsonArray())
        else if (adjustment["item_positions"]?.isJsonPrimitive == true) {
            val entries = adjustment.text("item_positions").split(',', '，').map { it.trim().toIntOrNull() }
            if (entries.isEmpty() || entries.any { it == null || it !in 1..candidate.array("items").size() } || entries.distinct().size != entries.size)
                errors["adjustment.$index.item_positions"] = "填受影響品項編號，以逗號分隔，例如 1,2。"
            else adjustment.add("item_positions", JsonArray().apply { entries.forEach { add(it!! - 1) } })
        }
    }
    fun triple(decision: JsonObject, key: String, path: String, maximum: Long) {
        val triple = decision[key]?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
        purposeLabels.keys.forEach { number(triple, it, "$path.$key.$it", maximum = maximum, required = true) }
        decision.add(key, triple)
    }
    value.array("line_decisions").forEach { element ->
        val decision = element.asJsonObject; val index = decision["item_position"].asInt
        if (decision.text("method") == "whole_line") {
            if (decision.text("whole_purpose") !in purposeLabels) errors["item.$index.purpose"] = "請選用途。"
            decision.add("quantities", JsonNull.INSTANCE); decision.add("amounts_yuan", JsonNull.INSTANCE)
        } else {
            decision.add("whole_purpose", JsonNull.INSTANCE)
            triple(decision, "quantities", "item.$index", Int.MAX_VALUE.toLong())
            if (decision.text("method") == "explicit_amounts") triple(decision, "amounts_yuan", "item.$index", Long.MAX_VALUE)
            else decision.add("amounts_yuan", JsonNull.INSTANCE)
        }
    }
    val filtered = JsonArray()
    value.array("adjustment_decisions").forEach { element ->
        val decision = element.asJsonObject; val index = decision["adjustment_position"].asInt
        if (candidate.array("adjustments")[index].asJsonObject.text("amount_effect") != "included_in_line_totals") {
            if (decision.text("method") == "explicit_amounts") triple(decision, "amounts_yuan", "adjustment.$index", Long.MAX_VALUE)
            else decision.add("amounts_yuan", JsonNull.INSTANCE)
            filtered.add(decision)
        }
    }
    value.add("adjustment_decisions", filtered)
    text(value, "review_note", "review_note", limit = 1000)
    value.addProperty("schema", "pixelreceipt-central-review-2")
    value.addProperty("expected_revision", review.draft["revision"].asLong)
    value.addProperty("assembly_status", if (complete) "complete_by_user" else if (value.text("assembly_status") == "incomplete") "incomplete" else "unknown")
    return ReviewPrepared(value.toString().takeIf { errors.isEmpty() }, errors)
}
internal fun matchesReview(draft: JsonObject, body: String): Boolean {
    val request = jsonObject(body)
    val document = request.deepCopy().apply { remove("expected_revision"); remove("candidate"); remove("review_note") }
    return draft["candidate"] == request["candidate"] && draft["review_document"] == document
}
internal fun reviewBlockers(review: MobileReview): List<JsonObject> = review.draft.getAsJsonObject("calculation").array("blockers").map { it.asJsonObject }
internal fun moneyText(value: JsonElement?): String = value?.takeUnless { it.isJsonNull }?.asString?.let { "NT$ $it" } ?: "金額待確認"

internal fun validateReview(review: MobileReview) {
    draftObject(review.serverJson); jsonObject(review.editorJson)
    check(review.editRevision >= 0 && review.savedEditRevision <= review.editRevision)
    review.saveAttempt?.let { attempt ->
        check(sha256(attempt.body.toByteArray(Charsets.UTF_8)) == attempt.bodySha256)
        check(strictLong(jsonObject(attempt.body)["expected_revision"], 1) == attempt.revision)
    }
    review.confirmation?.let { attempt ->
        check(validUuid(attempt.key) && attempt.baseRevision in 1 until Long.MAX_VALUE - 1)
        check(sha256(attempt.body.toByteArray(Charsets.UTF_8)) == attempt.bodySha256)
        check(sha256(attempt.reviewBody.toByteArray(Charsets.UTF_8)) == attempt.reviewSha256)
        check(strictLong(jsonObject(attempt.body)["expected_revision"], 1) == attempt.baseRevision + 1)
    }
}
/** Human-readable differences include purpose and preserved metadata, not only headline amounts. */
internal fun reviewDifferences(review: MobileReview): List<String> {
    val remote = review.comparisonJson ?: return emptyList()
    fun flattened(value: JsonElement, prefix: String = ""): Map<String, String> = when {
        value.isJsonObject -> value.asJsonObject.entrySet().flatMap { (key, entry) -> flattened(entry, if (prefix.isEmpty()) key else "$prefix.$key").entries.map { it.key to it.value } }.toMap()
        value.isJsonArray -> if (value.asJsonArray.size() == 0) mapOf(prefix to "未設定") else value.asJsonArray.flatMapIndexed { index, entry -> flattened(entry, "$prefix.$index").entries.map { it.key to it.value } }.toMap()
        else -> mapOf(prefix to if (value.isJsonNull) "待確認" else value.asString)
    }
    val local = flattened(review.editor); val latest = flattened(createReview(remote).editor)
    val labels = mapOf("merchant" to "店家", "receipt_date" to "日期", "printed_total_yuan" to "實付", "raw_name" to "品名", "quantity" to "數量", "printed_unit_price_yuan" to "票面單價", "printed_line_total_yuan" to "行金額", "brand" to "品牌", "specification" to "規格", "value_decimal" to "包裝含量", "unit" to "單位", "raw_label" to "名稱", "direction" to "加減方式", "amount_yuan" to "金額", "amount_effect" to "折扣列法", "scope" to "影響範圍", "item_positions" to "受影響品項", "method" to "分攤方式", "whole_purpose" to "用途", "assembly_status" to "完整性", "tolerance_acknowledgement" to "差額確認", "review_note" to "核對備註")
    val values = purposeLabels + mapOf("whole_line" to "整項同一用途", "by_quantity" to "按數量分攤", "explicit_amounts" to "指定金額", "proportional_amount" to "依金額比例", "subtract" to "折扣", "add" to "加收", "included_in_line_totals" to "已含行金額", "separate_from_line_totals" to "另外加減總額", "receipt" to "整張收據", "items" to "指定品項", "unknown" to "未確認", "incomplete" to "缺照片或品項", "none" to "尚未確認", "accept_within_tolerance" to "接受差額")
    return (local.keys + latest.keys).distinct().filter { local[it] != latest[it] && it.substringAfterLast('.') !in setOf("schema", "item_position", "adjustment_position", "currency", "amount_unit") }.map { path ->
        val parts = path.split('.')
        val group = when { "items" in parts -> "品項 ${(parts.getOrNull(2)?.toIntOrNull() ?: 0) + 1} · "; "line_decisions" in parts -> "品項 ${(parts.getOrNull(1)?.toIntOrNull() ?: 0) + 1} · "; "adjustments" in parts -> "折扣／加收 ${(parts.getOrNull(2)?.toIntOrNull() ?: 0) + 1} · "; "adjustment_decisions" in parts -> "折扣／加收 ${(parts.getOrNull(1)?.toIntOrNull() ?: 0) + 1} · "; else -> "" }
        val last = parts.last(); val label = labels[last] ?: purposeLabels[last]?.let { "$it${if ("quantities" in parts) "分攤數量" else "分攤金額"}" } ?: "受影響品項／內容"
        val a = local[path] ?: "未設定"; val b = latest[path] ?: "未設定"
        "$group$label：手機 ${values[a] ?: a} ／ 中央 ${values[b] ?: b}"
    }
}
