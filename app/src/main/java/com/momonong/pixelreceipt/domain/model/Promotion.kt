package com.momonong.pixelreceipt.domain.model

/** One product explicitly mentioned or pictured by a promotion sign. */
data class PromotionProductMention(
    val id: String,
    val displayName: Fact<String>,
    val barcodeOrSku: Fact<String> = Fact.Unknown(UnknownFactReason.NotObserved),
) {
    init {
        require(id.isNotBlank()) { "Promotion product mention id cannot be blank." }
        require(displayName.candidateValues().all(String::isNotBlank)) {
            "Known promotion product names cannot be blank."
        }
        require(barcodeOrSku.candidateValues().all(String::isNotBlank)) {
            "Known promotion product identifiers cannot be blank."
        }
    }
}

/** A machine-readable description of which products an offer can cover. */
sealed interface PromotionEligibility {
    class ExplicitProducts(
        productMentionIds: Collection<String>,
    ) : PromotionEligibility {
        val productMentionIds: Set<String> = productMentionIds.toSet()

        init {
            require(this.productMentionIds.isNotEmpty()) {
                "Explicit promotion eligibility requires product mentions."
            }
            require(this.productMentionIds.none(String::isBlank)) {
                "Promotion product mention ids cannot be blank."
            }
        }

        override fun equals(other: Any?): Boolean =
            other is ExplicitProducts && productMentionIds == other.productMentionIds

        override fun hashCode(): Int = productMentionIds.hashCode()

        override fun toString(): String = "ExplicitProducts(productMentionIds=$productMentionIds)"
    }

    data class DescribedGroup(
        val description: String,
    ) : PromotionEligibility {
        init {
            require(description.isNotBlank()) { "Promotion group description cannot be blank." }
        }
    }

    data object StoreWide : PromotionEligibility
}

/** Promotion terms supported by the first deterministic evaluator. */
sealed interface PromotionTerms {
    data class FixedUnitPrice(
        val unitPrice: Money,
    ) : PromotionTerms

    data class MultiBuy(
        val requiredQuantity: Int,
        val bundlePrice: Money,
    ) : PromotionTerms {
        init {
            require(requiredQuantity > 1) { "A multi-buy offer requires at least two items." }
        }
    }

    data class BuyXGetY(
        val buyQuantity: Int,
        val rewardQuantity: Int,
        val rewardSelection: RewardSelection,
        val rewardBasisPoints: Int = FullDiscountBasisPoints,
    ) : PromotionTerms {
        init {
            require(buyQuantity > 0) { "Buy quantity must be greater than zero." }
            require(rewardQuantity > 0) { "Reward quantity must be greater than zero." }
            require(rewardBasisPoints in 1..FullDiscountBasisPoints) {
                "Reward rate must be between 1 and 10000 basis points."
            }
        }
    }

    data class PercentageOff(
        val basisPoints: Int,
    ) : PromotionTerms {
        init {
            require(basisPoints in 1..FullDiscountBasisPoints) {
                "Discount rate must be between 1 and 10000 basis points."
            }
        }
    }

    data class FixedDiscount(
        val amount: Money,
    ) : PromotionTerms

    data class BasketThreshold(
        val minimumSpend: Money,
        val discount: Money,
    ) : PromotionTerms {
        init {
            require(minimumSpend.currencyCode == discount.currencyCode) {
                "Threshold and discount currencies must match."
            }
        }
    }

    /** Preserves readable terms that are not safe for deterministic evaluation yet. */
    data class Opaque(
        val rawText: String,
    ) : PromotionTerms {
        init {
            require(rawText.isNotBlank()) { "Opaque promotion terms cannot be blank." }
        }
    }

    private companion object {
        const val FullDiscountBasisPoints = 10_000
    }
}

enum class RewardSelection {
    LowestPricedParticipatingUnits,
    SameProductUnits,
}

/**
 * Offer evidence is independent from the receipt. Mentioned products are an eligibility universe,
 * not proof that any product was purchased.
 */
class PromotionOffer(
    val id: String,
    val title: Fact<String>,
    productMentions: Collection<PromotionProductMention>,
    val eligibility: Fact<PromotionEligibility>,
    val terms: Fact<PromotionTerms>,
) {
    val productMentions: List<PromotionProductMention> = productMentions.toList()

    init {
        require(id.isNotBlank()) { "Promotion offer id cannot be blank." }
        require(title.candidateValues().all(String::isNotBlank)) {
            "Known promotion titles cannot be blank."
        }
        require(productMentions.map(PromotionProductMention::id).distinct().size == productMentions.size) {
            "Promotion product mention ids must be unique."
        }

        val availableMentionIds = productMentions.mapTo(mutableSetOf(), PromotionProductMention::id)
        require(explicitMentionIds(eligibility).all(availableMentionIds::contains)) {
            "Promotion eligibility references an unknown product mention."
        }
    }

    override fun equals(other: Any?): Boolean = other is PromotionOffer &&
        id == other.id &&
        title == other.title &&
        productMentions == other.productMentions &&
        eligibility == other.eligibility &&
        terms == other.terms

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + title.hashCode()
        result = 31 * result + productMentions.hashCode()
        result = 31 * result + eligibility.hashCode()
        result = 31 * result + terms.hashCode()
        return result
    }

    override fun toString(): String = "PromotionOffer(id=$id, productMentions=$productMentions)"
}

/** Connects one purchased receipt quantity to one product explicitly eligible for an offer. */
data class PromotionParticipant(
    val portion: LinePortion,
    val productMentionId: String,
    val status: PromotionMatchStatus,
    val matchProvenance: FactProvenance,
    /** Optional machine confidence for this exact line-to-mention match. */
    val confidenceBasisPoints: Int? = null,
) {
    init {
        require(productMentionId.isNotBlank()) { "Promotion product mention id cannot be blank." }
        require(confidenceBasisPoints == null || confidenceBasisPoints in 0..10_000) {
            "Promotion match confidence must be between 0 and 10000 basis points."
        }
        if (status == PromotionMatchStatus.Confirmed) {
            require(
                matchProvenance is FactProvenance.Derived ||
                    matchProvenance is FactProvenance.UserConfirmed,
            ) {
                "A confirmed product match must be rule-verified or user-confirmed."
            }
        }
    }
}

enum class PromotionMatchStatus {
    Candidate,
    Confirmed,
}

/** A proposed or confirmed application of one offer to receipt quantities actually purchased. */
class PromotionApplication(
    val id: String,
    val offerId: String,
    participants: Collection<PromotionParticipant>,
    actualAdjustmentIds: Collection<String>,
    val expectedDiscount: Fact<Money>,
    val status: PromotionApplicationStatus,
    val decisionProvenance: FactProvenance,
) {
    val participants: Set<PromotionParticipant> = participants.toSet()
    val actualAdjustmentIds: Set<String> = actualAdjustmentIds.toSet()

    init {
        require(id.isNotBlank()) { "Promotion application id cannot be blank." }
        require(offerId.isNotBlank()) { "Promotion offer id cannot be blank." }
        require(this.participants.isNotEmpty()) {
            "Promotion application requires purchased participants."
        }
        requireUniqueReceiptLines(this.participants.map(PromotionParticipant::portion))
        require(this.actualAdjustmentIds.none(String::isBlank)) {
            "Promotion adjustment ids cannot be blank."
        }
        require(expectedDiscount !is Fact.NotApplicable) {
            "A promotion application discount must be known, unknown, or conflicting."
        }
        if (status != PromotionApplicationStatus.Proposed) {
            require(
                decisionProvenance is FactProvenance.Derived ||
                    decisionProvenance is FactProvenance.UserConfirmed,
            ) {
                "A resolved promotion decision must be rule-verified or user-confirmed."
            }
        }
        if (status == PromotionApplicationStatus.Confirmed) {
            require(expectedDiscount is Fact.Known) {
                "A confirmed promotion requires a known expected discount."
            }
            require(this.actualAdjustmentIds.isNotEmpty()) {
                "A confirmed promotion requires at least one actual receipt adjustment."
            }
            require(this.participants.all { it.status == PromotionMatchStatus.Confirmed }) {
                "A confirmed promotion requires confirmed product matches."
            }
        }
        if (status == PromotionApplicationStatus.Rejected) {
            require(this.actualAdjustmentIds.isEmpty()) {
                "A rejected promotion cannot own applied receipt adjustments."
            }
        }
    }

    override fun equals(other: Any?): Boolean = other is PromotionApplication &&
        id == other.id &&
        offerId == other.offerId &&
        participants == other.participants &&
        actualAdjustmentIds == other.actualAdjustmentIds &&
        expectedDiscount == other.expectedDiscount &&
        status == other.status &&
        decisionProvenance == other.decisionProvenance

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + offerId.hashCode()
        result = 31 * result + participants.hashCode()
        result = 31 * result + actualAdjustmentIds.hashCode()
        result = 31 * result + expectedDiscount.hashCode()
        result = 31 * result + status.hashCode()
        result = 31 * result + decisionProvenance.hashCode()
        return result
    }

    override fun toString(): String = "PromotionApplication(id=$id, offerId=$offerId, status=$status)"
}

enum class PromotionApplicationStatus {
    Proposed,
    Confirmed,
    Rejected,
    NotEvaluable,
}

private fun explicitMentionIds(fact: Fact<PromotionEligibility>): Set<String> = when (fact) {
    is Fact.Known -> fact.value.explicitMentionIds()
    is Fact.Conflicting -> fact.candidates.flatMapTo(mutableSetOf()) {
        it.value.explicitMentionIds()
    }
    is Fact.NotApplicable,
    is Fact.Unknown,
    -> emptySet()
}

private fun PromotionEligibility.explicitMentionIds(): Set<String> = when (this) {
    is PromotionEligibility.ExplicitProducts -> productMentionIds
    is PromotionEligibility.DescribedGroup,
    PromotionEligibility.StoreWide,
    -> emptySet()
}
