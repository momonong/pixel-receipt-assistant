package com.momonong.pixelreceipt.mobile

import androidx.compose.ui.graphics.Color
import java.time.Instant

internal data class ReceiptColors(val ink: Color, val background: Color)

/** Presentation only: original intake, mutation keys and ledger values are never changed. */
internal enum class ReceiptStatus(
    val key: String, val label: String, ink: Long, background: Long,
    val normal: Boolean = true, val attention: Boolean = false,
) {
    Completed("completed", "已完成", 0xFF1F6542, 0xFFE8F4EB),
    NeedsReview("review", "待核對", 0xFF7A5310, 0xFFFFF2D5),
    Processing("processing", "中央處理中", 0xFF235BA7, 0xFFE8F0FD),
    WaitingSync("waiting_sync", "等待同步", 0xFF1C687A, 0xFFE4F3F5),
    WaitingSource("waiting_source", "等待辨識恢復", 0xFF7A4A20, 0xFFF7E9D5, attention = true),
    Draft("draft", "尚未送出", 0xFF425B74, 0xFFECF0F5),
    ReferenceReady("reference", "成果待查看", 0xFF724489, 0xFFF4EAF9),
    MissingPhotos("missing_photos", "待補照片", 0xFF913E18, 0xFFFDEBDD, false, true),
    Delayed("delayed", "等待較久", 0xFF9A3F62, 0xFFFAE9F0, false, true),
    UnknownResult("unknown", "結果待查詢", 0xFF4E4E98, 0xFFEEEEFA, false, true),
    Failed("failed", "辨識未完成", 0xFFA13236, 0xFFFCE8E9, false, true),
    Unavailable("unavailable", "來源未就緒", 0xFF6C5A26, 0xFFF4EFCF, false, true),
    Voided("voided", "已作廢", 0xFF715347, 0xFFF2EAE4, false),
    Deleted("deleted", "已刪除", 0xFF5D6062, 0xFFEDEEEF, false),
    DeletePending("delete_pending", "刪除結果待確認", 0xFF773B70, 0xFFF5E8F4, attention = true),
    VoidPending("void_pending", "作廢結果待確認", 0xFF8A3551, 0xFFFBE7EE, attention = true),
    NamesPending("names_pending", "補品名結果待確認", 0xFF576420, 0xFFF0F3DF, attention = true);

    val colors = ReceiptColors(Color(ink), Color(background))
}

internal data class ReceiptFilter(val key: String = "normal") {
    val label: String get() = when (key) {
        "normal" -> "一般收據"
        "attention" -> "需要處理"
        "all" -> "全部收據"
        else -> ReceiptStatus.entries.firstOrNull { it.key == key }?.label ?: "一般收據"
    }
    fun includes(status: ReceiptStatus): Boolean = when (key) {
        "normal" -> status.normal
        "attention" -> status.attention
        "all" -> true
        else -> status.key == key
    }
}

internal fun restoreReceiptFilter(key: String): ReceiptFilter =
    if (key in setOf("normal", "attention", "all") || ReceiptStatus.entries.any { it.key == key }) ReceiptFilter(key) else ReceiptFilter()

internal fun receiptStatus(batch: MobileBatch, mutations: List<ReceiptMutation> = emptyList(), now: Long = System.currentTimeMillis()): ReceiptStatus {
    if (batch.deleted) return ReceiptStatus.Deleted
    if (batch.ledgerStatus == "voided" || batch.draftStatus == "voided") return ReceiptStatus.Voided
    val pending = mutations.lastOrNull {
        it.phase == "pending" && (it.batchId == batch.id || (batch.ledgerId != null && it.targetId == batch.ledgerId))
    }
    when (pending?.kind) {
        "intake_delete", "draft_delete" -> return ReceiptStatus.DeletePending
        "ledger_void" -> return ReceiptStatus.VoidPending
        "ledger_names" -> return ReceiptStatus.NamesPending
    }
    if (batch.ledgerStatus == "active" || batch.draftStatus == "confirmed") return ReceiptStatus.Completed
    if (!batch.importComplete) return ReceiptStatus.MissingPhotos
    return when (batch.status) {
        "succeeded" -> if (batch.purpose == "reference") ReceiptStatus.ReferenceReady else ReceiptStatus.NeedsReview
        "unknown" -> ReceiptStatus.UnknownResult
        "failed" -> ReceiptStatus.Failed
        "unavailable" -> ReceiptStatus.Unavailable
        "queued", "preparing", "submitting", "processing" -> centralProcessingStatus(batch, now)
        else -> if (batch.frozen != null) ReceiptStatus.WaitingSync else ReceiptStatus.Draft
    }
}

private fun centralProcessingStatus(batch: MobileBatch, now: Long): ReceiptStatus {
    val remote = batch.serverJson?.let { runCatching { jsonObject(it) }.getOrNull() }
    val finished = remote?.get("polling_finished")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean == true
    if (finished) return ReceiptStatus.UnknownResult
    if (admissionWaiting(batch)) return ReceiptStatus.WaitingSource
    // A photo may sit locally for weeks before submission; only the central creation time is evidence of age.
    val created = remote?.text("created_at")?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
    return if (created != null && created <= now && now - created >= 24L * 60 * 60 * 1000) ReceiptStatus.Delayed else ReceiptStatus.Processing
}

internal fun admissionWaiting(batch: MobileBatch): Boolean = batch.status == "queued" &&
    batch.serverJson?.let { runCatching { jsonObject(it).text("error_code") }.getOrNull() } in
        setOf("broker_admission_waiting", "broker_admission_unavailable", "broker_work_busy")

internal fun admissionExplanation(batch: MobileBatch, engines: Map<String, EngineAvailability>): String? {
    if (!admissionWaiting(batch)) return null
    return when (engines[batch.engine]?.reason) {
        "broker_qualification_unavailable" -> "免費資格待更新。照片與說明已保存，服務恢復後中央會自動繼續。"
        "broker_capacity_unavailable" -> "目前額度或處理量已滿。照片與說明已保存，中央會自動等候可用額度。"
        "broker_credential_unavailable", "recognition_configuration_invalid", "recognition_not_configured" -> "辨識服務需要更新設定。照片與說明已保存，設定恢復後中央會自動繼續。"
        else -> "中央正在等候可用的辨識服務。照片與說明已保存，恢復後會自動繼續。"
    }
}

internal fun receiptTagColors(label: String): ReceiptColors? = ReceiptStatus.entries.firstOrNull { it.label == label }?.colors
    ?: if (label.matches(Regex("[0-9]+ 個品項待補品名"))) ReceiptColors(Color(0xFF365D56), Color(0xFFE7EEDD)) else null
