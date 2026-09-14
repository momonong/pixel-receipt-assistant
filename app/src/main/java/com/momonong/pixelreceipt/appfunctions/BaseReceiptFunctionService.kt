package com.momonong.pixelreceipt.appfunctions

import androidx.annotation.RequiresApi
import androidx.appfunctions.AppFunction
import androidx.appfunctions.AppFunctionService
import androidx.appfunctions.AppFunctionServiceEntryPoint
import com.momonong.pixelreceipt.app.ReceiptApplication
import com.momonong.pixelreceipt.domain.usecase.CreateAssistantReviewDraft
import com.momonong.pixelreceipt.domain.usecase.ImportAssistantReceipt
import com.momonong.pixelreceipt.domain.model.*
import androidx.appfunctions.AppFunctionInvalidArgumentException
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@RequiresApi(36)
@AppFunctionServiceEntryPoint(serviceName = "ReceiptFunctionService", appFunctionXmlFileName = "receipt_functions")
abstract class BaseReceiptFunctionService : AppFunctionService() {
    /**
     * Import the user's receipt observations as a NEW unconfirmed draft with product rows in PixelReceipt.
     * Use only when the user asks to save a receipt or expense for review. Does not invoke an AI model,
     * read existing financial data, upload photos, decide ownership, or confirm an expense.
     * Pass only observed fields. Unknown is null, never zero or an inferred count/amount.
     * Reuse the request token with identical fields after an uncertain response to avoid duplicates.
     * Different receipts need different tokens, even when their contents are identical.
     * The user opens PixelReceipt to review the draft, optionally attach photos and assign ownership.
     * @param receipt The observed receipt fields and stable retry token.
     * @return Local draft ID. Success means saved for review, not confirmed or financially verified.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun importReceiptForReview(receipt: AssistantReceiptParameters): String = withContext(Dispatchers.IO) {
        try {
            val input = AssistantReceipt("pixelreceipt-1", receipt.currency, receipt.merchant, receipt.date, receipt.totalMinor,
                receipt.items.map { AssistantReceiptItem(it.name, it.quantity, it.lineTotalMinor) },
                receipt.adjustments.map { AssistantReceiptAdjustment(it.label, it.direction, it.amountMinor) })
            input.validate()
            ImportAssistantReceipt((application as ReceiptApplication).repository)
                .create(input, Gson().toJson(input), receipt.requestId, AssistantImportChannel.AppFunction).id
        } catch (error: IllegalArgumentException) {
            throw AppFunctionInvalidArgumentException(error.message ?: "Invalid receipt fields")
        }
    }

    /**
     * Create one new empty TWD receipt draft for the user to review in PixelReceipt.
     * Use only when the user asks to create a receipt draft. Does not read existing receipts,
     * analyze photos, assign ownership, record an amount, or confirm spending.
     * Each invocation creates a new draft; do not automatically retry after an uncertain response.
     * The user must open PixelReceipt to add a photo or edit and confirm the draft.
     * @return The locally generated ID of the new draft, which is still awaiting user review.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun createReviewDraft(): String = withContext(Dispatchers.IO) {
        CreateAssistantReviewDraft((application as ReceiptApplication).repository).create().id
    }
}
