package com.momonong.pixelreceipt.domain.rules

import com.momonong.pixelreceipt.domain.model.Fact
import com.momonong.pixelreceipt.domain.model.FactProvenance
import com.momonong.pixelreceipt.domain.model.LinePortion
import com.momonong.pixelreceipt.domain.model.Money
import com.momonong.pixelreceipt.domain.model.PromotionTerms
import com.momonong.pixelreceipt.domain.model.RewardSelection
import com.momonong.pixelreceipt.domain.model.UnknownFactReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PromotionPricingEngineTest {
    private val engine = PromotionPricingEngine()

    @Test
    fun `fixed unit price uses every explicitly selected quantity`() {
        val result = price(
            terms = PromotionTerms.FixedUnitPrice(Money(50)),
            portions = listOf(portion("line-a", quantity = 2, unitPrice = 80)),
        )

        assertPriced(result, regular = 160, discount = 60, payable = 100)
    }

    @Test
    fun `fixed and multi-buy prices that do not save money are not eligible`() {
        val fixed = price(
            terms = PromotionTerms.FixedUnitPrice(Money(100)),
            portions = listOf(portion("line-a", unitPrice = 100)),
        )
        assertEquals(
            PromotionPricingResult.NotEligible(
                PromotionPricingNotEligibleReason.OfferDoesNotReducePrice(
                    regularSubtotalMinorUnits = 100.toBigInteger(),
                    offerSubtotalMinorUnits = 100.toBigInteger(),
                    currencyCode = "TWD",
                ),
            ),
            fixed,
        )

        val multiBuy = price(
            terms = PromotionTerms.MultiBuy(requiredQuantity = 2, bundlePrice = Money(250)),
            portions = listOf(portion("line-a", quantity = 2, unitPrice = 100)),
        )
        assertEquals(
            PromotionPricingResult.NotEligible(
                PromotionPricingNotEligibleReason.OfferDoesNotReducePrice(
                    regularSubtotalMinorUnits = 200.toBigInteger(),
                    offerSubtotalMinorUnits = 250.toBigInteger(),
                    currencyCode = "TWD",
                ),
            ),
            multiBuy,
        )
    }

    @Test
    fun `multi-buy prices complete heterogeneous bundles without floating point`() {
        val result = price(
            terms = PromotionTerms.MultiBuy(requiredQuantity = 2, bundlePrice = Money(100)),
            portions = listOf(
                portion("line-a", unitPrice = 80),
                portion("line-b", unitPrice = 70),
                portion("line-c", quantity = 2, unitPrice = 60),
            ),
        )

        assertPriced(result, regular = 270, discount = 70, payable = 200)
    }

    @Test
    fun `multi-buy refuses to guess which remainder is outside the application`() {
        val result = price(
            terms = PromotionTerms.MultiBuy(requiredQuantity = 2, bundlePrice = Money(100)),
            portions = listOf(portion("line-a", quantity = 3, unitPrice = 80)),
        )

        assertEquals(
            PromotionPricingResult.Indeterminate(
                PromotionPricingIndeterminateReason.IncompletePromotionGroup(
                    selectedQuantity = 3.toBigInteger(),
                    requiredGroupSize = 2.toBigInteger(),
                ),
            ),
            result,
        )
    }

    @Test
    fun `buy x get y can deterministically reward cheapest eligible units`() {
        val result = price(
            terms = PromotionTerms.BuyXGetY(
                buyQuantity = 2,
                rewardQuantity = 1,
                rewardSelection = RewardSelection.LowestPricedParticipatingUnits,
            ),
            portions = listOf(
                portion("line-a", quantity = 2, unitPrice = 100),
                portion("line-b", unitPrice = 60),
            ),
        )

        assertPriced(result, regular = 260, discount = 60, payable = 200)
    }

    @Test
    fun `same-product reward uses integer basis points`() {
        val result = price(
            terms = PromotionTerms.BuyXGetY(
                buyQuantity = 1,
                rewardQuantity = 1,
                rewardSelection = RewardSelection.SameProductUnits,
                rewardBasisPoints = 5_000,
            ),
            portions = listOf(portion("line-a", quantity = 2, unitPrice = 51)),
        )

        // 51 * 5000 / 10000 = 25.5, rounded down to a minor unit.
        assertPriced(result, regular = 102, discount = 25, payable = 77)
    }

    @Test
    fun `same-product reward does not combine incomplete quantities across products`() {
        val result = price(
            terms = PromotionTerms.BuyXGetY(
                buyQuantity = 1,
                rewardQuantity = 1,
                rewardSelection = RewardSelection.SameProductUnits,
            ),
            portions = listOf(
                portion("line-a", quantity = 1, unitPrice = 50),
                portion("line-b", quantity = 1, unitPrice = 50),
            ),
        )

        assertEquals(
            PromotionPricingResult.Indeterminate(
                PromotionPricingIndeterminateReason.IncompletePromotionGroup(
                    selectedQuantity = 1.toBigInteger(),
                    requiredGroupSize = 2.toBigInteger(),
                ),
            ),
            result,
        )
    }

    @Test
    fun `lowest-priced reward uses a stable policy across products`() {
        val result = price(
            terms = PromotionTerms.BuyXGetY(
                buyQuantity = 2,
                rewardQuantity = 1,
                rewardSelection = RewardSelection.LowestPricedParticipatingUnits,
            ),
            portions = listOf(
                portion("line-a", unitPrice = 90),
                portion("line-b", unitPrice = 40),
                portion("line-c", unitPrice = 70),
            ),
        )

        assertPriced(result, regular = 200, discount = 40, payable = 160)
    }

    @Test
    fun `percentage discount uses basis points and rounds down`() {
        val result = price(
            terms = PromotionTerms.PercentageOff(basisPoints = 3_333),
            portions = listOf(portion("line-a", unitPrice = 101)),
        )

        assertPriced(result, regular = 101, discount = 33, payable = 68)
    }

    @Test
    fun `fixed discount and basket threshold do not create negative payable amounts`() {
        val applicableFixed = price(
            terms = PromotionTerms.FixedDiscount(Money(30)),
            portions = listOf(portion("line-a", unitPrice = 100)),
        )
        assertPriced(applicableFixed, regular = 100, discount = 30, payable = 70)

        val fixed = price(
            terms = PromotionTerms.FixedDiscount(Money(101)),
            portions = listOf(portion("line-a", unitPrice = 100)),
        )
        assertEquals(
            PromotionPricingResult.Invalid(
                PromotionPricingInvalidReason.DiscountExceedsSubtotal(
                    discountMinorUnits = 101.toBigInteger(),
                    subtotalMinorUnits = 100.toBigInteger(),
                ),
            ),
            fixed,
        )

        val belowThreshold = price(
            terms = PromotionTerms.BasketThreshold(
                minimumSpend = Money(200),
                discount = Money(30),
            ),
            portions = listOf(portion("line-a", unitPrice = 199)),
        )
        assertEquals(
            PromotionPricingResult.NotEligible(
                PromotionPricingNotEligibleReason.ThresholdNotMet(
                    minimumSpend = Money(200),
                    actualSubtotalMinorUnits = 199.toBigInteger(),
                ),
            ),
            belowThreshold,
        )

        val qualifies = price(
            terms = PromotionTerms.BasketThreshold(
                minimumSpend = Money(200),
                discount = Money(30),
            ),
            portions = listOf(portion("line-a", unitPrice = 200)),
        )
        assertPriced(qualifies, regular = 200, discount = 30, payable = 170)
    }

    @Test
    fun `unknown conflicting not-applicable and opaque terms remain typed indeterminate`() {
        val portions = listOf(portion("line-a", unitPrice = 100))

        assertEquals(
            PromotionPricingResult.Indeterminate(
                PromotionPricingIndeterminateReason.TermsUnknown(UnknownFactReason.Unreadable),
            ),
            engine.price(
                PromotionPricingRequest(
                    terms = Fact.Unknown(UnknownFactReason.Unreadable),
                    portions = portions,
                ),
            ),
        )

        val conflicting = Fact.Conflicting(
            listOf(
                known<PromotionTerms>(PromotionTerms.FixedDiscount(Money(10))),
                known<PromotionTerms>(PromotionTerms.FixedDiscount(Money(20))),
            ),
        )
        assertEquals(
            PromotionPricingResult.Indeterminate(
                PromotionPricingIndeterminateReason.TermsConflicting(candidateCount = 2),
            ),
            engine.price(PromotionPricingRequest(conflicting, portions)),
        )

        assertEquals(
            PromotionPricingResult.Indeterminate(
                PromotionPricingIndeterminateReason.TermsNotApplicable("會員限定且未登入"),
            ),
            engine.price(
                PromotionPricingRequest(
                    terms = Fact.NotApplicable("會員限定且未登入"),
                    portions = portions,
                ),
            ),
        )

        assertEquals(
            PromotionPricingResult.Indeterminate(
                PromotionPricingIndeterminateReason.OpaqueTerms("第二件神秘折扣"),
            ),
            price(PromotionTerms.Opaque("第二件神秘折扣"), portions),
        )
    }

    @Test
    fun `mixed currencies and duplicate receipt lines are invalid`() {
        val mixedCurrencies = price(
            terms = PromotionTerms.PercentageOff(1_000),
            portions = listOf(
                portion("line-a", unitPrice = 100, currency = "TWD"),
                portion("line-b", unitPrice = 10, currency = "USD"),
            ),
        )
        assertTrue(mixedCurrencies is PromotionPricingResult.Invalid)
        assertTrue(
            (mixedCurrencies as PromotionPricingResult.Invalid).reason is
                PromotionPricingInvalidReason.CurrencyMismatch,
        )

        val duplicate = price(
            terms = PromotionTerms.PercentageOff(1_000),
            portions = listOf(
                portion("line-a", unitPrice = 100),
                portion("line-a", unitPrice = 100),
            ),
        )
        assertEquals(
            PromotionPricingResult.Invalid(
                PromotionPricingInvalidReason.DuplicateReceiptLine("line-a"),
            ),
            duplicate,
        )
    }

    @Test
    fun `all arithmetic is checked before converting back to Money`() {
        val result = price(
            terms = PromotionTerms.PercentageOff(5_000),
            portions = listOf(portion("line-a", quantity = 2, unitPrice = Long.MAX_VALUE)),
        )

        assertEquals(
            PromotionPricingResult.Invalid(
                PromotionPricingInvalidReason.AmountOutOfRange(
                    Long.MAX_VALUE.toBigInteger() * 2.toBigInteger(),
                ),
            ),
            result,
        )
    }

    @Test
    fun `request snapshots caller-owned portions and priced result records rule version`() {
        val mutablePortions = mutableListOf(portion("line-a", unitPrice = 100))
        val request = PromotionPricingRequest(
            terms = known(PromotionTerms.FixedDiscount(Money(10))),
            portions = mutablePortions,
        )
        mutablePortions.clear()

        val result = PromotionPricingEngine(ruleVersion = "pricing-v1").price(request)

        assertEquals(
            PromotionPricingResult.Priced(
                regularSubtotal = Money(100),
                discount = Money(10),
                payableSubtotal = Money(90),
                ruleVersion = "pricing-v1",
            ),
            result,
        )
    }

    private fun price(
        terms: PromotionTerms,
        portions: List<PricedLinePortion>,
    ): PromotionPricingResult = engine.price(
        PromotionPricingRequest(
            terms = known(terms),
            portions = portions,
        ),
    )

    private fun portion(
        id: String,
        quantity: Int = 1,
        unitPrice: Long,
        currency: String = "TWD",
    ) = PricedLinePortion(
        portion = LinePortion(id, quantity),
        unitPrice = Money(unitPrice, currency),
    )

    private fun assertPriced(
        result: PromotionPricingResult,
        regular: Long,
        discount: Long,
        payable: Long,
    ) {
        assertEquals(
            PromotionPricingResult.Priced(
                regularSubtotal = Money(regular),
                discount = Money(discount),
                payableSubtotal = Money(payable),
                ruleVersion = "1",
            ),
            result,
        )
    }

    private fun <T : Any> known(value: T): Fact.Known<T> = Fact.Known(
        value = value,
        provenance = FactProvenance.UserConfirmed(confirmedAtEpochMillis = 1),
    )
}
