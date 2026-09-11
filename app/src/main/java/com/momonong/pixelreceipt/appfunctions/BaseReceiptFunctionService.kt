package com.momonong.pixelreceipt.appfunctions

import androidx.annotation.RequiresApi
import androidx.appfunctions.AppFunction
import androidx.appfunctions.AppFunctionService
import androidx.appfunctions.AppFunctionServiceEntryPoint
import com.momonong.pixelreceipt.app.ReceiptApplication
import com.momonong.pixelreceipt.domain.usecase.CreateAssistantReviewDraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@RequiresApi(36)
@AppFunctionServiceEntryPoint(serviceName = "ReceiptFunctionService", appFunctionXmlFileName = "receipt_functions")
abstract class BaseReceiptFunctionService : AppFunctionService() {
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
