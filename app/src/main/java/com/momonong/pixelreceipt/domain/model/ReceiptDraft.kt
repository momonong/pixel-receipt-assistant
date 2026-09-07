package com.momonong.pixelreceipt.domain.model

/**
 * Evidence-first transaction aggregate.
 *
 * A printed line amount is kept separate from receipt adjustments and from analytical
 * allocations. This lets a receipt remain useful when an original price, discount scope, or
 * even part of the receipt is unknown.
 */
class ReceiptDraft(
    val id: String,
    val merchant: Fact<String> = Fact.Unknown(UnknownFactReason.PendingAnalysis),
    val total: Fact<Money> = Fact.Unknown(UnknownFactReason.PendingAnalysis),
    items: Collection<ReceiptLineDraft> = emptyList(),
    adjustments: Collection<ReceiptAdjustment> = emptyList(),
    allocations: Collection<Allocation> = emptyList(),
    promotionOffers: Collection<PromotionOffer> = emptyList(),
    promotionApplications: Collection<PromotionApplication> = emptyList(),
    evidenceAssetIds: Collection<String> = emptySet(),
    evidenceLinks: Collection<EvidenceLink> = emptyList(),
    val assemblyStatus: ReceiptAssemblyStatus = ReceiptAssemblyStatus.Collecting,
    val stage: ReceiptStage = ReceiptStage.Captured,
    val revision: Long = 0,
) {
    val items: List<ReceiptLineDraft> = items.toList()
    val adjustments: List<ReceiptAdjustment> = adjustments.toList()
    val allocations: List<Allocation> = allocations.toList()
    val promotionOffers: List<PromotionOffer> = promotionOffers.toList()
    val promotionApplications: List<PromotionApplication> = promotionApplications.toList()
    val evidenceAssetIds: Set<String> = evidenceAssetIds.toSet()
    val evidenceLinks: List<EvidenceLink> = evidenceLinks.toList()

    init {
        require(id.isNotBlank()) { "Receipt id cannot be blank." }
        require(revision >= 0) { "Receipt revision cannot be negative." }
        requireUniqueIds(this.items.map(ReceiptLineDraft::id), "Receipt line")
        requireUniqueIds(this.adjustments.map(ReceiptAdjustment::id), "Receipt adjustment")
        requireUniqueIds(this.allocations.map(Allocation::id), "Allocation")
        requireUniqueIds(this.promotionOffers.map(PromotionOffer::id), "Promotion offer")
        requireUniqueIds(
            this.promotionApplications.map(PromotionApplication::id),
            "Promotion application",
        )
        requireUniqueIds(this.evidenceLinks.map(EvidenceLink::id), "Evidence link")
        require(this.evidenceAssetIds.none(String::isBlank)) {
            "Receipt evidence asset ids cannot be blank."
        }
    }

    @Suppress("LongParameterList")
    fun copy(
        id: String = this.id,
        merchant: Fact<String> = this.merchant,
        total: Fact<Money> = this.total,
        items: Collection<ReceiptLineDraft> = this.items,
        adjustments: Collection<ReceiptAdjustment> = this.adjustments,
        allocations: Collection<Allocation> = this.allocations,
        promotionOffers: Collection<PromotionOffer> = this.promotionOffers,
        promotionApplications: Collection<PromotionApplication> = this.promotionApplications,
        evidenceAssetIds: Collection<String> = this.evidenceAssetIds,
        evidenceLinks: Collection<EvidenceLink> = this.evidenceLinks,
        assemblyStatus: ReceiptAssemblyStatus = this.assemblyStatus,
        stage: ReceiptStage = this.stage,
        revision: Long = this.revision,
    ) = ReceiptDraft(
        id = id,
        merchant = merchant,
        total = total,
        items = items,
        adjustments = adjustments,
        allocations = allocations,
        promotionOffers = promotionOffers,
        promotionApplications = promotionApplications,
        evidenceAssetIds = evidenceAssetIds,
        evidenceLinks = evidenceLinks,
        assemblyStatus = assemblyStatus,
        stage = stage,
        revision = revision,
    )

    override fun equals(other: Any?): Boolean = other is ReceiptDraft &&
        id == other.id &&
        merchant == other.merchant &&
        total == other.total &&
        items == other.items &&
        adjustments == other.adjustments &&
        allocations == other.allocations &&
        promotionOffers == other.promotionOffers &&
        promotionApplications == other.promotionApplications &&
        evidenceAssetIds == other.evidenceAssetIds &&
        evidenceLinks == other.evidenceLinks &&
        assemblyStatus == other.assemblyStatus &&
        stage == other.stage &&
        revision == other.revision

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + merchant.hashCode()
        result = 31 * result + total.hashCode()
        result = 31 * result + items.hashCode()
        result = 31 * result + adjustments.hashCode()
        result = 31 * result + allocations.hashCode()
        result = 31 * result + promotionOffers.hashCode()
        result = 31 * result + promotionApplications.hashCode()
        result = 31 * result + evidenceAssetIds.hashCode()
        result = 31 * result + evidenceLinks.hashCode()
        result = 31 * result + assemblyStatus.hashCode()
        result = 31 * result + stage.hashCode()
        result = 31 * result + revision.hashCode()
        return result
    }

    override fun toString(): String =
        "ReceiptDraft(id=$id, stage=$stage, revision=$revision, items=${items.size})"
}

data class ReceiptLineDraft(
    val id: String,
    val rawName: Fact<String> = Fact.Unknown(UnknownFactReason.PendingAnalysis),
    val barcodeOrSku: Fact<String> = Fact.Unknown(UnknownFactReason.NotObserved),
    val quantity: Fact<Int> = Fact.Unknown(UnknownFactReason.PendingAnalysis),
    /** Amount printed for this line before separately printed receipt adjustments. */
    val printedTotal: Fact<Money> = Fact.Unknown(UnknownFactReason.PendingAnalysis),
    /** Shelf/list total used for savings analysis; absence never implies zero savings. */
    val referenceOriginalTotal: Fact<Money> = Fact.Unknown(UnknownFactReason.MissingEvidence),
) {
    init {
        require(id.isNotBlank()) { "Line item id cannot be blank." }
        require(rawName.candidateValues().all(String::isNotBlank)) {
            "Known receipt line names cannot be blank."
        }
        require(barcodeOrSku.candidateValues().all(String::isNotBlank)) {
            "Known receipt line identifiers cannot be blank."
        }
        require(quantity.candidateValues().all { it > 0 }) {
            "Known quantities must be greater than zero."
        }
    }
}

enum class ReceiptAssemblyStatus {
    Collecting,
    NeedsOrdering,
    PossibleGap,
    PossibleDuplicates,
    CompleteByRule,
    CompleteByUser,
}

enum class ReceiptStage {
    Captured,
    PendingAnalysis,
    Analyzing,
    NeedsReview,
    AnalysisFailed,
    Confirmed,
    ExportPending,
    Exported,
    ExportFailed,
    AuthorizationRequired,
    ;

    fun canTransitionTo(next: ReceiptStage): Boolean = next in when (this) {
        Captured -> setOf(PendingAnalysis)
        PendingAnalysis -> setOf(Analyzing)
        Analyzing -> setOf(NeedsReview, AnalysisFailed)
        AnalysisFailed -> setOf(PendingAnalysis)
        NeedsReview -> setOf(PendingAnalysis, Confirmed)
        Confirmed -> setOf(ExportPending)
        ExportPending -> setOf(Exported, ExportFailed, AuthorizationRequired)
        ExportFailed, AuthorizationRequired -> setOf(ExportPending)
        Exported -> emptySet()
    }
}

private fun requireUniqueIds(ids: List<String>, label: String) {
    require(ids.none(String::isBlank)) { "$label ids cannot be blank." }
    require(ids.distinct().size == ids.size) { "$label ids must be unique." }
}

internal fun <T : Any> Fact<T>.candidateValues(): List<T> = when (this) {
    is Fact.Known -> listOf(value)
    is Fact.Conflicting -> candidates.map(Fact.Known<T>::value)
    is Fact.NotApplicable,
    is Fact.Unknown,
    -> emptyList()
}
