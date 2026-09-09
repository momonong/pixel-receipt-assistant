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
class ReviewSessionTest {
    private fun form() = ReviewInput("店", "2026-09-08", "100", true, lines = listOf(ReviewLineInput("line", "商品", "1", "100")))
    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Test fun rawInvalidEditsAndOriginalRevisionSurviveSavedStateRecreation() {
        val repo = ReviewTestRepository(ReceiptDraft("draft"))
        val saved = SavedStateHandle()
        val first = ReviewSession(repo, saved, scope())
        first.open("draft")
        first.edit(form().copy(total = "12."))
        val recreated = ReviewSession(repo, SavedStateHandle(mapOf(
            "review.base" to saved.get<String>("review.base"), "review.input" to saved.get<String>("review.input"))), scope())
        assertEquals("12.", recreated.state.value.input.total)
        assertEquals(1L, recreated.state.value.base!!.revision)
        assertTrue(recreated.state.value.dirty)
        recreated.save()
        assertTrue(recreated.state.value.message!!.contains("收據總額"))
        assertEquals(1L, repo.current!!.revision)
        recreated.edit(form())
        recreated.save()
        assertFalse(recreated.state.value.dirty)
        assertEquals("修改已保存。", recreated.state.value.message)
    }

    @Test fun conflictPreservesInputAndExplicitReloadUsesLatestIncludingConfirmedReadOnly() {
        val repo = ReviewTestRepository(ReceiptDraft("draft"))
        val session = ReviewSession(repo, SavedStateHandle(), scope())
        session.open("draft")
        session.edit(form())
        repo.current = repo.current!!.copy(revision = 2, merchant = Fact.Known("另一使用者", FactProvenance.UserConfirmed(10)), stage = ReceiptStage.Confirmed)
        session.save()
        assertTrue(session.state.value.conflict)
        assertEquals(form(), session.state.value.input)
        assertEquals("另一使用者", session.state.value.latest!!.merchant.inputText())
        session.reload()
        assertFalse(session.state.value.conflict)
        assertFalse(session.state.value.editable)
        assertEquals(ReceiptStage.Confirmed, session.state.value.base!!.stage)
        session.edit(form())
        assertEquals("另一使用者", session.state.value.input.merchant)
    }

    @Test fun confirmRequiresSavedInputAndRepeatConfirmDoesNotWrite() {
        val repo = ReviewTestRepository(ReceiptDraft("draft"))
        val session = ReviewSession(repo, SavedStateHandle(), scope())
        session.open("draft")
        session.edit(form())
        session.confirm()
        assertEquals(ReceiptStage.NeedsReview, repo.current!!.stage)
        session.save()
        session.confirm()
        assertEquals(ReceiptStage.Confirmed, repo.current!!.stage)
        val revision = repo.current!!.revision
        session.confirm()
        session.save()
        assertEquals(revision, repo.current!!.revision)
        session.close()
        assertNull(session.state.value.base)
    }

    @Test fun confirmationConflictKeepsSavedSnapshotAndHasExplicitRecovery() {
        val repo = ReviewTestRepository(ReceiptDraft("draft"))
        val session = ReviewSession(repo, SavedStateHandle(), scope())
        session.open("draft"); session.edit(form()); session.save()
        val snapshot = session.state.value.base!!
        repo.current = repo.current!!.copy(revision = snapshot.revision + 1)
        session.confirm()
        assertTrue(session.state.value.conflict)
        assertEquals(snapshot, session.state.value.base)
        assertEquals(ReceiptStage.NeedsReview, repo.current!!.stage)
        session.reload()
        session.confirm()
        assertEquals(ReceiptStage.Confirmed, repo.current!!.stage)
    }
}
