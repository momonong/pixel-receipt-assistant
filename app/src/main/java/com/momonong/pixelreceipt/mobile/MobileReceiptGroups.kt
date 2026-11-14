package com.momonong.pixelreceipt.mobile

import com.google.gson.JsonObject

/** Geometry only; original photo ownership and bytes stay with the imported batch. */
data class ReceiptRegion(val imagePosition: Int, val box: List<Int>)
data class MobileReceiptInfo(val receiptId: String, val label: String, val regions: List<ReceiptRegion>, val purpose: String? = null)
data class ServerReceipt(
    val info: MobileReceiptInfo, val intakeId: String, val status: String, val errorCode: String?,
    val draftId: String?, val draftStatus: String?, val ledgerId: String?, val ledgerStatus: String?,
)
internal const val GROUP_DELETE_EXPLANATION = "這批包含分開核對的收據；請開啟各張收據，分別刪除未入帳收據或作廢已入帳帳目。原圖與整批說明保留。"

internal fun validateReceiptInfo(info: MobileReceiptInfo) {
    check(info.receiptId.matches(Regex("[A-Za-z0-9_-]{1,64}")))
    check(info.label.isNotBlank() && info.label.length <= 200)
    check(info.purpose == null || info.purpose in purposeLabels)
    check(info.regions.size in 1..20)
    info.regions.forEach { region ->
        check(region.imagePosition in 1..20 && region.box.size == 4 && region.box.all { it in 0..1000 })
        check(region.box[0] < region.box[2] && region.box[1] < region.box[3])
    }
}

internal fun parseServerReceipts(json: JsonObject): List<ServerReceipt> {
    val values = json["receipts"]?.takeUnless { it.isJsonNull } ?: return emptyList()
    check(values.isJsonArray && values.asJsonArray.size() <= 100)
    val receipts = values.asJsonArray.map { value ->
        check(value.isJsonObject)
        val item = value.asJsonObject
        fun text(key: String, limit: Int): String {
            val field = item[key]
            check(field != null && field.isJsonPrimitive && field.asJsonPrimitive.isString && field.asString.length <= limit)
            return field.asString
        }
        fun optional(key: String, allowed: Set<String>? = null, uuid: Boolean = false): String? = item[key]?.takeUnless { it.isJsonNull }?.let {
            val result = text(key, 256)
            check(allowed == null || result in allowed)
            check(!uuid || validUuid(result))
            result
        }
        check(item["regions"]?.isJsonArray == true)
        val regions = item.array("regions").map { region ->
            check(region.isJsonObject)
            val obj = region.asJsonObject
            check(obj["box"]?.isJsonArray == true && obj.array("box").size() == 4)
            ReceiptRegion(strictLong(obj["image_position"], 1, 20).toInt(), obj.array("box").map { strictLong(it, 0, 1000).toInt() })
        }
        val info = MobileReceiptInfo(text("receipt_id", 64), text("label", 200), regions, optional("purpose", purposeLabels.keys))
        validateReceiptInfo(info)
        ServerReceipt(info, text("intake_id", 36).also { check(validUuid(it)) }, text("status", 32).also { check(it in intakeStatuses) },
            optional("error_code"), optional("draft_id", uuid = true), optional("draft_status", setOf("needs_review", "confirmed", "voided", "deleted")),
            optional("ledger_id", uuid = true), optional("ledger_status", setOf("active", "voided")))
    }
    check(receipts.map { it.info.receiptId }.distinct().size == receipts.size)
    check(receipts.map { it.intakeId }.distinct().size == receipts.size)
    check(receipts.none { it.intakeId == json.text("id") })
    return receipts
}

internal fun ServerReceipt.asIntake(): ServerIntake {
    val summary = JsonObject().apply {
        addProperty("id", intakeId); addProperty("status", status); errorCode?.let { addProperty("error_code", it) }
    }
    return ServerIntake(intakeId, status, draftId, summary.toString(), draftStatus, ledgerId, ledgerStatus)
}

internal fun projectIntake(old: MobileBatch, result: ServerIntake): MobileBatch {
    if (old.deleted) return old
    return old.copy(intakeId = result.id, status = result.status, draftId = result.draftId ?: old.draftId, serverJson = result.rawJson,
        message = null, syncRetryable = false, syncRetryAt = null, syncFailures = 0,
        draftStatus = if (old.draftStatus == "voided" || old.ledgerStatus == "voided") "voided" else if (old.ledgerId != null && result.ledgerId == null) old.draftStatus else result.draftStatus,
        ledgerId = old.ledgerId ?: result.ledgerId,
        ledgerStatus = if (old.ledgerStatus == "voided") "voided" else result.ledgerStatus ?: old.ledgerStatus,
        deletedAt = if (result.status == "deleted" || result.draftStatus == "deleted") System.currentTimeMillis() else old.deletedAt)
}

internal fun MobileBatch.receiptChildren(batches: List<MobileBatch>): List<MobileBatch> = receiptIds.orEmpty().mapNotNull { id -> batches.firstOrNull { it.id == id && it.parentBatchId == this.id } }

internal data class ReceiptGroupSummary(val total: Int, val confirmed: Int, val voided: Int, val deleted: Int, val pending: Int) {
    val text: String get() = "$total 張收據 · $confirmed 已入帳 · $pending 待處理" +
        (if (voided > 0) " · $voided 已作廢" else "") + (if (deleted > 0) " · $deleted 已刪除" else "")
}
internal fun MobileBatch.groupSummary(batches: List<MobileBatch>, mutations: List<ReceiptMutation> = emptyList()): ReceiptGroupSummary {
    val statuses = receiptChildren(batches).map { receiptStatus(it, mutations) }
    val total = receiptIds.orEmpty().size
    val confirmed = statuses.count { it == ReceiptStatus.Completed }
    val voided = statuses.count { it == ReceiptStatus.Voided }
    val deleted = statuses.count { it == ReceiptStatus.Deleted }
    return ReceiptGroupSummary(total, confirmed, voided, deleted, total - confirmed - voided - deleted)
}
internal fun groupedReceiptStatus(batch: MobileBatch, batches: List<MobileBatch>, mutations: List<ReceiptMutation>): ReceiptStatus {
    if (!batch.isReceiptGroup) return receiptStatus(batch, mutations)
    val statuses = batch.receiptChildren(batches).map { receiptStatus(it, mutations) }
    if (batch.status == "succeeded" && statuses.size == batch.receiptIds.orEmpty().size && statuses.all { it == ReceiptStatus.Completed }) return ReceiptStatus.Completed
    statuses.firstOrNull { it in setOf(ReceiptStatus.DeletePending, ReceiptStatus.VoidPending, ReceiptStatus.NamesPending) }?.let { return it }
    if (batch.status == "failed" || statuses.any { it == ReceiptStatus.Failed }) return ReceiptStatus.Failed
    if (statuses.any { it == ReceiptStatus.NeedsReview }) return ReceiptStatus.NeedsReview
    if (batch.status == "succeeded") return ReceiptStatus.GroupIncomplete
    return receiptStatus(batch, mutations)
}

/** Display the user's current per-item choices before the original proposal. No amount is inferred. */
internal fun receiptPurposeText(child: MobileBatch): String {
    val choices = child.review?.editor?.array("line_decisions")?.map { it.asJsonObject }?.map { decision ->
        if (decision.text("method") == "whole_line") purposeLabels[decision.text("whole_purpose")] ?: "用途待核對" else "分攤"
    }?.distinct().orEmpty()
    if (choices.isNotEmpty()) return choices.joinToString("、")
    return child.receiptInfo?.purpose?.let { "建議${purposeLabels[it]}，待核對" } ?: "用途待核對"
}
internal fun receiptAmountText(child: MobileBatch): String = child.review?.editor?.getAsJsonObject("candidate")?.get("printed_total_yuan")?.let(::moneyText) ?: "金額待讀取"
