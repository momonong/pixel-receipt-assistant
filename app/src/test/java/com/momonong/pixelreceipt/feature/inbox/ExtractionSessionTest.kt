package com.momonong.pixelreceipt.feature.inbox

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import com.momonong.pixelreceipt.data.extraction.LocalReceiptParser
import com.momonong.pixelreceipt.domain.ai.*
import com.momonong.pixelreceipt.domain.model.*
import com.momonong.pixelreceipt.domain.port.*
import com.momonong.pixelreceipt.domain.usecase.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ExtractionSessionTest {
    private val base = ReceiptDraft("draft", evidenceAssetIds = setOf("image"))
    private val asset = EvidenceAsset("image", contentSha256 = "a".repeat(64), mimeType = "image/png", byteSize = 1,
        widthPx = 100, heightPx = 100, importSource = EvidenceImportSource.Other, importedAtEpochMillis = 0)
    private fun success(): AnalysisResult {
        val page = OcrPage(100, 100, listOf("店", "品名 數量 金額", "牛奶 2 100", "總計 100")
            .mapIndexed { i, text -> OcrLine(text, 0, i * 20, 100, i * 20 + 19) })
        return AnalysisResult.Success(AnalyzedReceipt(null, null, null, "TWD", emptyList(), LocalReceiptParser().parse(listOf(page))))
    }

    @Test fun busyStatePreventsDuplicateRequestsAndForegroundLossCancelsWithoutOpeningReview() = runBlocking {
        val repo = ReviewTestRepository(base)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var calls = 0
        var opened = false
        val analyzer = object : OnDeviceReceiptAnalyzer {
            override val descriptor = AiAnalyzerDescriptor("ocr", "local", "1", schemaVersion = "1", capabilities = AiCapability.entries.toSet())
            override suspend fun analyze(request: AiAnalysisRequest): AnalysisResult { calls++; awaitCancellation() }
        }
        val session = ExtractionSession(ExtractReceipt(repo, analyzer, { listOf(asset) }, {}), SavedStateHandle(), scope) { opened = true }
        session.foreground(true)
        session.start(base, setOf("image"), false)
        session.start(base, setOf("image"), false)
        assertEquals(1, calls)
        assertTrue(session.state.value.busy)
        session.foreground(false)
        assertFalse(session.state.value.busy)
        assertFalse(opened)
        assertEquals(base, repo.current)
        assertTrue(session.state.value.message!!.contains("取消"))
        scope.cancel()
    }

    @Test fun retryAfterFailureAutomaticallyOpensPopulatedReviewAndRestoreNeverReplays() {
        val repo = ReviewTestRepository(base)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var calls = 0
        var opened: String? = null
        val analyzer = object : OnDeviceReceiptAnalyzer {
            override val descriptor = AiAnalyzerDescriptor("ocr", "local", "1", schemaVersion = "1", capabilities = AiCapability.entries.toSet())
            override suspend fun analyze(request: AiAnalysisRequest): AnalysisResult = if (++calls == 1)
                AnalysisResult.Failure(AnalysisError(AnalysisErrorKind.ServiceUnavailable)) else success()
        }
        val saved = SavedStateHandle(mapOf("extract.running" to true))
        val session = ExtractionSession(ExtractReceipt(repo, analyzer, { listOf(asset) }, {}), saved, scope) { opened = it }
        assertEquals(0, calls)
        assertTrue(session.state.value.message!!.contains("中斷"))
        session.start(base, setOf("image"), false)
        assertEquals(0, calls)
        session.foreground(true)
        session.start(base, setOf("image"), false)
        assertEquals(base, repo.current)
        assertNull(opened)
        session.start(base, setOf("image"), false)
        assertEquals("draft", opened)
        assertEquals(1, repo.current!!.items.size)
        assertFalse(session.state.value.busy)
        assertEquals(false, saved.get<Boolean>("extract.running"))
        scope.cancel()
    }
}
