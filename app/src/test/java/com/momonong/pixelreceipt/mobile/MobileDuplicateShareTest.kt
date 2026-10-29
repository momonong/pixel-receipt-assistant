package com.momonong.pixelreceipt.mobile

import android.app.Application
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.momonong.pixelreceipt.data.ingestion.ImageStore
import com.momonong.pixelreceipt.data.ingestion.ImportInput
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MobileDuplicateShareTest {
    private lateinit var directory: File
    private lateinit var store: MobileStore
    private lateinit var api: FakeMobileApi
    private lateinit var repo: MobileRepository
    @Before fun setup() {
        directory = File(ApplicationProvider.getApplicationContext<Application>().cacheDir, UUID.randomUUID().toString()).apply { mkdirs() }
        store = MobileStore(directory)
        api = FakeMobileApi()
        repo = MobileRepository(store, ImageStore(File(directory, "images")), MemoryVault(), api)
    }
    @After fun cleanup() { directory.deleteRecursively() }
    private fun png(color: Int = 0xff123456.toInt()) = ByteArrayOutputStream().use { out ->
        Bitmap.createBitmap(4, 3, Bitmap.Config.ARGB_8888).apply { eraseColor(color); compress(Bitmap.CompressFormat.PNG, 100, out); recycle() }
        out.toByteArray()
    }
    private suspend fun imported(notes: String, bytes: List<ByteArray> = listOf(png())) = repo.import(
        UUID.randomUUID().toString(), notes, bytes.map { image -> ImportInput { ByteArrayInputStream(image) } },
    )
    @Test fun duplicateFirstSubmissionStopsBeforeFreezeUploadAndDoesNotRewriteRevision() = runBlocking {
        val old = imported("原收件")
        val incoming = imported("這次的新說明")
        val before = File(directory, "state.json").readBytes()
        var branch: Exception? = null
        try { repo.sync(incoming.id, incoming.revision) } catch (error: Exception) { branch = error }
        assertEquals("A possible duplicate needs a decision before any submit", 0, api.submissions.size)
        assertEquals(0, api.uploads.size)
        assertEquals("DuplicateShareRequired", branch?.javaClass?.simpleName)
        assertNull(store.batch(incoming.id).frozen)
        assertEquals(incoming, store.batch(incoming.id))
        assertEquals(old, store.batch(old.id))
        assertArrayEquals(before, File(directory, "state.json").readBytes())
    }
    private suspend fun reminder(id: String, approval: DuplicateShareApproval? = null): DuplicateShareReminder {
        try { repo.syncWithDuplicateApproval(id, store.batch(id).revision, approval); fail("Expected a local duplicate decision") }
        catch (required: DuplicateShareRequired) { return required.reminder }
        error("unreachable")
    }
    @Test fun explicitSeparateKeepsNotesRolesReferenceAndPageOrder() = runBlocking {
        val a = png(); val b = png(0xffaabbcc.toInt()); val promotion = png(0xffbbaa99.toInt())
        val old = imported("舊說明", listOf(a, b))
        var incoming = imported("新說明", listOf(b, a, promotion))
        repo.edit(incoming.id) { it.copy(images = it.images.mapIndexed { index, image -> image.copy(role = if (index == 2) "promotion" else "receipt", notes = "逐圖 $index") }) }
        incoming = store.batch(incoming.id)
        val prompt = reminder(incoming.id)
        assertEquals(listOf(old.id), prompt.candidates.map { it.id })
        repo.syncWithDuplicateApproval(incoming.id, incoming.revision, prompt.approval)
        val frozen = store.batch(incoming.id).frozen!!
        val body = jsonObject(frozen.body)
        assertEquals("新說明", body.text("user_notes"))
        assertEquals(incoming.images.map { it.hash }, body.array("evidence").map { it.asJsonObject.text("sha256") })
        assertEquals("promotion", body.array("evidence")[2].asJsonObject.text("role"))
        assertEquals("逐圖 0", body.array("evidence")[0].asJsonObject.text("notes"))
        assertEquals(1, api.submissions.size); assertEquals(old, store.batch(old.id))
    }
    @Test fun sharedPromotionSubsetAndReferenceDoNotBecomeReceiptMatches() = runBlocking {
        val a = png(); val b = png(0xffaabbcc.toInt()); val p = png(0xffbbaa99.toInt())
        val old = imported("舊", listOf(a, p))
        repo.edit(old.id) { it.copy(images = it.images.mapIndexed { i, image -> if (i == 1) image.copy(role = "promotion") else image }) }
        val fresh = imported("不同收據，同促銷", listOf(b, p))
        repo.edit(fresh.id) { it.copy(images = it.images.mapIndexed { i, image -> if (i == 1) image.copy(role = "promotion") else image }) }
        repo.sync(fresh.id)
        assertEquals(1, api.submissions.size)
        val twoPages = store.batch(old.id).copy(images = listOf(store.batch(old.id).images[0], store.batch(fresh.id).images[0]))
        assertNull(duplicateShareReminder(store.batch(old.id), listOf(twoPages.copy(id = UUID.randomUUID().toString()))))
        assertNull(duplicateShareReminder(store.batch(old.id).copy(purpose = "reference"), listOf(twoPages)))
    }
    @Test fun changedIncomingAndNewMatchingCandidateRequireANewDecision() = runBlocking {
        imported("原本")
        val incoming = imported("這次")
        val initial = reminder(incoming.id)
        repo.edit(incoming.id) { it.copy(userNotes = "提示中改了說明") }
        val before = File(directory, "state.json").readBytes()
        try { repo.syncWithDuplicateApproval(incoming.id, store.batch(incoming.id).revision, initial.approval); fail() } catch (_: DuplicateShareDecisionExpired) { }
        assertArrayEquals(before, File(directory, "state.json").readBytes())
        val changed = reminder(incoming.id)
        assertNotEquals(initial.approval, changed.approval)
        assertEquals("提示中改了說明", changed.incoming.userNotes)
        imported("比對後另一個新候選")
        val added = reminder(incoming.id, changed.approval)
        assertEquals(2, added.candidates.size)
        assertEquals(0, api.uploads.size); assertEquals(0, api.submissions.size)
        assertNull(store.batch(incoming.id).frozen)
    }
    @Test fun realLedgerProjectionWithoutRevisionChangeInvalidatesTheDisplayedDecision() = runBlocking {
        val old = imported("原帳目")
        val fixture = ReceiptActionsApi().apply { posted = true }
        store.update(old.id) { it.copy(draftId = fixture.draftId, ledgerId = fixture.entryId, ledgerStatus = "active", draftStatus = "confirmed") }
        store.cacheEntry(fixture.entry())
        val incoming = imported("新分享")
        val first = reminder(incoming.id)
        val revision = store.batch(old.id).revision
        fixture.voided = true; fixture.ledgerRevision++
        store.cacheEntry(fixture.entry()) // Actual production projection deliberately does not bump revision.
        assertEquals(revision, store.batch(old.id).revision)
        val updated = reminder(incoming.id, first.approval)
        assertEquals("voided", updated.candidates.single().ledgerStatus)
        assertNotEquals(first.approval, updated.approval)
        assertEquals(0, api.submissions.size)
    }
    @Test fun removedRoleMatchRejectsOldApprovalAndUnrelatedChangesDoNotInvalidateIt() = runBlocking {
        val old = imported("原本")
        val incoming = imported("這次")
        val first = reminder(incoming.id)
        repo.edit(old.id) { it.copy(images = it.images.map { image -> image.copy(role = "promotion") }) }
        val beforeExpired = File(directory, "state.json").readBytes()
        try { repo.syncWithDuplicateApproval(incoming.id, incoming.revision, first.approval); fail() } catch (_: DuplicateShareDecisionExpired) { }
        assertArrayEquals("An expired decision must not write a message/revision", beforeExpired, File(directory, "state.json").readBytes())
        assertEquals(0, api.submissions.size); assertNull(store.batch(incoming.id).frozen)
        repo.edit(old.id) { it.copy(images = it.images.map { image -> image.copy(role = "receipt") }) }
        val current = reminder(incoming.id)
        val unrelated = imported("另一筆", listOf(png(0xffaabbcc.toInt())))
        repo.edit(unrelated.id) { it.copy(userNotes = "無關修改") }
        repo.syncWithDuplicateApproval(incoming.id, store.batch(incoming.id).revision, current.approval)
        assertEquals(1, api.submissions.size)
    }
    @Test fun staleApprovalCannotSubmitContentFrozenOrAssignedAnIntakeByAnotherFlow() = runBlocking {
        imported("原本")
        val incoming = imported("這次")
        val first = reminder(incoming.id)
        repo.edit(incoming.id) { it.copy(userNotes = "另一流程的新內容") }
        store.freeze(incoming.id)
        val before = File(directory, "state.json").readBytes()
        try { repo.syncWithDuplicateApproval(incoming.id, store.batch(incoming.id).revision, first.approval); fail() } catch (_: DuplicateShareDecisionExpired) { }
        assertEquals("Old confirmation cannot become a retry for a different frozen body", 0, api.submissions.size)
        assertEquals(0, api.uploads.size)
        assertArrayEquals(before, File(directory, "state.json").readBytes())
        store.update(incoming.id) { it.copy(intakeId = UUID.randomUUID().toString(), status = "unknown") }
        val known = File(directory, "state.json").readBytes()
        try { repo.syncWithDuplicateApproval(incoming.id, store.batch(incoming.id).revision, first.approval); fail() } catch (_: DuplicateShareDecisionExpired) { }
        assertEquals(0, api.statusCalls)
        assertArrayEquals(known, File(directory, "state.json").readBytes())
    }
    @Test fun frozenUncertainRetryAfterNewRepositoryNeverPromptsOrChangesTheKey() = runBlocking {
        imported("原本")
        val incoming = imported("這次")
        val first = reminder(incoming.id)
        api.submitError = IOException("synthetic lost response")
        try { repo.syncWithDuplicateApproval(incoming.id, incoming.revision, first.approval); fail() } catch (_: IOException) { }
        val frozen = store.batch(incoming.id).frozen!!
        api.submitError = null
        val restored = MobileRepository(MobileStore(directory), ImageStore(File(directory, "images")), MemoryVault(), api)
        restored.sync(incoming.id)
        assertEquals(listOf(frozen, frozen), api.submissions)
        assertEquals(frozen, store.batch(incoming.id).frozen)
    }
    @Test fun importAndCasValidationRunBeforeAnyDuplicateDecision() = runBlocking {
        imported("原本")
        val incoming = imported("這次")
        try { repo.sync(incoming.id, incoming.revision - 1); fail() } catch (_: MobileFailure) { }
        store.update(incoming.id) { it.copy(importComplete = false) }
        try { repo.sync(incoming.id); fail() } catch (_: IllegalArgumentException) { }
        store.update(incoming.id) { it.copy(importComplete = true, images = it.images.map { image -> image.copy(notes = "x".repeat(1001)) }) }
        try { repo.sync(incoming.id); fail() } catch (_: IllegalArgumentException) { }
        assertEquals(0, api.uploads.size); assertEquals(0, api.submissions.size)
        assertNull(store.batch(incoming.id).frozen)
    }
}
