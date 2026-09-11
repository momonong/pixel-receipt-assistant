package com.momonong.pixelreceipt.domain.usecase

import com.momonong.pixelreceipt.domain.model.*
import com.momonong.pixelreceipt.domain.rules.*
import com.momonong.pixelreceipt.data.local.DraftCodec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PersonalExpenseReviewTest {
    private val base = ReceiptDraft("r", stage = ReceiptStage.NeedsReview)
    private val form = ReviewInput("商店", total = "100", complete = true, lines = listOf(ReviewLineInput("tea", "茶", "3", "100")))

    @Test fun balancedReceiptCannotConfirmWithoutOwnershipAndPendingDraftCanSave() = runBlocking {
        val repo = ReviewTestRepository(base)
        val review = ManualReceiptReview(repo)
        val saved = (review.save(base, form, 1) as ReviewSaveResult.Saved).draft
        assertTrue(ReceiptReconciler().reconcile(saved) is ReceiptReconciliationResult.Balanced)
        assertTrue(TransitionReceiptStage(repo)(saved, ReceiptStage.Confirmed) is TransitionReceiptStageResult.ExpenseBlocked)
        assertSame(saved, repo.current)
        val pending = ReviewInput.from(saved).copy(lines = listOf(form.lines.single().copy(expense = ExpenseInput(
            quantities = mapOf(ExpensePurpose.Self to "1")))))
        val stored = (review.save(saved, pending, 2) as ReviewSaveResult.Saved).draft
        assertEquals(1, stored.personalExpenses.single().quantities[ExpensePurpose.Self])
        assertFalse(PersonalExpenseCalculator.calculate(stored).ready)
    }

    @Test fun partialAssignmentRoundTripsAndStaleCasCannotOverwriteIt() = runBlocking {
        val repo = ReviewTestRepository(base)
        val review = ManualReceiptReview(repo)
        val evaluated = review.evaluate(base, form, 1).draft!!
        val input = form.copy(lines = listOf(form.lines.single().copy(expense = ExpenseInput(
            quantities = mapOf(ExpensePurpose.Self to "1", ExpensePurpose.Advance to "2"),
            basisKey = PersonalExpenseCalculator.lineBasis(evaluated, "tea"), confirmedAtEpochMillis = 1))))
        val saved = (review.save(base, input, 1) as ReviewSaveResult.Saved).draft
        val restored = DraftCodec().decode(DraftCodec().encode(saved))
        assertEquals(saved, restored)
        assertEquals(33L, PersonalExpenseCalculator.calculate(restored).personalMinor)
        assertEquals(ReviewSaveResult.Conflict, review.save(base, form.withSelfExpenses(base), 2))
        assertEquals(saved, repo.current)
        val confirmed = (TransitionReceiptStage(repo)(restored, ReceiptStage.Confirmed) as TransitionReceiptStageResult.Updated).draft
        assertTrue(review.save(confirmed, form, 3) is ReviewSaveResult.Rejected)
    }

    @Test fun editsKeepDecisionsButInvalidateFinancialApprovalAndRequireExplicitReconfirmation() {
        val review = ManualReceiptReview(ReviewTestRepository(base))
        val assigned = review.evaluate(base, form.withSelfExpenses(base), 1).draft!!
        for (input in listOf(ReviewInput.from(assigned).copy(lines = listOf(ReviewInput.from(assigned).lines.single().copy(quantity = "4"))),
            ReviewInput.from(assigned).copy(lines = listOf(ReviewInput.from(assigned).lines.single().copy(amount = "101"))),
            ReviewInput.from(assigned).copy(adjustments = listOf(ReviewAdjustmentInput("coupon", "1", scope = "order"))))) {
            val changed = review.evaluate(assigned, input, 2).draft!!
            assertEquals(assigned.personalExpenses, changed.personalExpenses)
            assertFalse(PersonalExpenseCalculator.calculate(changed).ready)
            val rechecked = review.evaluate(changed, ReviewInput.from(changed).withSelfExpenses(changed), 3).draft!!
            assertTrue(PersonalExpenseCalculator.calculate(rechecked).ready)
        }
    }
}
