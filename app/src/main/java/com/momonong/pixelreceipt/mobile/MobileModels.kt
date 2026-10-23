package com.momonong.pixelreceipt.mobile

import com.google.gson.JsonNull
import com.google.gson.JsonObject
import java.security.MessageDigest
import java.util.UUID

internal val imageRoles = linkedMapOf("receipt" to "收據", "price_tag" to "價標", "promotion" to "促銷", "menu" to "菜單", "other" to "其他")
internal val intakeStatuses = setOf("queued", "preparing", "submitting", "processing", "succeeded", "failed", "unavailable", "unknown")
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
) {
    val editable: Boolean get() = frozen == null && intakeId == null
}
data class MobileState(val deviceId: String, val batches: List<MobileBatch> = emptyList(), val ledgerJson: String? = null, val ledgerUpdatedAt: Long? = null, val ledgerDetailsJson: String? = null, val ledgerNextOffset: Int? = null)
data class EngineAvailability(val available: Boolean, val reason: String?)
data class ServerIntake(val id: String, val status: String, val draftId: String?, val rawJson: String, val draftStatus: String? = null, val ledgerId: String? = null, val ledgerStatus: String? = null)
class MobileFailure(val userMessage: String) : Exception(userMessage)

internal fun freezeIntake(batch: MobileBatch): FrozenIntake {
    batch.frozen?.let { return it }
    require(batch.importComplete && batch.importReview?.requiresConfirmation != true) { "照片尚未保存完成，請先補齊或重新分享。" }
    require(batch.images.size in 1..20) { "每筆收件需要 1 至 20 張圖片。" }
    require(batch.images.map { it.hash }.distinct().size == batch.images.size) { "同一筆收件不能包含重複圖片。" }
    require(batch.userNotes.length <= 4000) { "整批說明最多 4,000 字。" }
    require(batch.purpose in setOf("purchase", "reference"))
    require(batch.engine in setOf("broker", "local"))
    require(batch.purpose != "purchase" || batch.images.any { it.role == "receipt" }) { "消費至少需要一張收據；菜單或促銷請改選參考資料。" }
    val body = JsonObject().apply {
        addProperty("schema", "pixelreceipt-mobile-ingest-1")
        addProperty("purpose", batch.purpose)
        addProperty("engine", batch.engine)
        add("user_notes", batch.userNotes.takeIf { it.isNotBlank() }?.let { com.google.gson.JsonPrimitive(it) } ?: JsonNull.INSTANCE)
        add("evidence", com.google.gson.JsonArray().apply {
            batch.images.forEach { image ->
                require(image.hash.matches(Regex("[0-9a-f]{64}")))
                require(image.role in imageRoles) { "圖片角色無效。" }
                require(image.notes.length <= 1000) { "每張圖片說明最多 1,000 字。" }
                add(JsonObject().apply {
                    addProperty("sha256", image.hash)
                    addProperty("role", image.role)
                    add("notes", image.notes.takeIf { it.isNotBlank() }?.let { com.google.gson.JsonPrimitive(it) } ?: JsonNull.INSTANCE)
                })
            }
        })
    }.toString()
    return FrozenIntake(UUID.randomUUID().toString(), body, sha256(body.toByteArray(Charsets.UTF_8)))
}

internal fun statusLabel(status: String?): String = when (status) {
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
