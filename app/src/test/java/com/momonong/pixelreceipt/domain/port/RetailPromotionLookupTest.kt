package com.momonong.pixelreceipt.domain.port

import com.momonong.pixelreceipt.domain.model.Fact
import com.momonong.pixelreceipt.domain.model.FactProvenance
import com.momonong.pixelreceipt.domain.model.Money
import com.momonong.pixelreceipt.domain.model.PromotionEligibility
import com.momonong.pixelreceipt.domain.model.PromotionOffer
import com.momonong.pixelreceipt.domain.model.PromotionTerms
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RetailPromotionLookupTest {
    @Test
    fun `lookup can start from purchased receipt hints without any image`() {
        val query = RetailPromotionQuery(
            merchant = "寶雅",
            branchHint = "台南店",
            productHints = listOf(RetailProductHint("line-1", "洗髮精")),
        )

        assertEquals("line-1", query.productHints.single().receiptLineId)
    }

    @Test
    fun `external result remains a candidate with unresolved applicability`() {
        val candidate = RetailPromotionCandidate(
            offer = PromotionOffer(
                id = "offer-1",
                title = known("任選折扣"),
                productMentions = emptyList(),
                eligibility = known<PromotionEligibility>(PromotionEligibility.StoreWide),
                terms = known<PromotionTerms>(PromotionTerms.FixedDiscount(Money(20))),
            ),
            source = RetailPromotionSource(
                sourceUri = "https://www.poya.com.tw/events/example",
                publisher = "寶雅",
                sourceKind = RetailPromotionSourceKind.OfficialRetailer,
                retrievedAtEpochMillis = 1,
            ),
            applicability = RetailPromotionApplicability(),
        )

        assertTrue(candidate.applicability.branch is Fact.Unknown)
        assertThrows(IllegalArgumentException::class.java) {
            candidate.source.copy(sourceUri = "http://insecure.example")
        }
    }

    private fun <T : Any> known(value: T) = Fact.Known(
        value = value,
        provenance = FactProvenance.UserConfirmed(confirmedAtEpochMillis = 1),
    )
}
