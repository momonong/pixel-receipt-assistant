package com.momonong.pixelreceipt.domain.model

/** Purpose and who bears the cost are independent of receipt-content verification. */
enum class ExpensePurpose { Self, Advance, Gift }
enum class ExpenseSplitMethod { WholeLine, ByQuantity, ExplicitAmounts }
enum class ExpenseAdjustmentMethod { ProportionalAmount, ExplicitAmounts }

data class LineExpenseDecision(
    val lineId: String,
    val method: ExpenseSplitMethod,
    val wholePurpose: ExpensePurpose? = null,
    val quantities: Map<ExpensePurpose, Int> = emptyMap(),
    /** Printed line amount, BEFORE separately printed adjustments. */
    val amounts: Map<ExpensePurpose, Long> = emptyMap(),
    val basisKey: String? = null,
    val confirmedAtEpochMillis: Long? = null,
)

data class ExpenseAdjustmentDecision(
    val adjustmentId: String,
    val method: ExpenseAdjustmentMethod,
    /** Unsigned shares; the receipt adjustment determines the sign. */
    val amounts: Map<ExpensePurpose, Long> = emptyMap(),
    val basisKey: String? = null,
    val confirmedAtEpochMillis: Long? = null,
)
