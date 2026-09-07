package com.momonong.pixelreceipt.domain.usecase

import com.momonong.pixelreceipt.domain.model.Fact
import com.momonong.pixelreceipt.domain.model.FactProvenance
import com.momonong.pixelreceipt.domain.model.Money
import com.momonong.pixelreceipt.domain.model.ReceiptAssemblyStatus
import com.momonong.pixelreceipt.domain.model.ReceiptDraft
import com.momonong.pixelreceipt.domain.model.ReceiptLineDraft
import com.momonong.pixelreceipt.domain.model.ReceiptStage
import com.momonong.pixelreceipt.domain.model.UnknownFactReason
import com.momonong.pixelreceipt.domain.port.DraftWriteResult
import com.momonong.pixelreceipt.domain.port.ReceiptRepository
import com.momonong.pixelreceipt.domain.rules.ReceiptReconciliationResult
import com.momonong.pixelreceipt.domain.rules.ReconciliationGap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TransitionReceiptStageTest {
    @Test
    fun `valid transition increments revision and persists with compare and set`() = runBlocking {
        val initial = draft(stage = ReceiptStage.Captured, revision = 4)
        val repository = FakeReceiptRepository(initial)

        val result = TransitionReceiptStage(repository)(initial, ReceiptStage.PendingAnalysis)

        assertTrue(result is TransitionReceiptStageResult.Updated)
        assertEquals(ReceiptStage.PendingAnalysis, repository.stored?.stage)
        assertEquals(5L, repository.stored?.revision)
    }

    @Test
    fun `invalid transition is rejected before persistence`() = runBlocking {
        val initial = draft(stage = ReceiptStage.Captured)
        val repository = FakeReceiptRepository(initial)

        val result = TransitionReceiptStage(repository)(initial, ReceiptStage.Exported)

        assertEquals(
            TransitionReceiptStageResult.InvalidTransition(
                from = ReceiptStage.Captured,
                to = ReceiptStage.Exported,
            ),
            result,
        )
        assertSame(initial, repository.stored)
    }

    @Test
    fun `stale revision returns conflict instead of losing a newer update`() = runBlocking {
        val stale = draft(stage = ReceiptStage.Captured, revision = 2)
        val repository = FakeReceiptRepository(
            draft(stage = ReceiptStage.Captured, revision = 3),
        )

        val result = TransitionReceiptStage(repository)(stale, ReceiptStage.PendingAnalysis)

        assertEquals(TransitionReceiptStageResult.Conflict, result)
        assertEquals(3L, repository.stored?.revision)
    }

    @Test
    fun `confirmation is blocked without writing when receipt is incomplete or unknown`() = runBlocking {
        val initial = ReceiptDraft(
            id = "receipt-1",
            merchant = known("全聯"),
            total = Fact.Unknown(UnknownFactReason.Unreadable),
            items = listOf(
                ReceiptLineDraft(
                    id = "line-1",
                    rawName = known("測試商品"),
                    quantity = known(1),
                    printedTotal = known(Money(100)),
                ),
            ),
            assemblyStatus = ReceiptAssemblyStatus.Collecting,
            stage = ReceiptStage.NeedsReview,
            revision = 4,
        )
        val repository = FakeReceiptRepository(initial)

        val result = TransitionReceiptStage(repository)(initial, ReceiptStage.Confirmed)

        assertEquals(
            TransitionReceiptStageResult.ConfirmationBlocked(
                ReceiptReconciliationResult.Indeterminate(
                    gaps = listOf(
                        ReconciliationGap.IncompleteReceipt(ReceiptAssemblyStatus.Collecting),
                        ReconciliationGap.ReceiptTotal,
                    ),
                ),
            ),
            result,
        )
        assertEquals(0, repository.compareAndSetCalls)
        assertSame(initial, repository.stored)
    }

    private fun draft(
        stage: ReceiptStage,
        revision: Long = 0,
    ) = ReceiptDraft(
        id = "receipt-1",
        merchant = known("全聯"),
        total = known(Money(0)),
        items = emptyList(),
        stage = stage,
        revision = revision,
    )

    private fun <T : Any> known(value: T): Fact.Known<T> = Fact.Known(
        value = value,
        provenance = FactProvenance.UserConfirmed(confirmedAtEpochMillis = 1),
    )
}

private class FakeReceiptRepository(
    var stored: ReceiptDraft?,
) : ReceiptRepository {
    var compareAndSetCalls: Int = 0

    override fun observeDraft(id: String): Flow<ReceiptDraft?> = flowOf(
        stored?.takeIf { it.id == id },
    )

    override suspend fun createDraft(draft: ReceiptDraft): DraftWriteResult {
        if (stored != null) return DraftWriteResult.Conflict
        stored = draft
        return DraftWriteResult.Written(draft.revision)
    }

    override suspend fun compareAndSetDraft(
        draft: ReceiptDraft,
        expectedRevision: Long,
    ): DraftWriteResult {
        compareAndSetCalls += 1
        val current = stored ?: return DraftWriteResult.NotFound
        if (current.id != draft.id || current.revision != expectedRevision) {
            return DraftWriteResult.Conflict
        }

        stored = draft
        return DraftWriteResult.Written(draft.revision)
    }
}
