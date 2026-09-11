package com.momonong.pixelreceipt.domain.usecase

import com.momonong.pixelreceipt.domain.model.ReceiptDraft
import com.momonong.pixelreceipt.domain.model.ReceiptStage
import com.momonong.pixelreceipt.domain.port.DraftWriteResult
import com.momonong.pixelreceipt.domain.port.ReceiptRepository
import java.util.UUID

/** A bounded assistant action: only a new empty review draft, never financial confirmation. */
class CreateAssistantReviewDraft(private val repository: ReceiptRepository) {
    suspend fun create(): ReceiptDraft {
        val draft = ReceiptDraft(UUID.randomUUID().toString(), stage = ReceiptStage.NeedsReview)
        check(repository.createDraft(draft) is DraftWriteResult.Written) { "待核對草稿未建立，請開啟 App 確認。" }
        return draft
    }
}
