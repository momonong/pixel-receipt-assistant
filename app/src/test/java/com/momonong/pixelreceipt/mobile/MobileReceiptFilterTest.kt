package com.momonong.pixelreceipt.mobile

import androidx.compose.ui.graphics.luminance
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class MobileReceiptFilterTest {
    private val now = Instant.parse("2026-11-12T12:00:00Z").toEpochMilli()
    private fun batch(status: String? = null, ledger: String? = null, complete: Boolean = true, json: String? = null) =
        MobileBatch("synthetic", createdAt = 0, importComplete = complete, status = status, ledgerStatus = ledger, serverJson = json)

    @Test fun formalOutcomeWinsOverOldImportAndRecognitionFlags() {
        assertEquals(ReceiptStatus.Completed, receiptStatus(batch("processing", "active", false), now = now))
        assertEquals(ReceiptStatus.Voided, receiptStatus(batch("failed", "voided", false), now = now))
        assertEquals(ReceiptStatus.Deleted, receiptStatus(batch("deleted", "active"), now = now))
    }
    @Test fun pausedUnsentRecognitionHasAnHonestVisibleStatusEvenAfterADay() {
        val waiting = batch("queued", json="""{"created_at":"2026-11-10T12:00:00+00:00","error_code":"broker_admission_waiting","polling_finished":false}""")
        val status = receiptStatus(waiting, now=now)
        assertEquals("等待辨識恢復", status.label)
        assertTrue(ReceiptFilter().includes(status))
        assertTrue(ReceiptFilter("attention").includes(status))
        assertEquals("queued", waiting.status)
    }
    @Test fun formalOutcomeAlsoControlsTheNextStepAndMissingNamesHasItsOwnColor() {
        assertEquals("查看已完成帳目",mobileNextStep(batch("processing","active",false)))
        assertEquals("查看作廢紀錄",mobileNextStep(batch("failed","voided",false)))
        val missing=receiptTagColors("2 個品項待補品名")
        assertNotNull(missing)
        assertFalse(ReceiptStatus.entries.any { it.colors==missing })
        assertEquals(missing,receiptTagColors("10 個品項待補品名"))
    }
    @Test fun eachLabelHasItsOwnReadablePalette() {
        val statuses = ReceiptStatus.entries
        assertEquals(statuses.size, statuses.map { it.colors.ink.value }.distinct().size)
        assertEquals(statuses.size, statuses.map { it.colors.background.value }.distinct().size)
        statuses.forEach {
            val contrast = (it.colors.background.luminance() + .05f) / (it.colors.ink.luminance() + .05f)
            assertTrue("${it.label}: $contrast", contrast >= 4.5f)
            assertFalse(it.label.isBlank())
        }
    }
    @Test fun normalFilterKeepsNewReceiptsAndHidesArchivedOrProblemCases() {
        val visible = ReceiptStatus.entries.filter { ReceiptFilter().includes(it) }.toSet()
        assertTrue(visible.containsAll(listOf(ReceiptStatus.Completed, ReceiptStatus.NeedsReview, ReceiptStatus.Processing, ReceiptStatus.Draft, ReceiptStatus.WaitingSync, ReceiptStatus.ReferenceReady)))
        assertFalse(visible.any { it in setOf(ReceiptStatus.Deleted, ReceiptStatus.Voided, ReceiptStatus.MissingPhotos, ReceiptStatus.Delayed, ReceiptStatus.UnknownResult, ReceiptStatus.Failed, ReceiptStatus.Unavailable) })
        assertEquals(ReceiptStatus.entries.toSet(), ReceiptStatus.entries.filter { ReceiptFilter("all").includes(it) }.toSet())
        assertEquals(listOf(ReceiptStatus.NeedsReview), ReceiptStatus.entries.filter { ReceiptFilter(ReceiptStatus.NeedsReview.key).includes(it) })
    }
    @Test fun oldPhotoDateCannotMakeAFreshCentralJobDisappear() {
        assertEquals(ReceiptStatus.Processing, receiptStatus(batch("processing", json="""{"created_at":"2026-11-12T11:59:00+00:00","polling_finished":false}"""), now=now))
        assertEquals(ReceiptStatus.Processing, receiptStatus(batch("processing"), now=now))
    }
    @Test fun agedCentralJobIsVisibleThroughAttentionWithoutChangingOriginalJob() {
        val old=batch("processing",json="""{"created_at":"2026-11-10T12:00:00+00:00","polling_finished":false}""")
        assertEquals(ReceiptStatus.Delayed,receiptStatus(old,now=now))
        assertFalse(ReceiptFilter().includes(receiptStatus(old,now=now)))
        assertTrue(ReceiptFilter("attention").includes(receiptStatus(old,now=now)))
        assertEquals("processing",old.status)
        assertEquals(ReceiptStatus.UnknownResult,receiptStatus(batch("processing",json="""{"polling_finished":true}"""),now=now))
    }
    @Test fun pendingMutationIsVisibleAndIsNotMistakenForSuccessfulRemoval() {
        val value=batch("succeeded","active")
        val pending=ReceiptMutation("ledger_void","synthetic-ledger",value.id,"synthetic-key","{}")
        val state=receiptStatus(value,listOf(pending),now)
        assertEquals(ReceiptStatus.VoidPending,state)
        assertTrue(ReceiptFilter().includes(state))
        assertEquals(ReceiptStatus.Completed,receiptStatus(value,listOf(pending.copy(phase="completed")),now))
    }
    @Test fun unknownSavedFilterReturnsToTheNormalDefault() {
        assertEquals(ReceiptFilter(),restoreReceiptFilter("future-value"))
        assertEquals(ReceiptFilter("all"),restoreReceiptFilter("all"))
    }
    @Test fun centralPollingOutcomeSurvivesTheRealHttpsResponseParser() = kotlinx.coroutines.runBlocking {
        val id="11111111-1111-4111-8111-111111111111"
        val api=HttpsMobileApi(MobileTransport { """{"id":"$id","status":"processing","draft_id":null,"polling_finished":true}""" })
        val remote=api.status("synthetic-local-fixture",id)
        assertEquals(ReceiptStatus.UnknownResult,receiptStatus(batch(remote.status,json=remote.rawJson),now=now))
    }

}
