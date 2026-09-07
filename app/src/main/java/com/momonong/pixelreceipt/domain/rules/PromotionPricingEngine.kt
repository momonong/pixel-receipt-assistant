package com.momonong.pixelreceipt.domain.rules

import com.momonong.pixelreceipt.domain.model.Fact
import com.momonong.pixelreceipt.domain.model.LinePortion
import com.momonong.pixelreceipt.domain.model.Money
import com.momonong.pixelreceipt.domain.model.PromotionTerms
import com.momonong.pixelreceipt.domain.model.RewardSelection
import com.momonong.pixelreceipt.domain.model.UnknownFactReason
import java.math.BigInteger

/**
 * Connects an explicitly selected receipt quantity to its regular unit price.
 *
 * Eligibility and product matching happen before pricing. This engine never adds receipt lines to
 * an application merely because they look eligible.
 */
data class PricedLinePortion(
    val portion: LinePortion,
    val unitPrice: Money,
)

class PromotionPricingRequest(
    val terms: Fact<PromotionTerms>,
    portions: Collection<PricedLinePortion>,
) {
    val portions: List<PricedLinePortion> = portions.toList()
}

sealed interface PromotionPricingResult {
    /** All amounts use the common currency of [PromotionPricingRequest.portions]. */
    data class Priced(
        val regularSubtotal: Money,
        val discount: Money,
        val payableSubtotal: Money,
        val ruleVersion: String,
    ) : PromotionPricingResult

    /** The inputs are complete and valid, but the offer does not apply to this selection. */
    data class NotEligible(
        val reason: PromotionPricingNotEligibleReason,
    ) : PromotionPricingResult

    /** More evidence or an explicit decision is required before a price can be calculated. */
    data class Indeterminate(
        val reason: PromotionPricingIndeterminateReason,
    ) : PromotionPricingResult

    /** The supplied application contradicts itself or cannot be represented by [Money]. */
    data class Invalid(
        val reason: PromotionPricingInvalidReason,
    ) : PromotionPricingResult
}

sealed interface PromotionPricingNotEligibleReason {
    data class ThresholdNotMet(
        val minimumSpend: Money,
        val actualSubtotalMinorUnits: BigInteger,
    ) : PromotionPricingNotEligibleReason

    data class OfferDoesNotReducePrice(
        val regularSubtotalMinorUnits: BigInteger,
        val offerSubtotalMinorUnits: BigInteger,
        val currencyCode: String,
    ) : PromotionPricingNotEligibleReason
}

sealed interface PromotionPricingIndeterminateReason {
    data class TermsUnknown(
        val reason: UnknownFactReason,
    ) : PromotionPricingIndeterminateReason

    data class TermsConflicting(
        val candidateCount: Int,
    ) : PromotionPricingIndeterminateReason

    data class TermsNotApplicable(
        val reason: String,
    ) : PromotionPricingIndeterminateReason

    data class OpaqueTerms(
        val rawText: String,
    ) : PromotionPricingIndeterminateReason

    /**
     * Bundle inputs must describe only the quantities included in complete applications. A caller
     * can split a purchased receipt line into a participating and a non-participating portion.
     */
    data class IncompletePromotionGroup(
        val selectedQuantity: BigInteger,
        val requiredGroupSize: BigInteger,
    ) : PromotionPricingIndeterminateReason
}

sealed interface PromotionPricingInvalidReason {
    data object NoPricedPortions : PromotionPricingInvalidReason

    data class DuplicateReceiptLine(
        val receiptLineId: String,
    ) : PromotionPricingInvalidReason

    data class CurrencyMismatch(
        val expectedCurrencyCode: String,
        val actualCurrencyCode: String,
        val source: String,
    ) : PromotionPricingInvalidReason

    data class DiscountExceedsSubtotal(
        val discountMinorUnits: BigInteger,
        val subtotalMinorUnits: BigInteger,
    ) : PromotionPricingInvalidReason

    data class AmountOutOfRange(
        val amountMinorUnits: BigInteger,
    ) : PromotionPricingInvalidReason
}

/**
 * Pure deterministic promotion pricing.
 *
 * Every intermediate monetary operation uses [BigInteger]. Percentage fractions smaller than one
 * minor currency unit are rounded down, so a computed discount never exceeds the stated rate.
 */
class PromotionPricingEngine(
    private val ruleVersion: String = "1",
) {
    init {
        require(ruleVersion.isNotBlank()) { "Promotion pricing rule version cannot be blank." }
    }

    fun price(request: PromotionPricingRequest): PromotionPricingResult {
        val terms = when (val fact = request.terms) {
            is Fact.Known -> fact.value
            is Fact.Unknown -> return PromotionPricingResult.Indeterminate(
                PromotionPricingIndeterminateReason.TermsUnknown(fact.reason),
            )
            is Fact.Conflicting -> return PromotionPricingResult.Indeterminate(
                PromotionPricingIndeterminateReason.TermsConflicting(fact.candidates.size),
            )
            is Fact.NotApplicable -> return PromotionPricingResult.Indeterminate(
                PromotionPricingIndeterminateReason.TermsNotApplicable(fact.reason),
            )
        }

        if (terms is PromotionTerms.Opaque) {
            return PromotionPricingResult.Indeterminate(
                PromotionPricingIndeterminateReason.OpaqueTerms(terms.rawText),
            )
        }

        val context = when (val result = buildContext(request.portions)) {
            is ContextResult.Valid -> result.context
            is ContextResult.Invalid -> return PromotionPricingResult.Invalid(result.reason)
        }

        return when (terms) {
            is PromotionTerms.FixedUnitPrice -> priceFixedUnit(context, terms)
            is PromotionTerms.MultiBuy -> priceMultiBuy(context, terms)
            is PromotionTerms.BuyXGetY -> priceBuyXGetY(context, terms)
            is PromotionTerms.PercentageOff -> priced(
                context,
                context.regularSubtotal * terms.basisPoints.toBigInteger() / BasisPointScale,
            )
            is PromotionTerms.FixedDiscount -> priceFixedDiscount(context, terms.amount)
            is PromotionTerms.BasketThreshold -> priceBasketThreshold(context, terms)
            is PromotionTerms.Opaque -> error("Opaque terms are handled before pricing context.")
        }
    }

    private fun priceFixedUnit(
        context: PricingContext,
        terms: PromotionTerms.FixedUnitPrice,
    ): PromotionPricingResult {
        currencyMismatch(context, terms.unitPrice, "fixed unit price")?.let { return it }
        val offerSubtotal = terms.unitPrice.minorUnits.toBigInteger() * context.totalQuantity
        if (offerSubtotal >= context.regularSubtotal) {
            return notEligibleBecausePriceDoesNotDrop(context, offerSubtotal)
        }
        return priced(context, context.regularSubtotal - offerSubtotal)
    }

    private fun priceMultiBuy(
        context: PricingContext,
        terms: PromotionTerms.MultiBuy,
    ): PromotionPricingResult {
        currencyMismatch(context, terms.bundlePrice, "multi-buy bundle price")?.let { return it }
        val groupSize = terms.requiredQuantity.toBigInteger()
        val (groupCount, remainder) = context.totalQuantity.divideAndRemainder(groupSize)
        if (remainder != BigInteger.ZERO) {
            return incompleteGroup(context.totalQuantity, groupSize)
        }

        val offerSubtotal = terms.bundlePrice.minorUnits.toBigInteger() * groupCount
        if (offerSubtotal >= context.regularSubtotal) {
            return notEligibleBecausePriceDoesNotDrop(context, offerSubtotal)
        }
        return priced(context, context.regularSubtotal - offerSubtotal)
    }

    private fun priceBuyXGetY(
        context: PricingContext,
        terms: PromotionTerms.BuyXGetY,
    ): PromotionPricingResult {
        val groupSize = terms.buyQuantity.toBigInteger() + terms.rewardQuantity.toBigInteger()
        val rewardSubtotal = when (terms.rewardSelection) {
            RewardSelection.LowestPricedParticipatingUnits -> {
                val (groupCount, remainder) = context.totalQuantity.divideAndRemainder(groupSize)
                if (remainder != BigInteger.ZERO) {
                    return incompleteGroup(context.totalQuantity, groupSize)
                }
                cheapestSubtotal(
                    portions = context.portions,
                    quantity = groupCount * terms.rewardQuantity.toBigInteger(),
                )
            }
            RewardSelection.SameProductUnits -> {
                var subtotal = BigInteger.ZERO
                context.portions.forEach { priced ->
                    val quantity = priced.portion.quantity.toBigInteger()
                    val (groupCount, remainder) = quantity.divideAndRemainder(groupSize)
                    if (remainder != BigInteger.ZERO) {
                        return incompleteGroup(quantity, groupSize)
                    }
                    val rewardQuantity = groupCount * terms.rewardQuantity.toBigInteger()
                    subtotal += priced.unitPrice.minorUnits.toBigInteger() * rewardQuantity
                }
                subtotal
            }
        }

        val discount = rewardSubtotal * terms.rewardBasisPoints.toBigInteger() / BasisPointScale
        return priced(context, discount)
    }

    private fun priceFixedDiscount(
        context: PricingContext,
        amount: Money,
    ): PromotionPricingResult {
        currencyMismatch(context, amount, "fixed discount")?.let { return it }
        return priced(context, amount.minorUnits.toBigInteger())
    }

    private fun priceBasketThreshold(
        context: PricingContext,
        terms: PromotionTerms.BasketThreshold,
    ): PromotionPricingResult {
        currencyMismatch(context, terms.minimumSpend, "basket threshold")?.let { return it }
        currencyMismatch(context, terms.discount, "basket discount")?.let { return it }
        if (context.regularSubtotal < terms.minimumSpend.minorUnits.toBigInteger()) {
            return PromotionPricingResult.NotEligible(
                PromotionPricingNotEligibleReason.ThresholdNotMet(
                    minimumSpend = terms.minimumSpend,
                    actualSubtotalMinorUnits = context.regularSubtotal,
                ),
            )
        }
        return priced(context, terms.discount.minorUnits.toBigInteger())
    }

    private fun cheapestSubtotal(
        portions: List<PricedLinePortion>,
        quantity: BigInteger,
    ): BigInteger {
        var remaining = quantity
        var subtotal = BigInteger.ZERO
        portions.sortedWith(
            compareBy<PricedLinePortion> { it.unitPrice.minorUnits }
                .thenBy { it.portion.receiptLineId },
        ).forEach { priced ->
            if (remaining == BigInteger.ZERO) return@forEach
            val available = priced.portion.quantity.toBigInteger()
            val selected = available.min(remaining)
            subtotal += priced.unitPrice.minorUnits.toBigInteger() * selected
            remaining -= selected
        }
        check(remaining == BigInteger.ZERO) {
            "Reward count is derived from the same participant quantities."
        }
        return subtotal
    }

    private fun buildContext(portions: List<PricedLinePortion>): ContextResult {
        if (portions.isEmpty()) {
            return ContextResult.Invalid(PromotionPricingInvalidReason.NoPricedPortions)
        }

        val duplicateId = portions.groupingBy { it.portion.receiptLineId }
            .eachCount()
            .entries
            .firstOrNull { it.value > 1 }
            ?.key
        if (duplicateId != null) {
            return ContextResult.Invalid(
                PromotionPricingInvalidReason.DuplicateReceiptLine(duplicateId),
            )
        }

        val currencyCode = portions.first().unitPrice.currencyCode
        portions.firstOrNull { it.unitPrice.currencyCode != currencyCode }?.let { mismatched ->
            return ContextResult.Invalid(
                PromotionPricingInvalidReason.CurrencyMismatch(
                    expectedCurrencyCode = currencyCode,
                    actualCurrencyCode = mismatched.unitPrice.currencyCode,
                    source = "receipt line ${mismatched.portion.receiptLineId}",
                ),
            )
        }

        val regularSubtotal = portions.fold(BigInteger.ZERO) { subtotal, priced ->
            subtotal + priced.unitPrice.minorUnits.toBigInteger() * priced.portion.quantity.toBigInteger()
        }
        val totalQuantity = portions.fold(BigInteger.ZERO) { quantity, priced ->
            quantity + priced.portion.quantity.toBigInteger()
        }
        return ContextResult.Valid(
            PricingContext(
                portions = portions,
                currencyCode = currencyCode,
                regularSubtotal = regularSubtotal,
                totalQuantity = totalQuantity,
            ),
        )
    }

    private fun currencyMismatch(
        context: PricingContext,
        amount: Money,
        source: String,
    ): PromotionPricingResult.Invalid? = if (amount.currencyCode == context.currencyCode) {
        null
    } else {
        PromotionPricingResult.Invalid(
            PromotionPricingInvalidReason.CurrencyMismatch(
                expectedCurrencyCode = context.currencyCode,
                actualCurrencyCode = amount.currencyCode,
                source = source,
            ),
        )
    }

    private fun incompleteGroup(
        selectedQuantity: BigInteger,
        groupSize: BigInteger,
    ) = PromotionPricingResult.Indeterminate(
        PromotionPricingIndeterminateReason.IncompletePromotionGroup(
            selectedQuantity = selectedQuantity,
            requiredGroupSize = groupSize,
        ),
    )

    private fun notEligibleBecausePriceDoesNotDrop(
        context: PricingContext,
        offerSubtotal: BigInteger,
    ) = PromotionPricingResult.NotEligible(
        PromotionPricingNotEligibleReason.OfferDoesNotReducePrice(
            regularSubtotalMinorUnits = context.regularSubtotal,
            offerSubtotalMinorUnits = offerSubtotal,
            currencyCode = context.currencyCode,
        ),
    )

    private fun priced(
        context: PricingContext,
        discount: BigInteger,
    ): PromotionPricingResult {
        if (discount > context.regularSubtotal) {
            return PromotionPricingResult.Invalid(
                PromotionPricingInvalidReason.DiscountExceedsSubtotal(
                    discountMinorUnits = discount,
                    subtotalMinorUnits = context.regularSubtotal,
                ),
            )
        }
        if (discount == BigInteger.ZERO) {
            return notEligibleBecausePriceDoesNotDrop(context, context.regularSubtotal)
        }
        val payable = context.regularSubtotal - discount
        val amounts = listOf(context.regularSubtotal, discount, payable)
        amounts.firstOrNull { it > LongMaxValue }?.let { outOfRange ->
            return PromotionPricingResult.Invalid(
                PromotionPricingInvalidReason.AmountOutOfRange(outOfRange),
            )
        }
        return PromotionPricingResult.Priced(
            regularSubtotal = Money(context.regularSubtotal.longValueExact(), context.currencyCode),
            discount = Money(discount.longValueExact(), context.currencyCode),
            payableSubtotal = Money(payable.longValueExact(), context.currencyCode),
            ruleVersion = ruleVersion,
        )
    }

    private data class PricingContext(
        val portions: List<PricedLinePortion>,
        val currencyCode: String,
        val regularSubtotal: BigInteger,
        val totalQuantity: BigInteger,
    )

    private sealed interface ContextResult {
        data class Valid(val context: PricingContext) : ContextResult
        data class Invalid(val reason: PromotionPricingInvalidReason) : ContextResult
    }

    private companion object {
        val BasisPointScale: BigInteger = 10_000.toBigInteger()
        val LongMaxValue: BigInteger = Long.MAX_VALUE.toBigInteger()
    }
}
