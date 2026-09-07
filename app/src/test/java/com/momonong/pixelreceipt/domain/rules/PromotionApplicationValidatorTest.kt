package com.momonong.pixelreceipt.domain.rules

import com.momonong.pixelreceipt.domain.model.Fact
import com.momonong.pixelreceipt.domain.model.FactProvenance
import com.momonong.pixelreceipt.domain.model.LinePortion
import com.momonong.pixelreceipt.domain.model.Money
import com.momonong.pixelreceipt.domain.model.PromotionApplication
import com.momonong.pixelreceipt.domain.model.PromotionApplicationStatus
import com.momonong.pixelreceipt.domain.model.PromotionEligibility
import com.momonong.pixelreceipt.domain.model.PromotionMatchStatus
import com.momonong.pixelreceipt.domain.model.PromotionOffer
import com.momonong.pixelreceipt.domain.model.PromotionParticipant
import com.momonong.pixelreceipt.domain.model.PromotionProductMention
import com.momonong.pixelreceipt.domain.model.PromotionTerms
import com.momonong.pixelreceipt.domain.model.ReceiptLineDraft
import com.momonong.pixelreceipt.domain.model.UnknownFactReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PromotionApplicationValidatorTest {
    private val validator = PromotionApplicationValidator()

    @Test
    fun `large sign maps only purchased B and D lines to their explicit mentions`() {
        val offer = offer()
        val application = application(
            participants = listOf(
                confirmedParticipant("line-b", "mention-b"),
                confirmedParticipant("line-d", "mention-d"),
            ),
        )
        val receiptLines = listOf(
            receiptLine("line-b", purchasedQuantity = 1),
            receiptLine("line-d", purchasedQuantity = 1),
        )

        val lineToMention = application.participants.associate { participant ->
            participant.portion.receiptLineId to participant.productMentionId
        }

        assertEquals(4, offer.productMentions.size)
        assertEquals(
            mapOf(
                "line-b" to "mention-b",
                "line-d" to "mention-d",
            ),
            lineToMention,
        )
        assertTrue(validator.validate(offer, application, receiptLines).isEmpty())
    }

    @Test
    fun `participant referencing an unknown product mention is rejected`() {
        val offer = offer()
        val application = application(
            participants = listOf(confirmedParticipant("line-b", "mention-unknown")),
        )

        val issues = validator.validate(
            offer = offer,
            application = application,
            receiptLines = listOf(receiptLine("line-b", purchasedQuantity = 1)),
        )

        assertEquals(
            listOf(
                PromotionApplicationIssue.UnknownProductMention(
                    applicationId = ApplicationId,
                    productMentionId = "mention-unknown",
                ),
            ),
            issues,
        )
    }

    @Test
    fun `known mention outside explicit eligibility is rejected`() {
        val offer = offer(
            eligibility = known(
                PromotionEligibility.ExplicitProducts(setOf("mention-b", "mention-d")),
                "eligible-b-d",
            ),
        )
        val application = application(
            participants = listOf(confirmedParticipant("line-c", "mention-c")),
        )

        val issues = validator.validate(
            offer = offer,
            application = application,
            receiptLines = listOf(receiptLine("line-c", purchasedQuantity = 1)),
        )

        assertEquals(
            listOf(
                PromotionApplicationIssue.IneligibleProductMention(
                    applicationId = ApplicationId,
                    productMentionId = "mention-c",
                ),
            ),
            issues,
        )
    }

    @Test
    fun `applied quantity cannot exceed the purchased receipt quantity`() {
        val offer = offer()
        val application = application(
            participants = listOf(
                confirmedParticipant(
                    receiptLineId = "line-b",
                    productMentionId = "mention-b",
                    appliedQuantity = 2,
                ),
            ),
        )

        val issues = validator.validate(
            offer = offer,
            application = application,
            receiptLines = listOf(receiptLine("line-b", purchasedQuantity = 1)),
        )

        assertEquals(
            listOf(
                PromotionApplicationIssue.QuantityExceedsPurchase(
                    receiptLineId = "line-b",
                    purchasedQuantity = 1,
                    appliedQuantity = 2,
                ),
            ),
            issues,
        )
    }

    @Test
    fun `unknown eligibility and terms remain unresolved`() {
        val offer = offer(
            eligibility = Fact.Unknown(UnknownFactReason.IncompleteExtraction),
            terms = Fact.Unknown(UnknownFactReason.Unreadable),
        )
        val application = application(
            participants = listOf(confirmedParticipant("line-b", "mention-b")),
        )

        val issues = validator.validate(
            offer = offer,
            application = application,
            receiptLines = listOf(receiptLine("line-b", purchasedQuantity = 1)),
        )

        assertEquals(
            listOf(
                PromotionApplicationIssue.UnresolvedEligibility,
                PromotionApplicationIssue.UnresolvedTerms,
            ),
            issues,
        )
    }

    private fun offer(
        eligibility: Fact<PromotionEligibility> = known(
            PromotionEligibility.ExplicitProducts(MentionIds),
            "eligible-a-b-c-d",
        ),
        terms: Fact<PromotionTerms> = known(
            PromotionTerms.MultiBuy(requiredQuantity = 2, bundlePrice = Money(100)),
            "offer-terms",
        ),
    ) = PromotionOffer(
        id = OfferId,
        title = known("A/B/C/D 任選兩件 100 元", "offer-title"),
        productMentions = MentionIds.map { id ->
            PromotionProductMention(
                id = id,
                displayName = known(id, "display-name-$id"),
            )
        },
        eligibility = eligibility,
        terms = terms,
    )

    private fun application(
        participants: Collection<PromotionParticipant>,
    ) = PromotionApplication(
        id = ApplicationId,
        offerId = OfferId,
        participants = participants,
        actualAdjustmentIds = setOf("adjustment-1"),
        expectedDiscount = known(Money(0), "expected-discount"),
        status = PromotionApplicationStatus.Confirmed,
        decisionProvenance = derived("promotion-decision"),
    )

    private fun confirmedParticipant(
        receiptLineId: String,
        productMentionId: String,
        appliedQuantity: Int = 1,
    ) = PromotionParticipant(
        portion = LinePortion(receiptLineId, appliedQuantity),
        productMentionId = productMentionId,
        status = PromotionMatchStatus.Confirmed,
        matchProvenance = derived("$receiptLineId-to-$productMentionId"),
    )

    private fun receiptLine(
        id: String,
        purchasedQuantity: Int,
    ) = ReceiptLineDraft(
        id = id,
        quantity = known(purchasedQuantity, "purchased-quantity-$id"),
    )

    private fun <T : Any> known(
        value: T,
        inputFactId: String,
    ): Fact.Known<T> = Fact.Known(
        value = value,
        provenance = derived(inputFactId),
    )

    private fun derived(inputFactId: String) = FactProvenance.Derived(
        ruleName = "test-fixture",
        ruleVersion = "1",
        inputFactIds = setOf(inputFactId),
    )

    private companion object {
        const val OfferId = "offer-abcd"
        const val ApplicationId = "application-bd"
        val MentionIds = setOf("mention-a", "mention-b", "mention-c", "mention-d")
    }
}
