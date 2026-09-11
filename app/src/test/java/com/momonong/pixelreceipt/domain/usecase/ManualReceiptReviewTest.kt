package com.momonong.pixelreceipt.domain.usecase

import com.momonong.pixelreceipt.domain.model.*
import com.momonong.pixelreceipt.domain.port.*
import com.momonong.pixelreceipt.domain.rules.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ManualReceiptReviewTest {
    private val base = ReceiptDraft("manual", stage = ReceiptStage.NeedsReview, evidenceAssetIds = setOf("photo"))
    private fun complete(amount: String = "100") = ReviewInput("商家", "2026-09-08", "100", true,
        setOf("photo"), listOf(ReviewLineInput("line", "商品", "2", amount, setOf("photo"))))

    @Test fun capturedCanEnterReviewWithoutInventingAnalysisAndCannotConfirmDirectly() = runBlocking {
        val captured = base.copy(stage = ReceiptStage.Captured)
        val repo = ReviewTestRepository(captured)
        assertTrue(TransitionReceiptStage(repo)(captured, ReceiptStage.Confirmed) is TransitionReceiptStageResult.InvalidTransition)
        val opened = TransitionReceiptStage(repo)(captured, ReceiptStage.NeedsReview) as TransitionReceiptStageResult.Updated
        assertEquals(1L, opened.draft.revision)
        assertEquals(captured.merchant, opened.draft.merchant)
        assertEquals(captured.evidenceAssetIds, opened.draft.evidenceAssetIds)
        assertFalse(ReceiptStage.Analyzing.canTransitionTo(ReceiptStage.Confirmed))
    }

    @Test fun manualSaveRecordsFactsDateEvidenceAndPreservesUnknownOriginal() = runBlocking {
        val repo = ReviewTestRepository(base)
        val saved = (ManualReceiptReview(repo).save(base, complete(), 123) as ReviewSaveResult.Saved).draft
        assertEquals("2026-09-08", (saved.transactionDate as Fact.Known).value)
        assertEquals(FactProvenance.UserConfirmed(123, setOf(EvidenceReference("photo"))), (saved.items.single().quantity as Fact.Known).provenance)
        assertTrue(saved.items.single().referenceOriginalTotal is Fact.Unknown)
        assertEquals(2, saved.evidenceLinks.size)
        assertTrue(saved.evidenceLinks.all { it.status == EvidenceLinkStatus.Confirmed })
        assertTrue(ReceiptReconciler().reconcile(saved) is ReceiptReconciliationResult.Balanced)
    }

    @Test fun moneyAndQuantityRejectFractionsSignsSeparatorsOverflowAndZeroQuantity() {
        listOf("-1", "+1", "1.0", "1,000", "1e2", "9223372036854775808").forEach {
            assertThrows(IllegalArgumentException::class.java) { ReviewParsing.amount(it) }
        }
        listOf("0", "-1", "2.5", "2147483648").forEach {
            assertThrows(IllegalArgumentException::class.java) { ReviewParsing.quantity(it) }
        }
        assertEquals(Long.MAX_VALUE, ReviewParsing.amount(Long.MAX_VALUE.toString()))
        assertEquals(0L, ReviewParsing.amount("0"))
    }

    @Test fun strictCalendarParsingRejectsImpossibleDates() {
        listOf("2026-02-29", "2026-13-01", "26-09-08", "2026-9-8").forEach {
            assertThrows(IllegalArgumentException::class.java) { ReviewParsing.date(it) }
        }
        assertEquals("2024-02-29", ReviewParsing.date("2024-02-29"))
    }

    @Test fun invalidInputCannotSaveAndReportsField() = runBlocking {
        val repo = ReviewTestRepository(base)
        val result = ManualReceiptReview(repo).save(base, complete("1.5").copy(date = "2026-02-30"), 1) as ReviewSaveResult.Rejected
        assertTrue(result.reasons.any { it.contains("品項 1 金額") })
        assertTrue(result.reasons.any { it.contains("交易日期") })
        assertSame(base, repo.current)
    }

    @Test fun blankFieldsSaveAsUnknownButExistingGateBlocksMissingRequiredFacts() = runBlocking {
        val repo = ReviewTestRepository(base)
        val saved = (ManualReceiptReview(repo).save(base, complete().copy(merchant = "", total = ""), 1) as ReviewSaveResult.Saved).draft
        assertTrue(saved.merchant is Fact.Unknown)
        assertTrue(saved.total is Fact.Unknown)
        assertTrue(TransitionReceiptStage(repo)(saved, ReceiptStage.Confirmed) is TransitionReceiptStageResult.ConfirmationBlocked)
    }

    @Test fun confirmationKeepsBothDirectionsOfToleranceAndIsReadOnly() = runBlocking {
        for (amount in listOf("99", "100", "101")) {
            val repo = ReviewTestRepository(base)
            val useCase = ManualReceiptReview(repo)
            val saved = (useCase.save(base, complete(amount).withSelfExpenses(base), 1) as ReviewSaveResult.Saved).draft
            val result = ReceiptReconciler().reconcile(saved)
            if (amount == "100") assertTrue(result is ReceiptReconciliationResult.Balanced)
            else assertEquals((amount.toLong() - 100).toBigInteger(), (result as ReceiptReconciliationResult.WithinTolerance).differenceMinorUnits)
            val confirmed = (TransitionReceiptStage(repo)(saved, ReceiptStage.Confirmed) as TransitionReceiptStageResult.Updated).draft
            assertTrue(useCase.save(confirmed, complete(), 2) is ReviewSaveResult.Rejected)
            assertTrue(TransitionReceiptStage(repo)(confirmed, ReceiptStage.Confirmed) is TransitionReceiptStageResult.InvalidTransition)
            assertEquals(TransitionReceiptStageResult.Conflict, TransitionReceiptStage(repo)(saved, ReceiptStage.Confirmed))
            assertEquals(confirmed, repo.current)
        }
    }

    @Test fun exceedingToleranceBlocksConfirmationWithoutWrite() = runBlocking {
        val repo = ReviewTestRepository(base)
        val saved = (ManualReceiptReview(repo).save(base, complete("98"), 1) as ReviewSaveResult.Saved).draft
        assertTrue(TransitionReceiptStage(repo)(saved, ReceiptStage.Confirmed) is TransitionReceiptStageResult.ConfirmationBlocked)
        assertSame(saved, repo.current)
    }

    @Test fun explicitScopesAndDirectionsAffectLedgerExactlyOnce() {
        val form = complete().copy(total = "95", adjustments = listOf(
            ReviewAdjustmentInput("discount", "10", scope = "lines", portions = mapOf("line" to "2")),
            ReviewAdjustmentInput("fee", "5", AdjustmentDirection.Add, scope = "order"),
        ))
        val evaluated = ManualReceiptReview(ReviewTestRepository(base)).evaluate(base, form, 1)
        assertEquals(emptyList<String>(), evaluated.errors)
        assertTrue(ReceiptReconciler().reconcile(evaluated.draft!!) is ReceiptReconciliationResult.Balanced)
        assertEquals(ReceiptAdjustmentScope.Line(LinePortion("line", 2)), (evaluated.draft.adjustments.first().scope as Fact.Known).value)
        assertEquals(100L, (evaluated.draft.items.single().printedTotal as Fact.Known).value.minorUnits)
    }

    @Test fun lineSetPreservesExplicitPortionsAndDoesNotMultiplyAdjustment() {
        val form = complete().copy(lines = complete().lines + ReviewLineInput("second", "其他商品", "1", "30"),
            total = "120", adjustments = listOf(ReviewAdjustmentInput("discount", "10", scope = "lines", portions = mapOf("line" to "1", "second" to "1"))))
        val draft = ManualReceiptReview(ReviewTestRepository(base)).evaluate(base, form, 1).draft!!
        assertTrue((draft.adjustments.single().scope as Fact.Known).value is ReceiptAdjustmentScope.LineSet)
        assertTrue(ReceiptReconciler().reconcile(draft) is ReceiptReconciliationResult.Balanced)
    }

    @Test fun unknownScopeAndExcessivePortionsBlockConfirmation() {
        val useCase = ManualReceiptReview(ReviewTestRepository(base))
        val unknown = useCase.evaluate(base, complete().copy(adjustments = listOf(ReviewAdjustmentInput("a", "0"))), 1).draft!!
        assertTrue(ReceiptReconciler().reconcile(unknown) is ReceiptReconciliationResult.Indeterminate)
        val excessive = useCase.evaluate(base, complete().copy(adjustments = listOf(ReviewAdjustmentInput("a", "0", scope = "lines", portions = mapOf("line" to "3")))), 1).draft!!
        assertTrue(ReceiptValidator().validate(excessive).any { it is ReceiptValidationIssue.PortionExceedsPurchasedQuantity })
    }

    @Test fun changingMerchantPreservesOtherFactStatesProvenanceAndLinks() {
        val useCase = ManualReceiptReview(ReviewTestRepository(base))
        val populated = useCase.evaluate(base, complete(), 123).draft!!
        val original = Fact.Conflicting(listOf(Fact.Known(Money(110), FactProvenance.UserConfirmed(1)), Fact.Known(Money(120), FactProvenance.UserConfirmed(2))))
        val existing = populated.copy(items = listOf(populated.items.single().copy(referenceOriginalTotal = original)))
        val edited = useCase.evaluate(existing, ReviewInput.from(existing).copy(merchant = "新商家"), 456).draft!!
        assertSame(original, edited.items.single().referenceOriginalTotal)
        assertEquals(existing.items, edited.items)
        assertEquals(existing.evidenceLinks, edited.evidenceLinks)
        assertEquals(existing.transactionDate, edited.transactionDate)
    }

    @Test fun deletingReferencedLineRequiresRemovingItsAdjustmentFirst() {
        val useCase = ManualReceiptReview(ReviewTestRepository(base))
        val existing = useCase.evaluate(base, complete().copy(adjustments = listOf(ReviewAdjustmentInput("a", "0", scope = "lines", portions = mapOf("line" to "1")))), 1).draft!!
        val result = useCase.evaluate(existing, ReviewInput.from(existing).copy(lines = emptyList()), 2)
        assertNull(result.draft)
        assertTrue(result.errors.any { it.contains("仍引用") })
        val removed = useCase.evaluate(existing, ReviewInput.from(existing).copy(lines = emptyList(), adjustments = emptyList()), 2).draft!!
        assertTrue(removed.evidenceLinks.none { it.target is EvidenceLinkTarget.ReceiptLine })
    }

    @Test fun staleSaveCannotOverwriteNewerFacts() = runBlocking {
        val repo = ReviewTestRepository(base.copy(revision = 1))
        assertEquals(ReviewSaveResult.Conflict, ManualReceiptReview(repo).save(base, complete(), 1))
        assertEquals(1L, repo.current?.revision)
        assertTrue(repo.current?.merchant is Fact.Unknown)
    }

    @Test fun unknownDateRemainsUnknownAndDoesNotChangeExistingGate() {
        val draft = ManualReceiptReview(ReviewTestRepository(base)).evaluate(base, complete().copy(date = ""), 1).draft!!
        assertTrue(draft.transactionDate is Fact.Unknown)
        assertTrue(ReceiptReconciler().reconcile(draft) is ReceiptReconciliationResult.Balanced)
    }
}

class ReviewTestRepository(var current: ReceiptDraft?) : ReceiptRepository {
    override fun observeDraft(id: String) = flowOf(current?.takeIf { it.id == id })
    override suspend fun createDraft(draft: ReceiptDraft): DraftWriteResult {
        if (current != null) return DraftWriteResult.Conflict
        current = draft
        return DraftWriteResult.Written(0)
    }
    override suspend fun compareAndSetDraft(draft: ReceiptDraft, expectedRevision: Long): DraftWriteResult {
        val old = current ?: return DraftWriteResult.NotFound
        if (old.id != draft.id || old.revision != expectedRevision) return DraftWriteResult.Conflict
        current = draft
        return DraftWriteResult.Written(draft.revision)
    }
}
