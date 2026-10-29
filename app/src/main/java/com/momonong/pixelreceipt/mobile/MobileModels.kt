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
) {
    val deleted: Boolean get() = deletedAt != null || status == "deleted" || draftStatus == "deleted"
    val editable: Boolean get() = !deleted && frozen == null && intakeId == null
}
data class MobileState(val deviceId: String, val batches: List<MobileBatch> = emptyList(), val ledgerJson: String? = null, val ledgerUpdatedAt: Long? = null, val ledgerDetailsJson: String? = null, val ledgerNextOffset: Int? = null, val mutations: List<ReceiptMutation>? = null, val overviewJson: String? = null, val accountingStale: Boolean? = null, val accountingError: String? = null)
data class EngineAvailability(val available: Boolean, val reason: String?)
data class ServerIntake(val id: String, val status: String, val draftId: String?, val rawJson: String, val draftStatus: String? = null, val ledgerId: String? = null, val ledgerStatus: String? = null)
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
internal fun MobileState.pendingLedgerMutation(id: String): ReceiptMutation? = mutations.orEmpty().lastOrNull { it.targetId == id && it.kind == "ledger_void" && it.phase == "pending" }
internal fun validateMutation(value: ReceiptMutation) {
    check(value.kind in setOf("intake_delete", "draft_delete", "ledger_void") && validUuid(value.targetId) && validUuid(value.key))
    check(value.batchId == null || validUuid(value.batchId))
    check(value.phase in setOf("pending", "completed", "rejected"))
    check(sha256(value.body.toByteArray(Charsets.UTF_8)) == value.bodySha256)
    val body = jsonObject(value.body)
    val revision = if (value.kind == "intake_delete") "expected_draft_revision" else "expected_revision"
    check(body.keySet() == setOf(revision, "reason") && body.has(revision))
    if (!body[revision].isJsonNull) strictLong(body[revision], 1) else check(value.kind == "intake_delete")
    check(body["reason"].isJsonPrimitive && body["reason"].asJsonPrimitive.isString && body.text("reason").isNotBlank() && body.text("reason").length <= 1000)
}
internal fun overviewObject(json: String): JsonObject = jsonObject(json).also {
    check(it.text("scope") == "active_confirmed_ledger")
    listOf("receipt_count", "personal_yuan", "printed_total_yuan").forEach { key -> strictLong(it[key]) }
}
