package com.momonong.pixelreceipt.domain.usecase

import com.momonong.pixelreceipt.domain.model.*
import com.momonong.pixelreceipt.domain.rules.PersonalExpenseCalculator

/** Explicit test-user decisions for existing confirmation tests; never used by production. */
fun ReviewInput.withSelfExpenses(base: ReceiptDraft): ReviewInput {
    val useCase = ManualReceiptReview(ReviewTestRepository(base))
    val evaluated = requireNotNull(useCase.evaluate(base, this, 1).draft)
    val classified = copy(lines = lines.map { it.copy(expense = ExpenseInput(ExpenseSplitMethod.WholeLine, ExpensePurpose.Self,
        basisKey = PersonalExpenseCalculator.lineBasis(evaluated, it.id), confirmedAtEpochMillis = 1)) })
    val withLines = requireNotNull(useCase.evaluate(base, classified, 1).draft)
    return classified.copy(adjustments = adjustments.map { it.copy(expense = AdjustmentExpenseInput(
        basisKey = PersonalExpenseCalculator.adjustmentBasis(withLines), confirmedAtEpochMillis = 1)) })
}
