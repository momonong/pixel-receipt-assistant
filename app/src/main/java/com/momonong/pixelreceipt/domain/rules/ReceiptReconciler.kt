package com.momonong.pixelreceipt.domain.rules

import com.momonong.pixelreceipt.domain.model.AdjustmentDirection
import com.momonong.pixelreceipt.domain.model.Fact
import com.momonong.pixelreceipt.domain.model.Money
import com.momonong.pixelreceipt.domain.model.PromotionApplicationStatus
import com.momonong.pixelreceipt.domain.model.ReceiptAssemblyStatus
import com.momonong.pixelreceipt.domain.model.ReceiptDraft
import java.math.BigInteger

/** Reconciles printed line amounts and separately printed adjustments against the receipt total. */
class ReceiptReconciler(
    roundingToleranceMinorUnits: Long = 1,
    private val validator: ReceiptValidator = ReceiptValidator(),
) {
    private val tolerance = roundingToleranceMinorUnits.toBigInteger()

    init {
        require(roundingToleranceMinorUnits >= 0) {
            "Rounding tolerance cannot be negative."
        }
    }

    fun reconcile(draft: ReceiptDraft): ReceiptReconciliationResult {
        val validationIssues = validator.validate(draft)
        if (validationIssues.isNotEmpty()) {
            return ReceiptReconciliationResult.Invalid(validationIssues)
        }

        val unresolved = buildList {
            if (!draft.assemblyStatus.isComplete) {
                add(ReconciliationGap.IncompleteReceipt(draft.assemblyStatus))
            }
            if (draft.merchant !is Fact.Known) add(ReconciliationGap.Merchant)
            if (draft.total !is Fact.Known) add(ReconciliationGap.ReceiptTotal)
            draft.items.forEach { line ->
                if (line.rawName !is Fact.Known) {
                    add(ReconciliationGap.LineName(line.id))
                }
                if (line.quantity !is Fact.Known) {
                    add(ReconciliationGap.LineQuantity(line.id))
                }
                if (line.printedTotal !is Fact.Known) {
                    add(ReconciliationGap.LineAmount(line.id))
                }
            }
            draft.adjustments.forEach { adjustment ->
                if (adjustment.amount !is Fact.Known) {
                    add(ReconciliationGap.AdjustmentAmount(adjustment.id))
                }
                if (adjustment.scope !is Fact.Known) {
                    add(ReconciliationGap.AdjustmentScope(adjustment.id))
                }
            }
            draft.promotionApplications.forEach { application ->
                if (application.status == PromotionApplicationStatus.Proposed) {
                    add(ReconciliationGap.PromotionApplication(application.id))
                }
            }
        }
        if (unresolved.isNotEmpty()) {
            return ReceiptReconciliationResult.Indeterminate(unresolved)
        }

        val total = (draft.total as Fact.Known<Money>).value
        var computed = draft.items.fold(BigInteger.ZERO) { subtotal, line ->
            subtotal + (line.printedTotal as Fact.Known<Money>).value.minorUnits.toBigInteger()
        }
        draft.adjustments.forEach { adjustment ->
            val amount = (adjustment.amount as Fact.Known<Money>).value.minorUnits.toBigInteger()
            computed = when (adjustment.direction) {
                AdjustmentDirection.Add -> computed + amount
                AdjustmentDirection.Subtract -> computed - amount
            }
        }

        val expected = total.minorUnits.toBigInteger()
        val difference = computed - expected
        return when {
            computed < BigInteger.ZERO -> ReceiptReconciliationResult.Unbalanced(
                computedMinorUnits = computed,
                receiptMinorUnits = expected,
                differenceMinorUnits = difference,
                currencyCode = total.currencyCode,
            )
            difference == BigInteger.ZERO -> ReceiptReconciliationResult.Balanced(
                computedMinorUnits = computed,
                currencyCode = total.currencyCode,
            )
            difference.abs() <= tolerance -> ReceiptReconciliationResult.WithinTolerance(
                computedMinorUnits = computed,
                receiptMinorUnits = expected,
                differenceMinorUnits = difference,
                currencyCode = total.currencyCode,
            )
            else -> ReceiptReconciliationResult.Unbalanced(
                computedMinorUnits = computed,
                receiptMinorUnits = expected,
                differenceMinorUnits = difference,
                currencyCode = total.currencyCode,
            )
        }
    }
}

private val ReceiptAssemblyStatus.isComplete: Boolean
    get() = this == ReceiptAssemblyStatus.CompleteByRule ||
        this == ReceiptAssemblyStatus.CompleteByUser

sealed interface ReconciliationGap {
    data class IncompleteReceipt(val status: ReceiptAssemblyStatus) : ReconciliationGap
    data object Merchant : ReconciliationGap
    data object ReceiptTotal : ReconciliationGap
    data class LineName(val lineItemId: String) : ReconciliationGap
    data class LineQuantity(val lineItemId: String) : ReconciliationGap
    data class LineAmount(val lineItemId: String) : ReconciliationGap
    data class AdjustmentAmount(val adjustmentId: String) : ReconciliationGap
    data class AdjustmentScope(val adjustmentId: String) : ReconciliationGap
    data class PromotionApplication(val applicationId: String) : ReconciliationGap
}

sealed interface ReceiptReconciliationResult {
    data class Balanced(
        val computedMinorUnits: BigInteger,
        val currencyCode: String,
    ) : ReceiptReconciliationResult

    data class WithinTolerance(
        val computedMinorUnits: BigInteger,
        val receiptMinorUnits: BigInteger,
        val differenceMinorUnits: BigInteger,
        val currencyCode: String,
    ) : ReceiptReconciliationResult

    data class Unbalanced(
        val computedMinorUnits: BigInteger,
        val receiptMinorUnits: BigInteger,
        val differenceMinorUnits: BigInteger,
        val currencyCode: String,
    ) : ReceiptReconciliationResult

    data class Indeterminate(
        val gaps: List<ReconciliationGap>,
    ) : ReceiptReconciliationResult

    data class Invalid(
        val issues: List<ReceiptValidationIssue>,
    ) : ReceiptReconciliationResult
}
