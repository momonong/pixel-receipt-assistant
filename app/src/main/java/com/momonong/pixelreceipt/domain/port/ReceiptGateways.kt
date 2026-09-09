package com.momonong.pixelreceipt.domain.port

import com.momonong.pixelreceipt.domain.model.Money
import com.momonong.pixelreceipt.domain.model.Fact
import com.momonong.pixelreceipt.domain.model.candidateValues
import com.momonong.pixelreceipt.domain.model.ReceiptDraft
import kotlinx.coroutines.flow.Flow

/** Android-free boundaries. Firebase, Room and Sheets implementations stay in data adapters. */
interface ReceiptRepository {
    fun observeDraft(id: String): Flow<ReceiptDraft?>

    /** Creates revision 0, or returns [DraftWriteResult.Conflict] when the id already exists. */
    suspend fun createDraft(draft: ReceiptDraft): DraftWriteResult

    /**
     * Atomically writes [draft] only when storage still contains [expectedRevision]. [draft]'s
     * revision must be exactly one greater than [expectedRevision].
     * Legal stage transitions are decided by the use case before calling this boundary.
     */
    suspend fun compareAndSetDraft(
        draft: ReceiptDraft,
        expectedRevision: Long,
    ): DraftWriteResult
}

sealed interface DraftWriteResult {
    data class Written(val revision: Long) : DraftWriteResult {
        init {
            require(revision >= 0) { "Written revision cannot be negative." }
        }
    }

    data object Conflict : DraftWriteResult

    data object NotFound : DraftWriteResult
}

@JvmInline
value class LocalImageId(val value: String) {
    init {
        require(value.isNotBlank()) { "Local image id cannot be blank." }
    }
}

/** Recognition facts only: an AI adapter cannot choose local ids, revisions or workflow stages. */
data class AnalyzedReceipt(
    val merchantText: String?,
    val transactionDateText: String?,
    val totalText: String?,
    val currencyCodeText: String?,
    val items: List<AnalyzedLineItem>,
    val recognition: ReceiptRecognition? = null,
)

data class AnalyzedLineItem(
    val rawName: String,
    val standardNameSuggestion: String?,
    val categorySuggestion: String?,
    val quantityText: String?,
    val originalTotalText: String?,
    val discountText: String?,
    val netTotalText: String?,
)

sealed interface AnalysisResult {
    data class Success(val receipt: AnalyzedReceipt) : AnalysisResult

    data class Failure(val error: AnalysisError) : AnalysisResult
}

data class AnalysisError(
    val kind: AnalysisErrorKind,
) {
    val retryable: Boolean
        get() = kind.retryable
}

enum class AnalysisErrorKind(val retryable: Boolean) {
    InvalidOutput(false),
    Network(true),
    RateLimited(true),
    ServiceUnavailable(true),
    AccessDenied(false),
}

/** Immutable, already validated export snapshot prepared by the export use case. */
class ReceiptExportBatch(
    val idempotencyKey: String,
    val receiptId: String,
    val receiptRevision: Long,
    val merchant: String,
    val total: Money,
    rows: List<ReceiptExportRow>,
) {
    val rows: List<ReceiptExportRow> = rows.toList()

    init {
        require(idempotencyKey.isNotBlank()) { "Export idempotency key cannot be blank." }
        require(receiptId.isNotBlank()) { "Export receipt id cannot be blank." }
        require(receiptRevision >= 0) { "Export receipt revision cannot be negative." }
        require(merchant.isNotBlank()) { "Export merchant cannot be blank." }
        require(rows.isNotEmpty()) { "Export batch requires at least one row." }
        require(
            rows.all { row ->
                row.referenceOriginalTotal.moneyCandidates().all {
                    it.currencyCode == total.currencyCode
                } &&
                    row.discountSaved.moneyCandidates().all {
                        it.currencyCode == total.currencyCode
                    } &&
                    row.netTotal.currencyCode == total.currencyCode
            },
        ) { "Export row currencies must match the receipt total." }
    }
}

class ReceiptExportRow(
    val lineItemId: String,
    val rawName: String,
    val quantity: Int,
    /** Unknown remains blank/status-bearing at export; it is never rewritten as net total. */
    val referenceOriginalTotal: Fact<Money>,
    /** Unknown remains distinct from a confirmed zero discount. */
    val discountSaved: Fact<Money>,
    val netTotal: Money,
    val pricingRuleVersion: String? = null,
    evidenceAssetIds: Collection<String> = emptySet(),
) {
    val evidenceAssetIds: Set<String> = evidenceAssetIds.toSet()

    init {
        require(lineItemId.isNotBlank()) { "Export line item id cannot be blank." }
        require(rawName.isNotBlank()) { "Export receipt line name cannot be blank." }
        require(quantity > 0) { "Export quantity must be greater than zero." }
        require(pricingRuleVersion == null || pricingRuleVersion.isNotBlank()) {
            "Pricing rule version cannot be blank when present."
        }
        require(this.evidenceAssetIds.none(String::isBlank)) {
            "Export evidence asset ids cannot be blank."
        }
    }
}

private fun Fact<Money>.moneyCandidates(): List<Money> = candidateValues()

interface ReceiptExporter {
    suspend fun export(batch: ReceiptExportBatch): ExportResult
}

sealed interface ExportResult {
    data object Exported : ExportResult

    data object AuthorizationRequired : ExportResult

    data class Failed(
        val kind: ExportErrorKind,
    ) : ExportResult {
        val retryable: Boolean
            get() = kind.retryable
    }
}

enum class ExportErrorKind(val retryable: Boolean) {
    Network(true),
    RateLimited(true),
    InvalidDestination(false),
    InvalidPayload(false),
    ServiceUnavailable(true),
}
