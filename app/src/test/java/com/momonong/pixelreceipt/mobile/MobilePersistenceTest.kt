package com.momonong.pixelreceipt.mobile

import android.app.Application
import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.momonong.pixelreceipt.data.ingestion.ImageStore
import com.momonong.pixelreceipt.data.ingestion.ImportInput
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class MobilePersistenceTest {
    private val application get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var directory: File
    private var failWrites = 0
    private lateinit var store: MobileStore
    private lateinit var repo: MobileRepository
    private lateinit var api: FakeMobileApi
    private val viewModels = ViewModelStore()
    @Before fun setup() {
        directory = File(application.cacheDir, UUID.randomUUID().toString()).apply { mkdirs() }
        store = MobileStore(directory) { if (failWrites > 0) { failWrites--; throw IOException("injected ENOSPC") } }
        api = FakeMobileApi()
        repo = MobileRepository(store, ImageStore(File(directory, "images")), MemoryVault(), api)
    }
    @After fun cleanup() { viewModels.clear(); directory.deleteRecursively() }
    private fun batch(): MobileBatch {
        val initial = store.create(UUID.randomUUID().toString(), "原說明")
        return store.update(initial.id) { it.copy(importComplete = true,
            images = listOf(MobileImage("a".repeat(64), "image/png", 1, notes = "原逐圖說明"))) }
    }
    private fun createVm(saved: SavedStateHandle = SavedStateHandle()): MobileViewModel =
        MobileViewModel(application, saved, repo).also { viewModels.put(UUID.randomUUID().toString(), it); await { it.state.value != null } }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!condition() && System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5) }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("Timed out waiting for ViewModel persistence", condition())
    }
    private fun failedEditAndRetry(photo: Boolean) {
        val original = batch()
        val saved = SavedStateHandle()
        val vm = createVm(saved)
        failWrites = 1
        vm.edit(original.id) { old -> if (photo) old.copy(images = old.images.map { it.copy(notes = "新的逐圖說明，幫朋友買") })
            else old.copy(userNotes = "新的整批說明，幫朋友買") }
        await { vm.editState.value[original.id]?.error != null }
        assertEquals(0, failWrites) // Every subsequent write would succeed.
        vm.sync(original.id)
        await { vm.busy.value == null && vm.message.value?.contains("尚未保存") == true }
        assertEquals(0, api.uploads.size + api.submissions.size + api.statusCalls)
        assertNull(store.batch(original.id).frozen)
        assertEquals(original.userNotes, store.batch(original.id).userNotes)
        val current = vm.state.value!!.batches.single()
        assertEquals(if (photo) "新的逐圖說明，幫朋友買" else "原逐圖說明", current.images.single().notes)
        if (!photo) assertEquals("新的整批說明，幫朋友買", current.userNotes)
        // Recreating from SavedState keeps text, but never claims it was written to disk.
        val recovered = createVm(SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) }))
        assertNotNull(recovered.editState.value[original.id]?.error)
        assertEquals(current.userNotes, recovered.state.value!!.batches.single().userNotes)
        recovered.retrySave(original.id)
        await { recovered.editState.value.isEmpty() && recovered.busy.value == null }
        assertTrue(store.writable.value)
        val frozen = store.freeze(original.id, store.batch(original.id).revision)
        val body = JsonParser.parseString(frozen.frozen!!.body).asJsonObject
        if (photo) assertEquals("新的逐圖說明，幫朋友買", body.getAsJsonArray("evidence")[0].asJsonObject["notes"].asString)
        else assertEquals("新的整批說明，幫朋友買", body["user_notes"].asString)
    }
    @Test fun oneFailedBatchNoteWriteBlocksHealthySyncUntilExplicitRetry() = failedEditAndRetry(false)
    @Test fun oneFailedPhotoNoteWriteBlocksHealthySyncUntilExplicitRetry() = failedEditAndRetry(true)
    @Test fun olderSaveCannotClearANewerEditAndFreezeChecksRevisionInsideTheLock() = runBlocking {
        val original = batch()
        val edits = MobileEdits(SavedStateHandle())
        edits.edit(original) { it.copy(userNotes = "第一版") }
        edits.persist(original.id) { value, revision ->
            edits.edit(original) { it.copy(userNotes = "第二版") }
            repo.edit(original.id, revision) { value }
        }
        assertTrue(edits.pending(original.id))
        assertEquals("第二版", edits.state.value[original.id]!!.batch.userNotes)
        edits.persist(original.id) { value, revision -> repo.edit(original.id, revision) { value } }
        assertFalse(edits.pending(original.id))
        val savedRevision = store.batch(original.id).revision
        store.update(original.id) { it.copy(userNotes = "其他編輯器") }
        try { store.freeze(original.id, savedRevision); fail() } catch (_: MobileFailure) { }
        assertNull(store.batch(original.id).frozen)
    }
    @Test fun restoredEditAcknowledgesAnIdenticalCommittedRevisionWithoutOverwritingNewerContent() = runBlocking {
        val original = batch()
        val saved = SavedStateHandle()
        val edits = MobileEdits(saved)
        edits.edit(original) { it.copy(userNotes = "已寫入但尚未回報") }
        val disk = repo.edit(original.id, original.revision) { edits.state.value[original.id]!!.batch }
        val recovered = MobileEdits(SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) }))
        recovered.persist(original.id, retry = true) { value, revision -> repo.edit(original.id, revision) { value } }
        assertFalse(recovered.pending(original.id))
        assertEquals(disk, store.batch(original.id))
        val conflict = MobileEdits(SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) }))
        store.update(original.id) { it.copy(userNotes = "另一個新版本") }
        conflict.persist(original.id, retry = true) { value, revision -> repo.edit(original.id, revision) { value } }
        assertTrue(conflict.pending(original.id))
        assertNotNull(conflict.state.value[original.id]!!.error)
        assertEquals("另一個新版本", store.batch(original.id).userNotes)
    }
    @Test fun acceptedSyncIntentRejectsLateTextEventsBeforeComposeCanDisableTheField() {
        val original = batch()
        val vm = createVm()
        vm.edit(original.id) { it.copy(userNotes = "送出前最後版本") }
        vm.sync(original.id)
        vm.edit(original.id) { it.copy(userNotes = "送出後晚到事件") }
        await { store.batch(original.id).frozen != null && vm.busy.value == null }
        assertEquals("送出前最後版本", store.batch(original.id).userNotes)
        assertFalse(store.batch(original.id).frozen!!.body.contains("送出後晚到事件"))
    }
    @Test fun validExistingLoadIsReadOnlyEvenWhenEveryFutureWriteFails() {
        val original = batch()
        val file = File(directory, "state.json")
        val before = file.readBytes()
        val reopened = MobileStore(directory) { throw IOException("injected ENOSPC") }
        assertEquals(original, reopened.load().batches.single())
        assertEquals(original.revision, reopened.state.value!!.batches.single().revision)
        assertArrayEquals(before, file.readBytes())
        try { reopened.update(original.id) { it.copy(userNotes = "無法保存") }; fail() } catch (_: IOException) { }
        assertFalse(reopened.writable.value)
        assertEquals(original, reopened.load().batches.single())
        assertArrayEquals(before, file.readBytes())
    }
    @Test fun freshIdentityMustBeDurableBeforeLoginOrAnyAuthenticatedNetwork() = runBlocking {
        failWrites = 20
        try { repo.initialize(); fail() } catch (_: IOException) { }
        try { repo.login("fixture-password"); fail() } catch (_: IOException) { }
        try { repo.availability(); fail() } catch (_: IOException) { }
        assertEquals(0, api.loginCalls + api.recognitionCalls + api.uploads.size + api.submissions.size)
        assertNull(store.state.value)
        failWrites = 0
        val id = store.load().deviceId
        assertEquals(id, MobileStore(directory).load().deviceId)
    }
    @Test fun unreadableLockIsBlockedAndDoesNotTurnIntoAnUnlockedRead() {
        batch()
        val lock = File(directory, "state.lock")
        assertTrue(lock.delete()); assertTrue(lock.mkdir())
        try { MobileStore(directory).load(); fail() } catch (_: IOException) { }
    }
    @Test fun corruptImportReviewFailsClosedAndPreservesOriginalStateBytes() {
        val original = batch()
        val json = JsonParser.parseString(Gson().toJson(MobileState(store.load().deviceId, listOf(original)))).asJsonObject
        json.getAsJsonArray("batches")[0].asJsonObject.add("importReview", JsonParser.parseString("{\"selectedHashes\":null}"))
        val bytes = json.toString().toByteArray()
        File(directory, "state.json").writeBytes(bytes)
        try { MobileStore(directory).load(); fail() } catch (_: MobileFailure) { }
        assertArrayEquals(bytes, File(directory, "state.json").readBytes())
    }
    @Test fun legacyCompleteFrozenAndKnownIntakeStayUnchangedWhileIncompleteNeedsReview() {
        val original = batch()
        val states = listOf(original, original.copy(importComplete = false),
            original.copy(frozen = freezeIntake(original)),
            original.copy(intakeId = UUID.randomUUID().toString(), status = "unknown"))
        states.forEach { candidate ->
            val json = JsonParser.parseString(Gson().toJson(MobileState(store.load().deviceId, listOf(candidate)))).asJsonObject
            json.getAsJsonArray("batches")[0].asJsonObject.remove("importReview")
            File(directory, "state.json").writeText(json.toString())
            val restored = MobileStore(directory).load().batches.single()
            assertEquals(candidate, restored)
            if (!restored.importComplete && restored.editable) {
                try { freezeIntake(restored); fail() } catch (_: IllegalArgumentException) { }
            }
        }
    }
}
