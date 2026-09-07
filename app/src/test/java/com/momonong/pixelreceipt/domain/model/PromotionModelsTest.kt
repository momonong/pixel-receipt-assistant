package com.momonong.pixelreceipt.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PromotionModelsTest {
    @Test
    fun `large sign offer can apply to only the purchased subset`() {
        val offer = offer(
            mentionIds = listOf("product-a", "product-b", "product-c", "product-d"),
        )
        val application = PromotionApplication(
            id = "application-1",
            offerId = offer.id,
            participants = setOf(
                candidateParticipant(receiptLineId = "receipt-b", productMentionId = "product-b"),
                candidateParticipant(receiptLineId = "receipt-d", productMentionId = "product-d"),
            ),
            actualAdjustmentIds = emptySet(),
            expectedDiscount = Fact.Unknown(UnknownFactReason.PendingAnalysis),
            status = PromotionApplicationStatus.Proposed,
            decisionProvenance = extracted(),
        )

        assertEquals(4, offer.productMentions.size)
        assertEquals(
            mapOf("receipt-b" to "product-b", "receipt-d" to "product-d"),
            application.participants.associate {
                it.portion.receiptLineId to it.productMentionId
            },
        )
        assertTrue(
            application.participants.all { participant ->
                offer.productMentions.any { it.id == participant.productMentionId }
            },
        )
    }

    @Test
    fun `offer rejects eligibility that references an absent mention`() {
        assertThrows(IllegalArgumentException::class.java) {
            PromotionOffer(
                id = "offer-1",
                title = known("任選優惠"),
                productMentions = listOf(mention("product-a")),
                eligibility = known(
                    PromotionEligibility.ExplicitProducts(setOf("product-a", "missing")),
                ),
                terms = known<PromotionTerms>(PromotionTerms.MultiBuy(2, Money(100))),
            )
        }
    }

    @Test
    fun `line set rejects two portions of the same receipt line`() {
        assertThrows(IllegalArgumentException::class.java) {
            ReceiptAdjustmentScope.LineSet(
                setOf(
                    LinePortion("line-1", quantity = 1),
                    LinePortion("line-1", quantity = 2),
                ),
            )
        }
    }

    @Test
    fun `order adjustment can preserve an unknown amount without becoming zero`() {
        val adjustment = ReceiptAdjustment(
            id = "adjustment-1",
            kind = ReceiptAdjustmentKind.Promotion,
            direction = AdjustmentDirection.Subtract,
            amount = Fact.Unknown(UnknownFactReason.Unreadable),
            scope = known(ReceiptAdjustmentScope.Order),
        )

        assertTrue(adjustment.amount is Fact.Unknown)
    }

    @Test
    fun `confirmed participant rejects extracted match provenance`() {
        assertThrows(IllegalArgumentException::class.java) {
            PromotionParticipant(
                portion = LinePortion("line-1", quantity = 1),
                productMentionId = "product-1",
                status = PromotionMatchStatus.Confirmed,
                matchProvenance = extracted(),
                confidenceBasisPoints = 9_000,
            )
        }
    }

    @Test
    fun `confirmed promotion rejects extracted decision provenance`() {
        assertThrows(IllegalArgumentException::class.java) {
            PromotionApplication(
                id = "application-1",
                offerId = "offer-1",
                participants = listOf(confirmedParticipant()),
                actualAdjustmentIds = setOf("adjustment-1"),
                expectedDiscount = known(Money(10)),
                status = PromotionApplicationStatus.Confirmed,
                decisionProvenance = extracted(),
            )
        }
    }

    @Test
    fun `confirmed promotion rejects candidate participant matches`() {
        assertThrows(IllegalArgumentException::class.java) {
            PromotionApplication(
                id = "application-1",
                offerId = "offer-1",
                participants = listOf(candidateParticipant("line-1", "product-1")),
                actualAdjustmentIds = setOf("adjustment-1"),
                expectedDiscount = known(Money(10)),
                status = PromotionApplicationStatus.Confirmed,
                decisionProvenance = userConfirmed(),
            )
        }
    }

    @Test
    fun `allocation must be derived or confirmed rather than extracted`() {
        val allocate = { provenance: FactProvenance ->
            Allocation(
                id = "allocation-1",
                adjustmentId = "adjustment-1",
                portion = LinePortion("line-1", quantity = 1),
                amount = Money(20),
                method = AllocationMethod.ProportionalGross,
                provenance = provenance,
            )
        }

        val derived = allocate(
            FactProvenance.Derived(
                ruleName = "largest-remainder",
                ruleVersion = "1",
                inputFactIds = setOf("adjustment-amount", "line-gross"),
            ),
        )
        assertEquals(AllocationMethod.ProportionalGross, derived.method)
        assertThrows(IllegalArgumentException::class.java) {
            allocate(
                FactProvenance.Extracted(
                    extraction = extraction(),
                    evidence = setOf(EvidenceReference("asset-1", "region-1")),
                ),
            )
        }
    }

    @Test
    fun `promotion rates use integer basis points`() {
        val halfPrice = PromotionTerms.PercentageOff(5_000)

        assertEquals(5_000, halfPrice.basisPoints)
        assertThrows(IllegalArgumentException::class.java) {
            PromotionTerms.PercentageOff(10_001)
        }
    }

    private fun offer(mentionIds: List<String>): PromotionOffer {
        val mentions = mentionIds.map(::mention)
        return PromotionOffer(
            id = "offer-1",
            title = known("任選兩件 100 元"),
            productMentions = mentions,
            eligibility = known(
                PromotionEligibility.ExplicitProducts(mentionIds.toSet()),
            ),
            terms = known<PromotionTerms>(PromotionTerms.MultiBuy(2, Money(100))),
        )
    }

    private fun mention(id: String) = PromotionProductMention(
        id = id,
        displayName = known(id),
    )

    private fun <T : Any> known(value: T): Fact.Known<T> = Fact.Known(
        value = value,
        provenance = userConfirmed(),
    )

    private fun candidateParticipant(
        receiptLineId: String,
        productMentionId: String,
    ) = PromotionParticipant(
        portion = LinePortion(receiptLineId, quantity = 1),
        productMentionId = productMentionId,
        status = PromotionMatchStatus.Candidate,
        matchProvenance = extracted(),
        confidenceBasisPoints = 9_000,
    )

    private fun confirmedParticipant() = PromotionParticipant(
        portion = LinePortion("line-1", quantity = 1),
        productMentionId = "product-1",
        status = PromotionMatchStatus.Confirmed,
        matchProvenance = userConfirmed(),
    )

    private fun userConfirmed() = FactProvenance.UserConfirmed(confirmedAtEpochMillis = 1)

    private fun extraction() = ExtractionProvenance(
        runId = "run-1",
        extractorName = "promotion-extractor",
        extractorVersion = "1.0",
        schemaVersion = "1",
        runtime = ExtractionRuntime.Cloud,
        extractedAtEpochMillis = 1,
    )

    private fun extracted(): FactProvenance.Extracted = FactProvenance.Extracted(
        extraction = extraction(),
        evidence = setOf(EvidenceReference("asset-1", "region-1")),
    )
}
