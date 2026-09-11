package com.momonong.pixelreceipt.domain.usecase

import com.momonong.pixelreceipt.data.extraction.LocalReceiptParser
import com.momonong.pixelreceipt.data.local.DraftCodec
import com.momonong.pixelreceipt.domain.ai.*
import com.momonong.pixelreceipt.domain.model.*
import com.momonong.pixelreceipt.domain.port.*
import com.momonong.pixelreceipt.domain.rules.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Synthetic OCR observations exercise safety and mapping; they do not measure OCR accuracy. */
class ReceiptExtractionTest {
    private val parser = LocalReceiptParser()
    private val source = asset("image", "a")
    private val second = asset("page2", "b")
    private val base = ReceiptDraft("receipt", evidenceAssetIds = setOf(source.id, second.id))
    private val provenance = ExtractionProvenance("local-run", "mlkit", "16.0.1", "1", ExtractionRuntime.OnDevice, 10)
    private fun asset(id: String, digit: String) = EvidenceAsset(id, contentSha256 = digit.repeat(64), mimeType = "image/png",
        byteSize = 10, widthPx = 1000, heightPx = 1000, importSource = EvidenceImportSource.PhotoPicker, importedAtEpochMillis = 1)
    private fun page(vararg rows: String) = OcrPage(1000, 1000, rows.mapIndexed { i, text -> OcrLine(text, 1, i * 30, 900, i * 30 + 25) })
    private fun normal() = parser.parse(listOf(page("範例商店", "2026/09/09", "品名 數量 單價 金額", "牛奶 2 50 100", "總計 100")))
    private fun mapped(recognition: ReceiptRecognition = normal(), sources: List<EvidenceAsset> = listOf(source)) =
        MapReceiptRecognition().map(base, sources, recognition, provenance)
    private fun analyzer(block: suspend () -> AnalysisResult = { AnalysisResult.Success(AnalyzedReceipt(null, null, null, "TWD", emptyList(), normal())) }) = object : OnDeviceReceiptAnalyzer {
        override val descriptor = AiAnalyzerDescriptor("ocr", "local", "16.0.1", schemaVersion = "1", capabilities = AiCapability.entries.toSet())
        override suspend fun analyze(request: AiAnalysisRequest) = block()
    }

    @Test fun tableFieldsAutomaticallyPopulateWithoutMultiplyingLineAmount() {
        val draft = mapped()
        assertEquals("範例商店", draft.merchant.inputText())
        assertEquals("2026-09-09", draft.transactionDate.inputText())
        assertEquals("2", draft.items.single().quantity.inputText())
        assertEquals("100", draft.items.single().printedTotal.inputText())
        assertTrue(draft.items.single().referenceOriginalTotal is Fact.Unknown)
        assertTrue(ReceiptReconciler().reconcile(draft) is ReceiptReconciliationResult.Indeterminate)
        assertTrue(ReceiptReconciler().reconcile(draft.copy(assemblyStatus = ReceiptAssemblyStatus.CompleteByUser)) is ReceiptReconciliationResult.Balanced)
        assertEquals(ReceiptStage.NeedsReview, draft.stage)
    }

    @Test fun explicitQuantityUnitAndTotalAreSeparate() {
        val result = mapped(parser.parse(listOf(page("商店", "牛奶 2 x 50 100", "合計 100"))))
        assertEquals("2", result.items.single().quantity.inputText())
        assertEquals("100", result.items.single().printedTotal.inputText())
    }

    @Test fun singleUnlabelledPriceDoesNotInventQuantityOrLineTotal() {
        val result = mapped(parser.parse(listOf(page("商店", "牛奶 50", "合計 50"))))
        assertTrue(result.items.single().quantity is Fact.Unknown)
        assertTrue(result.items.single().printedTotal is Fact.Unknown)
        assertTrue(result.extraction!!.warnings.any { it.contains("單價") })
    }

    @Test fun unitOnlyTableKeepsLineTotalUnknown() {
        val result = mapped(parser.parse(listOf(page("商店", "品名 數量 單價", "牛奶 2 50", "合計 100"))))
        assertEquals("2", result.items.single().quantity.inputText())
        assertTrue(result.items.single().printedTotal is Fact.Unknown)
    }

    @Test fun exactCrossPageDuplicatesAreOneUnresolvedCandidateButWithinPageRepeatsRemain() {
        val p = page("商店", "品名 數量 金額", "牛奶 1 50", "牛奶 1 50", "合計 100")
        val result = mapped(parser.parse(listOf(p, p)), listOf(source, second))
        assertEquals(2, result.items.size)
        assertEquals(ReceiptAssemblyStatus.PossibleDuplicates, result.assemblyStatus)
        assertTrue(result.items.all { it.quantity is Fact.Unknown && it.printedTotal is Fact.Unknown })
        assertEquals(10, result.extraction!!.regions.size)
    }

    @Test fun disjointPagesAndConflictingTransactionTotalsRetainTheirEvidence() {
        val result = mapped(parser.parse(listOf(page("商店", "品名 數量 金額", "牛奶 1 50", "合計 50"),
            page("商店", "品名 數量 金額", "餅乾 1 20", "合計 70"))), listOf(source, second))
        assertEquals(2, result.items.size)
        assertTrue(result.total is Fact.Conflicting)
        assertEquals(2, (result.merchant as Fact.Known).provenance.evidence.size)
    }

    @Test fun discountsFeesAreSeparateAndScopeIsNotInvented() {
        val result = mapped(parser.parse(listOf(page("商店", "品名 數量 金額", "牛奶 2 50", "折扣 -10", "服務費 5", "總計 45"))))
        assertEquals(2, result.adjustments.size)
        assertEquals(AdjustmentDirection.Subtract, result.adjustments[0].direction)
        assertEquals("10", result.adjustments[0].amount.inputText())
        assertEquals(AdjustmentDirection.Add, result.adjustments[1].direction)
        assertTrue(result.adjustments.all { it.scope is Fact.Unknown })
    }

    @Test fun invalidDateFractionQuantityNegativeOverflowAndDecimalMoneyRemainUnknown() {
        val original = normal()
        for (bad in listOf("-1", "1e3", "9223372036854775808", "12.50", "12,34")) {
            val result = mapped(original.copy(totals = listOf(original.totals.single().copy(text = bad))))
            assertTrue(bad, result.total is Fact.Unknown)
        }
        val item = original.items.single()
        val result = mapped(original.copy(dates = listOf(original.dates.single().copy(text = "2026-02-30")),
            items = listOf(item.copy(quantity = item.quantity!!.copy(text = "1.5")))))
        assertTrue(result.transactionDate is Fact.Unknown)
        assertTrue(result.items.single().quantity is Fact.Unknown)
    }

    @Test fun validThousandsAndZeroFractionUseIntegerParsing() {
        val original = normal()
        assertEquals("1234", mapped(original.copy(totals = listOf(original.totals.single().copy(text = "1,234.00")))).total.inputText())
    }

    @Test fun emptyItemsInvalidReferencesOversizedOutputAndBadBoundsAreRejected() {
        val original = normal()
        assertThrows(IllegalArgumentException::class.java) { mapped(original.copy(items = emptyList())) }
        assertThrows(IllegalArgumentException::class.java) { mapped(original.copy(merchants = listOf(TextObservation("merchant", 9, 99)))) }
        assertThrows(IllegalArgumentException::class.java) { mapped(original.copy(items = List(101) { original.items.single() })) }
        assertThrows(IllegalArgumentException::class.java) { mapped(original.copy(pages = listOf(original.pages.single().copy(lines = listOf(OcrLine("bad", -1, 0, 2, 2)))))) }
    }

    @Test fun auditRoundTripAndManualCorrectionPreserveOriginalTextAndRegionReferences() {
        val draft = mapped()
        val restored = DraftCodec().decode(DraftCodec().encode(draft))
        assertEquals(draft, restored)
        val corrected = ManualReceiptReview(ReviewTestRepository(draft)).evaluate(draft,
            ReviewInput.from(draft).copy(merchant = "已修正商家"), 100).draft!!
        assertEquals(draft.extraction, corrected.extraction)
        assertTrue(corrected.merchant is Fact.Known)
        assertTrue((corrected.merchant as Fact.Known).provenance is FactProvenance.UserConfirmed)
        assertTrue(draft.extraction!!.regions.map { it.id }.containsAll(draft.evidenceLinks.map { it.evidence.regionId }))
    }

    @Test fun rerunRequiresConsentAndReplacesInsteadOfAppending() = runBlocking {
        val repo = ReviewTestRepository(base)
        val useCase = ExtractReceipt(repo, analyzer(), { listOf(source, second) }, {})
        val first = useCase.run(base, setOf(source.id), false) { true }
        try { useCase.run(first, setOf(source.id), false) { true }; fail() } catch (_: IllegalArgumentException) { }
        val secondRun = useCase.run(first, setOf(source.id), true) { true }
        assertEquals(1, secondRun.items.size)
        assertEquals(2L, secondRun.revision)
        assertNotEquals(first.items.single().id, secondRun.items.single().id)
    }

    @Test fun inFlightRevisionConflictPreservesManualEdit() = runBlocking {
        val repo = ReviewTestRepository(base)
        val useCase = ExtractReceipt(repo, analyzer {
            repo.current = base.copy(revision = 1, merchant = Fact.Known("人工", FactProvenance.UserConfirmed(1)))
            AnalysisResult.Success(AnalyzedReceipt(null, null, null, "TWD", emptyList(), normal()))
        }, { listOf(source, second) }, {})
        try { useCase.run(base, setOf(source.id), false) { true }; fail() } catch (_: IllegalStateException) { }
        assertEquals("人工", repo.current!!.merchant.inputText())
        assertTrue(repo.current!!.items.isEmpty())
    }

    @Test fun cancellationAndInferenceFailurePreserveOriginalAndAllowRetry() = runBlocking {
        val repo = ReviewTestRepository(base)
        val started = CompletableDeferred<Unit>()
        val blocked = ExtractReceipt(repo, analyzer { started.complete(Unit); awaitCancellation() }, { listOf(source) }, {})
        val job = launch { blocked.run(base, setOf(source.id), false) { true } }
        started.await(); job.cancelAndJoin()
        assertEquals(base, repo.current)
        val failed = ExtractReceipt(repo, analyzer { AnalysisResult.Failure(AnalysisError(AnalysisErrorKind.ServiceUnavailable)) }, { listOf(source) }, {})
        try { failed.run(base, setOf(source.id), false) { true }; fail() } catch (_: IllegalStateException) { }
        assertEquals(base, repo.current)
        assertEquals(1, ExtractReceipt(repo, analyzer(), { listOf(source) }, {}).run(base, setOf(source.id), false) { true }.items.size)
    }

    @Test fun changedImageBytesAndForegroundExitBlockApplication() = runBlocking {
        val repo = ReviewTestRepository(base)
        val changed = ExtractReceipt(repo, analyzer(), { listOf(source) }, { error("changed bytes") })
        try { changed.run(base, setOf(source.id), false) { true }; fail() } catch (_: IllegalStateException) { }
        assertEquals(base, repo.current)
        var active = true
        val leave = ExtractReceipt(repo, analyzer { active = false; AnalysisResult.Success(AnalyzedReceipt(null, null, null, "TWD", emptyList(), normal())) }, { listOf(source) }, {})
        try { leave.run(base, setOf(source.id), false) { active }; fail() } catch (_: IllegalStateException) { }
        assertEquals(base, repo.current)
    }

    @Test fun savedOwnershipCannotBeReplacedEvenWithOcrReplacementConsent() = runBlocking {
        val assigned = base.copy(personalExpenses = listOf(LineExpenseDecision("line", ExpenseSplitMethod.WholeLine, ExpensePurpose.Advance)))
        val repo = ReviewTestRepository(assigned)
        val useCase = ExtractReceipt(repo, analyzer { error("must not run") }, { listOf(source) }, {})
        try { useCase.run(assigned, setOf(source.id), true) { true }; fail() } catch (_: IllegalArgumentException) { }
        assertEquals(assigned, repo.current)
    }

    @Test fun calendarTokensUseRawOcrInsteadOfColumnGapsAndNeverAcceptTruncatedDays() {
        val split = OcrPage(1000, 1000, listOf(OcrLine("2026/09/1 1", 1, 1, 900, 30, rawText = "2026/09/11")))
        assertEquals("2026-09-11", parser.parse(listOf(split)).dates.single().text)
        assertTrue(parser.parse(listOf(page("2026/09/1 1"))).dates.isEmpty())
        assertEquals("2026-09-11", parser.parse(listOf(page("2026/09/11 12:35"))).dates.single().text)
        assertTrue(parser.parse(listOf(page("2026/09/111"))).dates.isEmpty())
    }

    @Test fun confirmedTransactionCannotInvokeAnalyzer() = runBlocking {
        val confirmed = base.copy(stage = ReceiptStage.Confirmed)
        val repo = ReviewTestRepository(confirmed)
        val useCase = ExtractReceipt(repo, analyzer { error("must not run") }, { listOf(source) }, {})
        try { useCase.run(confirmed, setOf(source.id), true) { true }; fail() } catch (_: IllegalArgumentException) { }
        assertEquals(confirmed, repo.current)
    }

    @Test fun priceTagSelectionIsRejected() = runBlocking {
        val tagged = source.copy(kind = Fact.Known(EvidenceAssetKind.PriceTag, FactProvenance.UserConfirmed(1)))
        val useCase = ExtractReceipt(ReviewTestRepository(base), analyzer { error("must not run") }, { listOf(tagged) }, {})
        try { useCase.run(base, setOf(source.id), false) { true }; fail() } catch (_: IllegalArgumentException) { }
    }

    @Test fun conflictingImageMetadataAndConcurrentConfirmationPreventOverwrite() = runBlocking {
        val repo = ReviewTestRepository(base)
        var reads = 0
        val metadataChanged = ExtractReceipt(repo, analyzer(), { if (++reads == 1) listOf(source) else listOf(source.copy(contentSha256 = "c".repeat(64))) }, {})
        try { metadataChanged.run(base, setOf(source.id), false) { true }; fail() } catch (_: IllegalStateException) { }
        assertEquals(base, repo.current)
        val confirmed = base.copy(revision = 1, stage = ReceiptStage.Confirmed)
        val confirmation = ExtractReceipt(repo, analyzer {
            repo.current = confirmed
            AnalysisResult.Success(AnalyzedReceipt(null, null, null, "TWD", emptyList(), normal()))
        }, { listOf(source) }, {})
        try { confirmation.run(base, setOf(source.id), false) { true }; fail() } catch (_: IllegalStateException) { }
        assertEquals(confirmed, repo.current)
    }

    @Test fun similarCrossPageNamesRemainUnresolvedAndAmountPreviewDoesNotChangeGate() {
        val result = mapped(parser.parse(listOf(page("商店", "品名 數量 金額", "牛奶 1 50"),
            page("商店", "品名 數量 金額", "午奶 1 50", "合計 50"))), listOf(source, second))
        assertEquals(1, result.items.size)
        assertTrue(result.items.single().rawName is Fact.Conflicting)
        assertTrue(receiptAmountPreview(result).contains("未知"))
        val normal = mapped()
        assertTrue(receiptAmountPreview(normal).contains("差額 0"))
        assertTrue(ReceiptReconciler().reconcile(normal) is ReceiptReconciliationResult.Indeterminate)
    }

    @Test fun unsupportedCurrencyAndNoItemsCannotMasqueradeAsSuccess() = runBlocking {
        assertThrows(IllegalArgumentException::class.java) { parser.parse(listOf(page("商店", "USD 100"))) }
        val repo = ReviewTestRepository(base)
        val useCase = ExtractReceipt(repo, analyzer { AnalysisResult.Success(AnalyzedReceipt("fake", null, "0", "USD", emptyList(), normal())) }, { listOf(source) }, {})
        try { useCase.run(base, setOf(source.id), false) { true }; fail() } catch (_: IllegalArgumentException) { }
        assertEquals(base, repo.current)
    }
}
