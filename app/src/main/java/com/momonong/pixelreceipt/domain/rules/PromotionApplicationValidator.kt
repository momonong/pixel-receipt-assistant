package com.momonong.pixelreceipt.domain.rules

import com.momonong.pixelreceipt.domain.model.Fact
import com.momonong.pixelreceipt.domain.model.PromotionApplication
import com.momonong.pixelreceipt.domain.model.PromotionEligibility
import com.momonong.pixelreceipt.domain.model.PromotionOffer
import com.momonong.pixelreceipt.domain.model.PromotionParticipant
import com.momonong.pixelreceipt.domain.model.ReceiptLineDraft

/**
 * Verifies that an offer candidate is linked only to quantities actually present on a receipt.
 *
 * This deliberately does not calculate money. [PromotionPricingEngine] owns that responsibility;
 * this rule only protects the evidence-to-purchase mapping used as its input.
 */
class PromotionApplicationValidator {
    fun validate(
        offer: PromotionOffer,
        application: PromotionApplication,
        receiptLines: Collection<ReceiptLineDraft>,
    ): List<PromotionApplicationIssue> = buildList {
        if (application.offerId != offer.id) {
            add(
                PromotionApplicationIssue.OfferMismatch(
                    expectedOfferId = offer.id,
                    actualOfferId = application.offerId,
                ),
            )
        }

        val linesById = receiptLines.associateBy(ReceiptLineDraft::id)
        val mentionIds = offer.productMentions.mapTo(mutableSetOf()) { it.id }
        val explicitEligibleIds = (offer.eligibility as? Fact.Known)
            ?.value
            ?.explicitProductIds()

        if (offer.eligibility !is Fact.Known) {
            add(PromotionApplicationIssue.UnresolvedEligibility)
        }
        if (offer.terms !is Fact.Known) {
            add(PromotionApplicationIssue.UnresolvedTerms)
        }

        application.participants.forEach { participant ->
            validateParticipant(
                applicationId = application.id,
                participant = participant,
                linesById = linesById,
                mentionIds = mentionIds,
                explicitEligibleIds = explicitEligibleIds,
            )
        }
    }

    private fun MutableList<PromotionApplicationIssue>.validateParticipant(
        applicationId: String,
        participant: PromotionParticipant,
        linesById: Map<String, ReceiptLineDraft>,
        mentionIds: Set<String>,
        explicitEligibleIds: Set<String>?,
    ) {
        if (participant.productMentionId !in mentionIds) {
            add(
                PromotionApplicationIssue.UnknownProductMention(
                    applicationId = applicationId,
                    productMentionId = participant.productMentionId,
                ),
            )
        } else if (explicitEligibleIds != null && participant.productMentionId !in explicitEligibleIds) {
            add(
                PromotionApplicationIssue.IneligibleProductMention(
                    applicationId = applicationId,
                    productMentionId = participant.productMentionId,
                ),
            )
        }

        val line = linesById[participant.portion.receiptLineId]
        if (line == null) {
            add(
                PromotionApplicationIssue.UnknownReceiptLine(
                    applicationId = applicationId,
                    receiptLineId = participant.portion.receiptLineId,
                ),
            )
            return
        }

        val purchasedQuantity = (line.quantity as? Fact.Known)?.value
        if (purchasedQuantity == null) {
            add(
                PromotionApplicationIssue.UnresolvedPurchasedQuantity(
                    receiptLineId = line.id,
                ),
            )
        } else if (participant.portion.quantity > purchasedQuantity) {
            add(
                PromotionApplicationIssue.QuantityExceedsPurchase(
                    receiptLineId = line.id,
                    purchasedQuantity = purchasedQuantity,
                    appliedQuantity = participant.portion.quantity,
                ),
            )
        }
    }
}

private fun PromotionEligibility.explicitProductIds(): Set<String>? = when (this) {
    is PromotionEligibility.ExplicitProducts -> productMentionIds
    is PromotionEligibility.DescribedGroup,
    PromotionEligibility.StoreWide,
    -> null
}

sealed interface PromotionApplicationIssue {
    data class OfferMismatch(
        val expectedOfferId: String,
        val actualOfferId: String,
    ) : PromotionApplicationIssue

    data object UnresolvedEligibility : PromotionApplicationIssue

    data object UnresolvedTerms : PromotionApplicationIssue

    data class UnknownProductMention(
        val applicationId: String,
        val productMentionId: String,
    ) : PromotionApplicationIssue

    data class IneligibleProductMention(
        val applicationId: String,
        val productMentionId: String,
    ) : PromotionApplicationIssue

    data class UnknownReceiptLine(
        val applicationId: String,
        val receiptLineId: String,
    ) : PromotionApplicationIssue

    data class UnresolvedPurchasedQuantity(
        val receiptLineId: String,
    ) : PromotionApplicationIssue

    data class QuantityExceedsPurchase(
        val receiptLineId: String,
        val purchasedQuantity: Int,
        val appliedQuantity: Int,
    ) : PromotionApplicationIssue
}
