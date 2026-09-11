package com.momonong.pixelreceipt.domain.usecase

import com.momonong.pixelreceipt.domain.model.ReceiptDraft
import com.momonong.pixelreceipt.domain.model.ReceiptStage
import com.momonong.pixelreceipt.domain.port.DraftWriteResult
import com.momonong.pixelreceipt.domain.port.ReceiptRepository
import com.momonong.pixelreceipt.domain.rules.ReceiptReconciler
import com.momonong.pixelreceipt.domain.rules.ReceiptReconciliationResult
import com.momonong.pixelreceipt.domain.rules.PersonalExpenseCalculator

/** Owns workflow legality while the repository provides atomic compare-and-set persistence. */
class TransitionReceiptStage(
    private val repository: ReceiptRepository,
    private val reconciler: ReceiptReconciler = ReceiptReconciler(),
) {
    suspend operator fun invoke(
        current: ReceiptDraft,
        nextStage: ReceiptStage,
    ): TransitionReceiptStageResult {
        if (!current.stage.canTransitionTo(nextStage)) {
            return TransitionReceiptStageResult.InvalidTransition(
                from = current.stage,
                to = nextStage,
            )
        }

        if (nextStage == ReceiptStage.Confirmed) {
            val reconciliation = reconciler.reconcile(current)
            if (
                reconciliation !is ReceiptReconciliationResult.Balanced &&
                reconciliation !is ReceiptReconciliationResult.WithinTolerance
            ) {
                return TransitionReceiptStageResult.ConfirmationBlocked(reconciliation)
            }
            val expenses = PersonalExpenseCalculator.calculate(current)
            if (!expenses.ready) return TransitionReceiptStageResult.ExpenseBlocked(expenses.pending)
        }

        require(current.revision < Long.MAX_VALUE) { "Receipt revision is exhausted." }
        val updated = current.copy(
            stage = nextStage,
            revision = current.revision + 1,
        )

        val writeResult = repository.compareAndSetDraft(
            draft = updated,
            expectedRevision = current.revision,
        )
        return when (writeResult) {
            is DraftWriteResult.Written -> {
                check(writeResult.revision == updated.revision) {
                    "Repository returned an unexpected receipt revision."
                }
                TransitionReceiptStageResult.Updated(updated)
            }
            DraftWriteResult.Conflict -> TransitionReceiptStageResult.Conflict
            DraftWriteResult.NotFound -> TransitionReceiptStageResult.NotFound
        }
    }
}

sealed interface TransitionReceiptStageResult {
    data class ExpenseBlocked(val reasons: List<String>) : TransitionReceiptStageResult
    data class Updated(val draft: ReceiptDraft) : TransitionReceiptStageResult

    data class InvalidTransition(
        val from: ReceiptStage,
        val to: ReceiptStage,
    ) : TransitionReceiptStageResult

    data class ConfirmationBlocked(
        val reconciliation: ReceiptReconciliationResult,
    ) : TransitionReceiptStageResult

    data object Conflict : TransitionReceiptStageResult

    data object NotFound : TransitionReceiptStageResult
}
