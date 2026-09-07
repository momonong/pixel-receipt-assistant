package com.momonong.pixelreceipt.domain.port

import com.momonong.pixelreceipt.domain.model.Fact
import com.momonong.pixelreceipt.domain.model.PromotionOffer
import com.momonong.pixelreceipt.domain.model.UnknownFactReason

/** Input for an optional retailer lookup when the user did not capture a price or promotion tag. */
class RetailPromotionQuery(
    val merchant: String,
    val branchHint: String? = null,
    val transactionDateEpochDay: Long? = null,
    productHints: Collection<RetailProductHint>,
) {
    val productHints: List<RetailProductHint> = productHints.toList()

    init {
        require(merchant.isNotBlank()) { "Retail promotion query merchant cannot be blank." }
        require(branchHint == null || branchHint.isNotBlank()) {
            "Retail promotion branch hint cannot be blank when present."
        }
        require(this.productHints.isNotEmpty()) {
            "Retail promotion lookup requires at least one purchased product hint."
        }
    }
}

data class RetailProductHint(
    val receiptLineId: String,
    val rawName: String,
    val barcodeOrSku: String? = null,
) {
    init {
        require(receiptLineId.isNotBlank()) { "Receipt line id cannot be blank." }
        require(rawName.isNotBlank()) { "Retail product name cannot be blank." }
        require(barcodeOrSku == null || barcodeOrSku.isNotBlank()) {
            "Product identifier cannot be blank when present."
        }
    }
}

/** Retrieval metadata is mandatory so a candidate can be rechecked rather than trusted blindly. */
data class RetailPromotionSource(
    val sourceUri: String,
    val publisher: String,
    val sourceKind: RetailPromotionSourceKind,
    val retrievedAtEpochMillis: Long,
) {
    init {
        require(sourceUri.startsWith("https://")) { "Promotion source must use HTTPS." }
        require(publisher.isNotBlank()) { "Promotion source publisher cannot be blank." }
        require(retrievedAtEpochMillis >= 0) { "Retrieval timestamp cannot be negative." }
    }
}

enum class RetailPromotionSourceKind {
    OfficialRetailer,
    ThirdParty,
}

/** Unresolved applicability stays Unknown and prevents automatic promotion confirmation. */
data class RetailPromotionApplicability(
    val validFromEpochDay: Fact<Long> = Fact.Unknown(UnknownFactReason.NotObserved),
    val validUntilEpochDay: Fact<Long> = Fact.Unknown(UnknownFactReason.NotObserved),
    val branch: Fact<String> = Fact.Unknown(UnknownFactReason.NotObserved),
    val membership: Fact<String> = Fact.Unknown(UnknownFactReason.NotObserved),
    val paymentMethod: Fact<String> = Fact.Unknown(UnknownFactReason.NotObserved),
)

/**
 * A lookup result is intentionally a candidate wrapper, not an applied adjustment. Reconciliation
 * can rank it, but only deterministic proof or a user decision may confirm its application.
 */
data class RetailPromotionCandidate(
    val offer: PromotionOffer,
    val source: RetailPromotionSource,
    val applicability: RetailPromotionApplicability,
)

interface RetailPromotionLookup {
    suspend fun findCandidates(query: RetailPromotionQuery): RetailPromotionLookupResult
}

sealed interface RetailPromotionLookupResult {
    class Candidates(candidates: Collection<RetailPromotionCandidate>) : RetailPromotionLookupResult {
        val candidates: List<RetailPromotionCandidate> = candidates.toList()
    }

    data object RetailerNotSupported : RetailPromotionLookupResult

    data class Failed(val kind: RetailPromotionLookupErrorKind) : RetailPromotionLookupResult
}

enum class RetailPromotionLookupErrorKind {
    Network,
    RateLimited,
    SourceChanged,
    InvalidResponse,
}
