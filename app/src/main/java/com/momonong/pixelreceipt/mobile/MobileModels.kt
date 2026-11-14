package com.momonong.pixelreceipt.mobile

import com.google.gson.JsonNull
import com.google.gson.JsonObject
import java.security.MessageDigest
import java.util.UUID

internal val imageRoles = linkedMapOf("receipt" to "收據", "price_tag" to "價標", "promotion" to "促銷", "menu" to "菜單", "other" to "其他")
internal val intakeStatuses = setOf("queued", "preparing", "submitting", "processing", "succeeded", "failed", "unavailable", "unknown", "deleted")
internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
internal fun validUuid(value: String): Boolean = runCatching { UUID.fromString(value).toString() == value.lowercase() }.getOrDefault(false)

data class MobileImage(val hash: String, val mime: String, val bytes: Long, val role: String = "receipt", val notes: String = "")
/** Only hashes actually read are recorded. Source URIs are deliberately not persisted. */
data class MobileImportReview(
    val expectedCount: Int? = null,
    val originalSlots: List<String?> = emptyList(),
    val selectedHashes: List<String> = emptyList(),
    val previousHashes: List<String> = emptyList(),
    val requiresConfirmation: Boolean = true,
)
data class FrozenIntake(val key: String, val body: String, val bodySha256: String)
data class MobileBatch(
    val id: String,
    val revision: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val images: List<MobileImage> = emptyList(),
    val userNotes: String = "",
    val purpose: String = "purchase",
    val engine: String = "broker",
    val importComplete: Boolean = false,
    val importReview: MobileImportReview? = null,
    val frozen: FrozenIntake? = null,
    val uploadedHashes: List<String> = emptyList(),
    val intakeId: String? = null,
    val status: String? = null,
    val draftId: String? = null,
    val serverJson: String? = null,
    val message: String? = null,
    val draftStatus: String? = null,
    val ledgerId: String? = null,
    val ledgerStatus: String? = null,
    val review: MobileReview? = null,
    val deletedAt: Long? = null,
    // Only a persisted, explicit send intent may resume network writes automatically.
    // Old records default to false; their original frozen intent remains manually recoverable.
    val syncRetryable: Boolean = false,
    val syncRetryAt: Long? = null,
    val syncFailures: Int = 0,
    // Additive nullable fields keep pre-group state files readable by Gson.
    val parentBatchId: String? = null,
    val receiptIds: List<String>? = null,
    val receiptInfo: MobileReceiptInfo? = null,
) {
    val deleted: Boolean get() = deletedAt != null || status == "deleted" || draftStatus == "deleted"
    val isReceiptGroup: Boolean get() = !receiptIds.isNullOrEmpty()
    val editable: Boolean get() = !deleted && parentBatchId == null && frozen == null && intakeId == null
}
data class MobileState(val deviceId: String, val batches: List<MobileBatch> = emptyList(), val ledgerJson: String? = null, val ledgerUpdatedAt: Long? = null, val ledgerDetailsJson: String? = null, val ledgerNextOffset: Int? = null, val mutations: List<ReceiptMutation>? = null, val overviewJson: String? = null, val accountingStale: Boolean? = null, val accountingError: String? = null)
data class EngineAvailability(val available: Boolean, val reason: String?)
data class ServerIntake(val id: String, val status: String, val draftId: String?, val rawJson: String, val draftStatus: String? = null, val ledgerId: String? = null, val ledgerStatus: String? = null, val receipts: List<ServerReceipt> = emptyList())
class MobileFailure(val userMessage: String) : Exception(userMessage)

internal fun freezeIntake(batch: MobileBatch): FrozenIntake {
    batch.frozen?.let { return it }
    validateNewIntake(batch)
    val body = intakeBody(batch)
    return FrozenIntake(UUID.randomUUID().toString(), body, sha256(body.toByteArray(Charsets.UTF_8)))
}

/** Validation before a duplicate decision must not create a key or mutate the saved batch. */
internal fun validateNewIntake(batch: MobileBatch) {
    if (batch.deleted) throw MobileFailure("這筆收據已刪除，不會再次送出。")
    require(batch.importComplete && batch.importReview?.requiresConfirmation != true) { "照片尚未保存完成，請先補齊或重新分享。" }
    require(batch.images.size in 1..20) { "每筆收件需要 1 至 20 張圖片。" }
    require(batch.images.map { it.hash }.distinct().size == batch.images.size) { "同一筆收件不能包含重複圖片。" }
    require(batch.userNotes.length <= 4000) { "整批說明最多 4,000 字。" }
    require(batch.purpose in setOf("purchase", "reference"))
    require(batch.engine in setOf("broker", "local"))
    require(batch.purpose != "purchase" || batch.images.any { it.role == "receipt" }) { "消費至少需要一張收據；菜單或促銷請改選參考資料。" }
    batch.images.forEach { image ->
        require(image.hash.matches(Regex("[0-9a-f]{64}")))
        require(image.role in imageRoles) { "圖片角色無效。" }
        require(image.notes.length <= 1000) { "每張圖片說明最多 1,000 字。" }
    }
}

private fun intakeBody(batch: MobileBatch): String = JsonObject().apply {
        addProperty("schema", "pixelreceipt-mobile-ingest-1")
        add("capabilities", com.google.gson.JsonArray().apply { add("receipt_groups_v1") })
        addProperty("purpose", batch.purpose)
        addProperty("engine", batch.engine)
        add("user_notes", batch.userNotes.takeIf { it.isNotBlank() }?.let { com.google.gson.JsonPrimitive(it) } ?: JsonNull.INSTANCE)
        add("evidence", com.google.gson.JsonArray().apply {
            batch.images.forEach { image ->
                add(JsonObject().apply {
                    addProperty("sha256", image.hash)
                    addProperty("role", image.role)
                    add("notes", image.notes.takeIf { it.isNotBlank() }?.let { com.google.gson.JsonPrimitive(it) } ?: JsonNull.INSTANCE)
                })
            }
        })
    }.toString()

internal fun statusLabel(status: String?): String = when (status) {
    "deleted" -> "已刪除"
    "queued" -> "中央已保存，等待處理"
    "preparing" -> "正在準備圖片"
    "submitting" -> "正在送交辨識來源"
    "processing" -> "辨識處理中"
    "succeeded" -> "辨識完成"
    "failed" -> "辨識未完成"
    "unavailable" -> "來源尚未設定，資料已保留"
    "unknown" -> "結果尚不確定，請更新狀態"
    else -> "本機已保存，尚未確認中央收件"
}

/** Any association change invalidates the prior membership/order acknowledgement. */
internal fun requireAssociationReview(old: MobileBatch, next: MobileBatch): MobileBatch =
    if (old.images.map { it.hash } == next.images.map { it.hash }) next else next.copy(
        importComplete = false,
        importReview = (next.importReview ?: MobileImportReview()).copy(requiresConfirmation = true),
    )

/** Immutable request intent is stored before dispatch. Completed and rejected records remain as history. */
data class ReceiptMutation(
    val kind: String, val targetId: String, val batchId: String?, val key: String, val body: String,
    val bodySha256: String = sha256(body.toByteArray(Charsets.UTF_8)),
    val phase: String = "pending", val error: String? = null,
) {
    override fun toString() = "ReceiptMutation(kind=$kind, phase=$phase; body and key redacted)"
}
internal fun MobileState.pendingMutation(batchId: String): ReceiptMutation? = mutations.orEmpty().lastOrNull { it.batchId == batchId && it.phase == "pending" }
internal fun MobileState.pendingLedgerMutation(id: String): ReceiptMutation? = mutations.orEmpty().lastOrNull { it.targetId == id && it.kind in setOf("ledger_void", "ledger_names") && it.phase == "pending" }
internal fun validateMutation(value: ReceiptMutation) {
    check(value.kind in setOf("intake_delete", "draft_delete", "ledger_void", "ledger_names") && validUuid(value.targetId) && validUuid(value.key))
    check(value.batchId == null || validUuid(value.batchId))
    check(value.phase in setOf("pending", "completed", "rejected"))
    check(sha256(value.body.toByteArray(Charsets.UTF_8)) == value.bodySha256)
    val body = jsonObject(value.body)
    if (value.kind == "ledger_names") {
        check(body.keySet() == setOf("expected_revision", "item_names"))
        strictLong(body["expected_revision"], 1)
        check(body["item_names"]?.isJsonArray == true)
        val names = body.array("item_names")
        check(names.size() in 1..100)
        val positions = mutableSetOf<Long>()
        names.forEach { element ->
            check(element.isJsonObject)
            val item = element.asJsonObject
            check(item.keySet() == setOf("item_position", "name"))
            check(positions.add(strictLong(item["item_position"], 0, 99)))
            val name = item["name"]
            check(name.isJsonNull || (name.isJsonPrimitive && name.asJsonPrimitive.isString && name.asString.length <= 500 && name.asString.isNotBlank()))
        }
        return
    }
    val revision = if (value.kind == "intake_delete") "expected_draft_revision" else "expected_revision"
    check(body.keySet() == setOf(revision, "reason") && body.has(revision))
    if (!body[revision].isJsonNull) strictLong(body[revision], 1) else check(value.kind == "intake_delete")
    check(body["reason"].isJsonPrimitive && body["reason"].asJsonPrimitive.isString && body.text("reason").isNotBlank() && body.text("reason").length <= 1000)
}
internal fun overviewObject(json: String): JsonObject = jsonObject(json).also {
    check(it.text("scope") == "active_confirmed_ledger")
    listOf("receipt_count", "personal_yuan", "printed_total_yuan").forEach { key -> strictLong(it[key]) }
    listOf("missing_item_name_count", "receipts_missing_item_names").forEach { key -> it[key]?.let { value -> strictLong(value) } }
}

/** Match the specific revision and metadata values, never just the latest display name. */
internal fun itemNamesEventMatches(entry: JsonObject, attempt: ReceiptMutation): Boolean {
    val body = jsonObject(attempt.body)
    val revision = body["expected_revision"].asLong + 1
    val event = entry.array("events").firstOrNull { value ->
        value.isJsonObject && value.asJsonObject.text("action") == "item_names_updated" && value.asJsonObject["revision"]?.let { !it.isJsonNull && it.asLong == revision } == true
    }?.asJsonObject ?: return false
    return runCatching { jsonObject(event.text("reason"))["item_names"] == body["item_names"] }.getOrDefault(false)
}
internal fun ledgerItemNames(entry: JsonObject): List<String?> {
    val items = entry.getAsJsonObject("snapshot").getAsJsonObject("candidate").array("items")
    val display = entry["display_item_names"]?.let { value ->
        check(value.isJsonArray && value.asJsonArray.size() == items.size())
        value.asJsonArray
    }
    return items.mapIndexed { index, item ->
        val name = if (display != null && index < display.size()) display[index] else item.asJsonObject["raw_name"]
        if (name == null || name.isJsonNull) null else {
            check(name.isJsonPrimitive && name.asJsonPrimitive.isString && name.asString.length <= 500)
            name.asString.takeIf { it.isNotBlank() }
        }
    }
}

internal fun ledgerNamesObject(json: String): JsonObject = ledgerObject(json).also { entry ->
    ledgerItemNames(entry)
    entry["missing_item_name_count"]?.let { strictLong(it, 0, 100) }
}
