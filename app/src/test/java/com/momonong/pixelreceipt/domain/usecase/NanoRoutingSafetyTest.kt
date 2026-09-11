package com.momonong.pixelreceipt.domain.usecase

import com.momonong.pixelreceipt.domain.ai.*
import com.momonong.pixelreceipt.domain.model.*
import com.momonong.pixelreceipt.domain.port.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class NanoRoutingSafetyTest {
    private val asset = EvidenceAsset("photo", contentSha256 = "a".repeat(64), mimeType = "image/png", byteSize = 1,
        widthPx = 1000, heightPx = 1000, importSource = EvidenceImportSource.Other, importedAtEpochMillis = 0)
    private val base = ReceiptDraft("draft", evidenceAssetIds = setOf("photo"))
    private class Adapter(val ready: suspend () -> OnDeviceModelState) : OnDeviceReceiptAnalyzer {
        var calls = 0
        override val descriptor = AiAnalyzerDescriptor("fixture", "local", "fixture", schemaVersion = "1", capabilities = AiCapability.entries)
        override suspend fun prepare() = ready()
        override suspend fun analyze(request: AiAnalysisRequest): AnalysisResult {
            calls++
            return AnalysisResult.Failure(AnalysisError(AnalysisErrorKind.RateLimited, "裝置配額不足"))
        }
    }
    @Test fun unavailableNeverRunsOrSilentlyFallsBackAndExplicitOcrBypassesNanoPreparation() = runBlocking {
        val repo = ReviewTestRepository(base)
        val nano = Adapter { OnDeviceModelState.Unavailable }
        val ocr = Adapter { OnDeviceModelState.Available }
        val useCase = ExtractReceipt(repo, nano, { listOf(asset) }, {}, ocr)
        try { useCase.run(base, setOf("photo"), false) { true }; fail() } catch (_: IllegalStateException) { }
        assertEquals(0, nano.calls); assertEquals(0, ocr.calls); assertEquals(base, repo.current)
        try { useCase.run(base, setOf("photo"), false, true) { true }; fail() }
        catch (error: IllegalStateException) { assertEquals("裝置配額不足", error.message) }
        assertEquals(1, ocr.calls); assertEquals(0, nano.calls); assertEquals(base, repo.current)
    }
    @Test fun cancellationDuringDownloadAndForegroundLossAfterPreparationNeverInvokeInference() = runBlocking {
        val repo = ReviewTestRepository(base)
        val started = CompletableDeferred<Unit>()
        val nano = Adapter { started.complete(Unit); awaitCancellation() }
        val useCase = ExtractReceipt(repo, nano, { listOf(asset) }, {})
        val job = launch { useCase.run(base, setOf("photo"), false) { true } }
        started.await(); job.cancelAndJoin()
        assertEquals(0, nano.calls); assertEquals(base, repo.current)
        var foreground = true
        val resumed = Adapter { foreground = false; OnDeviceModelState.Available }
        try { ExtractReceipt(repo, resumed, { listOf(asset) }, {}).run(base, setOf("photo"), false) { foreground }; fail() }
        catch (_: IllegalStateException) { }
        assertEquals(0, resumed.calls); assertEquals(base, repo.current)
    }
    @Test fun assistantCreatesOnlyNewUnconfirmedDraftWithLocallyChosenId() = runBlocking {
        val repo = ReviewTestRepository(null)
        val draft = CreateAssistantReviewDraft(repo).create()
        assertEquals(draft, repo.current)
        assertEquals(0L, draft.revision)
        assertEquals(ReceiptStage.NeedsReview, draft.stage)
        assertTrue(draft.total is Fact.Unknown && draft.merchant is Fact.Unknown)
        assertTrue(draft.items.isEmpty() && draft.personalExpenses.isEmpty() && draft.evidenceAssetIds.isEmpty())
        val other = ReviewTestRepository(null)
        assertNotEquals(draft.id, CreateAssistantReviewDraft(other).create().id)
        try { CreateAssistantReviewDraft(repo).create(); fail() } catch (_: IllegalStateException) { }
        assertEquals(draft, repo.current)
    }
}
