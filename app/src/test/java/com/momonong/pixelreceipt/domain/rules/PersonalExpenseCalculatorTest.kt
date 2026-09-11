package com.momonong.pixelreceipt.domain.rules

import com.momonong.pixelreceipt.domain.model.*
import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger

class PersonalExpenseCalculatorTest {
    private fun <T : Any> known(value: T) = Fact.Known(value, FactProvenance.UserConfirmed(1))
    private fun line(id: String, quantity: Int, amount: Long) = ReceiptLineDraft(id, rawName = known(id), quantity = known(quantity), printedTotal = known(Money(amount)))
    private fun draft(vararg lines: ReceiptLineDraft, total: Long = 100) = ReceiptDraft("r", merchant = known("店"), items = lines.toList(),
        total = known(Money(total)), assemblyStatus = ReceiptAssemblyStatus.CompleteByUser, stage = ReceiptStage.NeedsReview)
    private fun whole(draft: ReceiptDraft, vararg purposes: ExpensePurpose) = draft.copy(personalExpenses = draft.items.zip(purposes).map { (line, purpose) ->
        LineExpenseDecision(line.id, ExpenseSplitMethod.WholeLine, purpose, basisKey = PersonalExpenseCalculator.lineBasis(draft, line.id), confirmedAtEpochMillis = 1)
    })
    private fun split(draft: ReceiptDraft, method: ExpenseSplitMethod = ExpenseSplitMethod.ByQuantity,
        quantities: Map<ExpensePurpose, Int> = mapOf(ExpensePurpose.Self to 1, ExpensePurpose.Advance to 2),
        amounts: Map<ExpensePurpose, Long> = emptyMap()) = draft.copy(personalExpenses = listOf(LineExpenseDecision(draft.items.first().id,
        method, quantities = quantities, amounts = amounts, basisKey = PersonalExpenseCalculator.lineBasis(draft, draft.items.first().id), confirmedAtEpochMillis = 1)))
    private fun discount(draft: ReceiptDraft, amount: Long, scope: Fact<ReceiptAdjustmentScope> = known(ReceiptAdjustmentScope.Order)) = draft.copy(
        adjustments = listOf(ReceiptAdjustment("discount", ReceiptAdjustmentKind.Other, AdjustmentDirection.Subtract, known(Money(amount)), scope)))
    private fun approveAdjustment(draft: ReceiptDraft, method: ExpenseAdjustmentMethod = ExpenseAdjustmentMethod.ProportionalAmount,
        amounts: Map<ExpensePurpose, Long> = emptyMap()) = draft.copy(expenseAdjustments = listOf(ExpenseAdjustmentDecision("discount", method,
        amounts, PersonalExpenseCalculator.adjustmentBasis(draft), 1)))

    @Test fun selfAndGiftArePersonalAdvanceRemainsInFullReceipt() {
        val d = whole(draft(line("meal", 2, 50), line("gift", 1, 30), line("friend", 1, 20)), ExpensePurpose.Self, ExpensePurpose.Gift, ExpensePurpose.Advance)
        val result = PersonalExpenseCalculator.calculate(d)
        assertTrue(result.ready); assertEquals(80L, result.personalMinor); assertEquals(30L, result.giftMinor); assertEquals(20L, result.advanceMinor)
        assertEquals(100L, result.accountedMinor)
        assertTrue(ReceiptReconciler().reconcile(d) is ReceiptReconciliationResult.Balanced)
        assertEquals(3, d.items.size)
    }

    @Test fun partialQuantityUsesLargestRemainderWithoutMultiplyingLineTotal() {
        val result = PersonalExpenseCalculator.calculate(split(draft(line("tea", 3, 100))))
        assertTrue(result.ready); assertEquals(33L, result.selfMinor); assertEquals(67L, result.advanceMinor); assertEquals(100L, result.accountedMinor)
        val tie = PersonalExpenseCalculator.calculate(split(draft(line("tea", 2, 1), total = 1), quantities = mapOf(ExpensePurpose.Self to 1, ExpensePurpose.Advance to 1)))
        assertEquals(1L, tie.selfMinor); assertEquals(0L, tie.advanceMinor)
    }

    @Test fun unassignedUnderallocatedOverallocatedAndStaleNeverYieldAnApparentTotal() {
        val d = draft(line("tea", 3, 100))
        for (candidate in listOf(d, split(d, quantities = mapOf(ExpensePurpose.Self to 1)),
            split(d, quantities = mapOf(ExpensePurpose.Self to 4)),
            split(d).copy(items = listOf(line("tea", 4, 100))), split(d).copy(items = listOf(line("tea", 3, 101))))) {
            val result = PersonalExpenseCalculator.calculate(candidate)
            assertFalse(result.ready); assertNull(result.personalMinor); assertTrue(result.pending.isNotEmpty())
        }
    }

    @Test fun explicitNonUniformCostsArePreservedAndMustConserveAmountsAndQuantities() {
        val d = draft(line("buyOneGetOne", 2, 100))
        val quantities = mapOf(ExpensePurpose.Self to 1, ExpensePurpose.Gift to 1)
        val amounts = mapOf(ExpensePurpose.Self to 100L, ExpensePurpose.Gift to 0L, ExpensePurpose.Advance to 0L)
        val valid = split(d, ExpenseSplitMethod.ExplicitAmounts, quantities, amounts)
        assertEquals(100L, PersonalExpenseCalculator.calculate(valid).selfMinor)
        assertEquals(0L, PersonalExpenseCalculator.calculate(valid).giftMinor)
        assertFalse(PersonalExpenseCalculator.calculate(split(d, ExpenseSplitMethod.ExplicitAmounts, quantities, amounts - ExpensePurpose.Advance)).ready)
        assertFalse(PersonalExpenseCalculator.calculate(split(d, ExpenseSplitMethod.ExplicitAmounts, quantities, amounts + (ExpensePurpose.Gift to 1L))).ready)
        assertFalse(PersonalExpenseCalculator.calculate(split(d, ExpenseSplitMethod.ExplicitAmounts, quantities,
            amounts + (ExpensePurpose.Self to 90L) + (ExpensePurpose.Advance to 10L))).ready)
    }

    @Test fun orderDiscountRequiresUserChoiceUsesAmountsNotEqualLinesAndKeepsRemainder() {
        val d = whole(discount(draft(line("own", 1, 80), line("friend", 1, 20), total = 97), 3), ExpensePurpose.Self, ExpensePurpose.Advance)
        assertFalse(PersonalExpenseCalculator.calculate(d).ready)
        val result = PersonalExpenseCalculator.calculate(approveAdjustment(d))
        assertEquals(78L, result.personalMinor); assertEquals(19L, result.advanceMinor); assertEquals(97L, result.accountedMinor)
        assertEquals(BigInteger.ZERO, result.differenceMinor)
        // Changing who bears a line invalidates the adjustment approval, even if receipt money is identical.
        assertFalse(PersonalExpenseCalculator.calculate(whole(approveAdjustment(d), ExpensePurpose.Advance, ExpensePurpose.Self)).ready)
    }

    @Test fun unknownAndPartialScopesStayPendingUntilExplicitAllocation() {
        val d = draft(line("tea", 3, 100), total = 90)
        val unknown = split(discount(d, 10, Fact.Unknown(UnknownFactReason.Ambiguous)))
        assertFalse(PersonalExpenseCalculator.calculate(approveAdjustment(unknown)).ready)
        val partial = split(discount(d, 10, known(ReceiptAdjustmentScope.Line(LinePortion("tea", 1)))))
        assertFalse(PersonalExpenseCalculator.calculate(approveAdjustment(partial)).ready)
        val explicit = approveAdjustment(partial, ExpenseAdjustmentMethod.ExplicitAmounts,
            mapOf(ExpensePurpose.Self to 10L, ExpensePurpose.Advance to 0L, ExpensePurpose.Gift to 0L))
        assertEquals(23L, PersonalExpenseCalculator.calculate(explicit).personalMinor)
        assertEquals(67L, PersonalExpenseCalculator.calculate(explicit).advanceMinor)
    }

    @Test fun bothToleranceDirectionsRemainVisibleAndNeverChangeAnyonesCost() {
        for (payment in listOf(99L, 101L)) {
            val d = whole(draft(line("own", 2, 100), total = payment), ExpensePurpose.Self)
            val result = PersonalExpenseCalculator.calculate(d)
            assertEquals(100L, result.personalMinor)
            assertEquals(BigInteger.valueOf(100 - payment), result.differenceMinor)
            assertTrue(ReceiptReconciler().reconcile(d) is ReceiptReconciliationResult.WithinTolerance)
        }
    }

    @Test fun maxLongProductsUseExactIntermediatesAndOverflowRemainsPending() {
        val d = split(draft(line("max", 3, Long.MAX_VALUE), total = Long.MAX_VALUE))
        val result = PersonalExpenseCalculator.calculate(d)
        assertTrue(result.ready); assertEquals(Long.MAX_VALUE, Math.addExact(result.selfMinor!!, result.advanceMinor!!))
        val overflow = whole(draft(line("a", 1, Long.MAX_VALUE), line("b", 1, 1), total = Long.MAX_VALUE), ExpensePurpose.Self, ExpensePurpose.Self)
        assertFalse(PersonalExpenseCalculator.calculate(overflow).ready)
    }

    @Test fun zeroBasisFeeNeedsExplicitDecisionAndNegativeBurdenIsRejected() {
        val zero = draft(line("free", 1, 0), total = 5).copy(adjustments = listOf(ReceiptAdjustment("discount", ReceiptAdjustmentKind.Fee,
            AdjustmentDirection.Add, known(Money(5)), known(ReceiptAdjustmentScope.Order))))
        val owned = whole(zero, ExpensePurpose.Self)
        assertFalse(PersonalExpenseCalculator.calculate(approveAdjustment(owned)).ready)
        val explicit = approveAdjustment(owned, ExpenseAdjustmentMethod.ExplicitAmounts,
            mapOf(ExpensePurpose.Self to 5L, ExpensePurpose.Advance to 0L, ExpensePurpose.Gift to 0L))
        assertEquals(5L, PersonalExpenseCalculator.calculate(explicit).personalMinor)
        val negative = whole(discount(draft(line("self", 1, 1), line("friend", 1, 99), total = 90), 10), ExpensePurpose.Self, ExpensePurpose.Advance)
        assertFalse(PersonalExpenseCalculator.calculate(approveAdjustment(negative, ExpenseAdjustmentMethod.ExplicitAmounts,
            mapOf(ExpensePurpose.Self to 10L, ExpensePurpose.Advance to 0L, ExpensePurpose.Gift to 0L))).ready)
    }
}
