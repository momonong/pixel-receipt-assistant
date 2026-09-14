package com.momonong.pixelreceipt.domain.usecase

import com.momonong.pixelreceipt.domain.model.*
import com.momonong.pixelreceipt.domain.port.*
import kotlinx.coroutines.flow.first
import java.security.MessageDigest
import java.util.UUID

/** Create only. A retry can return an existing (even edited/confirmed) draft but cannot overwrite it. */
class ImportAssistantReceipt(private val repository: ReceiptRepository) {
    suspend fun create(input: AssistantReceipt, rawInput: String, requestId: String,
        channel: AssistantImportChannel, now: Long = System.currentTimeMillis()): ReceiptDraft {
        input.validate()
        require(rawInput.isNotBlank() && rawInput.length <= 64_000) { "收據內容最多 64,000 字。" }
        require(requestId.matches(Regex("[a-zA-Z0-9_-]{1,100}"))) { "匯入識別碼格式錯誤。" }
        val hash = MessageDigest.getInstance("SHA-256").digest(rawInput.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        // Namespace external retry tokens; they cannot select or replace an existing local ID.
        val id = UUID.nameUUIDFromBytes("pixelreceipt-assistant-v1:${channel.name}:$requestId".toByteArray(Charsets.UTF_8)).toString()
        suspend fun existing(): ReceiptDraft? = repository.observeDraft(id).first()?.also {
            require(it.assistantImport?.inputSha256 == hash && it.assistantImport.channel == channel) {
                "相同匯入識別碼的內容已不同；原本交易未變更。請開始新的匯入。"
            }
        }
        existing()?.let { return it }
        val provenance = FactProvenance.AssistantSuggested(hash, now)
        fun <T : Any> fact(value: T?): Fact<T> = value?.let { Fact.Known(it, provenance) }
            ?: Fact.Unknown(UnknownFactReason.NotObserved)
        val draft = ReceiptDraft(id = id, stage = ReceiptStage.NeedsReview,
            merchant = fact(input.merchant), transactionDate = fact(input.date),
            total = fact(input.totalMinor?.let { Money(it, input.currency) }),
            items = input.items.map { ReceiptLineDraft(UUID.randomUUID().toString(),
                rawName = fact(it.name), quantity = fact(it.quantity),
                printedTotal = fact(it.lineTotalMinor?.let { amount -> Money(amount, input.currency) })) },
            adjustments = input.adjustments.map { ReceiptAdjustment(UUID.randomUUID().toString(),
                ReceiptAdjustmentKind.Other, if (it.direction == "subtract") AdjustmentDirection.Subtract else AdjustmentDirection.Add,
                fact(it.amountMinor?.let { amount -> Money(amount, input.currency) }), Fact.Unknown(UnknownFactReason.Ambiguous)) },
            assistantImport = AssistantImportRecord(channel, hash, now, rawInput))
        return when (repository.createDraft(draft)) {
            is DraftWriteResult.Written -> draft
            DraftWriteResult.Conflict -> checkNotNull(existing()) { "匯入結果不確定，請返回消費紀錄查看。" }
            DraftWriteResult.NotFound -> error("草稿未建立，請稍後重試。")
        }
    }
}
