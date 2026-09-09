package com.momonong.pixelreceipt.feature.inbox

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import com.momonong.pixelreceipt.domain.model.*
import com.momonong.pixelreceipt.domain.usecase.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ReceiptFlowSessionTest {
    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private fun form() = ReviewInput("餐廳", total = "120", complete = true,
        lines = listOf(ReviewLineInput("meal", "午餐", "2", "120")))

    @Test fun saveAndLeaveOnlyClosesAfterSuccessfulWrite() {
        val repo = ReviewTestRepository(ReceiptDraft("receipt"))
        val session = ReviewSession(repo, SavedStateHandle(), scope())
        session.open("receipt")
        session.edit(form())
        session.save { session.close() }
        assertNull(session.state.value.base)
        assertEquals("120", repo.current!!.items.single().printedTotal.inputText())
        session.open("receipt")
        assertFalse(session.state.value.dirty)
        assertTrue(session.state.value.input.date.isEmpty())
    }

    @Test fun failedSaveNeitherLeavesNorLaunchesPhotoPicker() {
        val repo = ReviewTestRepository(ReceiptDraft("receipt"))
        val session = ReviewSession(repo, SavedStateHandle(), scope())
        session.open("receipt")
        session.edit(form().copy(total = "12."))
        var continued = false
        session.save { continued = true; session.close() }
        assertFalse(continued)
        assertTrue(session.state.value.dirty)
        assertEquals("12.", session.state.value.input.total)
        session.edit(form())
        repo.current = repo.current!!.copy(revision = 50)
        session.save { continued = true }
        assertFalse(continued)
        assertTrue(session.state.value.conflict)
    }

    @Test fun changingCheckedContentRequiresUserToCheckCompletenessAgain() {
        val repo = ReviewTestRepository(ReceiptDraft("receipt"))
        val session = ReviewSession(repo, SavedStateHandle(), scope())
        session.open("receipt"); session.edit(form()); session.save()
        session.edit(session.state.value.input.copy(lines = listOf(ReviewLineInput("meal", "午餐", "3", "120"))))
        assertFalse(session.state.value.input.complete)
        session.save(); session.confirm()
        assertEquals(ReceiptStage.NeedsReview, repo.current!!.stage)
        session.edit(session.state.value.input.copy(complete = true)); session.save(); session.confirm()
        assertEquals(ReceiptStage.Confirmed, repo.current!!.stage)
    }

    @Test fun appendRefreshNeverDiscardsDirtyOrBusyInput() {
        val repo = ReviewTestRepository(ReceiptDraft("receipt"))
        val session = ReviewSession(repo, SavedStateHandle(), scope())
        session.open("receipt"); session.edit(form())
        repo.current = repo.current!!.copy(revision = 10)
        session.refreshAfterImport()
        assertEquals(1L, session.state.value.base!!.revision)
        assertEquals(form(), session.state.value.input)
        session.attachmentBusy(true)
        session.edit(ReviewInput())
        assertEquals(form(), session.state.value.input)
        session.attachmentBusy(false)
    }

    @Test fun photoContextDoesNotCreateConfirmedLineLinksAndZeroDiffersFromUnknown() {
        val base = ReceiptDraft("receipt", stage = ReceiptStage.NeedsReview, evidenceAssetIds = setOf("photo"))
        val review = ManualReceiptReview(ReviewTestRepository(base))
        val without = review.evaluate(base, form(), 1).draft!!
        assertTrue(without.evidenceLinks.isEmpty())
        assertTrue((without.items.single().printedTotal as Fact.Known).provenance.evidence.isEmpty())
        assertTrue(without.items.single().referenceOriginalTotal is Fact.Unknown)
        val zero = review.evaluate(base, form().copy(total = "0"), 2).draft!!
        val unknown = review.evaluate(base, form().copy(total = ""), 3).draft!!
        assertEquals("0", zero.total.inputText())
        assertTrue(unknown.total is Fact.Unknown)
    }

    @Test fun feedbackMapsStableIdsToVisibleItemLocations() {
        val input = form()
        assertEquals("品項 第 1 項「午餐」：數量未知", reviewLocations("品項 meal：數量未知", input))
        assertNull(inputProblem("0", "amount", true))
        assertNotNull(inputProblem("0", "quantity", true))
        assertNotNull(inputProblem("12.", "amount"))
        assertNotNull(inputProblem("2026-02-30", "date"))
    }
}
