package com.momonong.pixelreceipt.mobile

import android.app.Application
import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.momonong.pixelreceipt.data.ingestion.ImageStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withTimeout
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class MobilePhotoStorageViewModelTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var root: File
    private lateinit var store: MobileStore
    private lateinit var images: ImageStore
    private lateinit var model: MobileViewModel
    private val models = ViewModelStore()
    private val batchId = UUID.randomUUID().toString()
    private val image = MobileImage("a".repeat(64), "image/png", 8)
    private var timeout = true
    private var block = false
    private val entered = CompletableDeferred<Unit>()
    private val cancelled = CompletableDeferred<Unit>()
    @Before fun setup() {
        root = File(app.cacheDir, UUID.randomUUID().toString()).apply { mkdirs() }
        store = MobileStore(File(root, "receipts")); store.create(batchId, "保留說明")
        store.update(batchId) { it.copy(importComplete = true, images = listOf(image), intakeId = UUID.randomUUID().toString(), status = "succeeded", ledgerId = UUID.randomUUID().toString(), draftStatus = "confirmed") }
        images = ImageStore(File(root, "images"))
        val api = object : MobileApi by FakeMobileApi() {
            override suspend fun ledgerScope(token: String) = "7a11bd13-6cf2-4f2f-8d86-4db17417aa1d"
            override suspend fun photoStorage(token: String): String {
                if (timeout) withTimeout(10) { awaitCancellation() }
                return """{"schema":"pixelreceipt-storage-1","evidence_bytes":8,"evidence_count":1,"evidence_limit_bytes":100,"database_bytes":1,"filesystem_available_bytes":900,"warning":"ok"}"""
            }
            override suspend fun downloadPhoto(token: String, image: MobileImage, target: File) {
                if (block) { entered.complete(Unit); try { awaitCancellation() } finally { cancelled.complete(Unit) } }
                withTimeout(10) { awaitCancellation() }
            }
        }
        model = MobileViewModel(app, SavedStateHandle(), MobileRepository(store, images, MemoryVault().apply { save("prm_fixture") }, api))
        models.put("photos", model)
        await { model.state.value != null && model.signedIn.value }
    }
    @After fun cleanup() { models.clear(); root.deleteRecursively() }
    private fun await(condition: () -> Boolean) {
        val end = System.nanoTime() + 3_000_000_000L
        while (!condition() && System.nanoTime() < end) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5) }
        shadowOf(Looper.getMainLooper()).idle(); assertTrue("Photo work did not settle", condition())
    }
    @Test fun internalRecoveryTimeoutClearsBusyAndAllowsRetryWithoutLosingMetadata() {
        model.recoverPhotos(batchId)
        await { !model.photoStorage.value.busy && model.photoStorage.value.message?.contains("重試") == true }
        assertFalse(images.file(image.hash).exists()); assertEquals("保留說明", store.batch(batchId).userNotes)
        model.refreshPhotoStorage()
        await { !model.photoStorage.value.busy && model.photoStorage.value.message?.contains("重試") == true }
        timeout = false
        model.refreshPhotoStorage()
        await { !model.photoStorage.value.busy && model.photoStorage.value.central != null }
        assertNull(model.photoStorage.value.message)
    }
    @Test fun internalReclaimTimeoutClearsBusyAndPreservesLocalPhoto() {
        images.file(image.hash).writeText("original")
        model.reclaimPhotos()
        await { !model.photoStorage.value.busy && model.photoStorage.value.message?.contains("回收尚未完成") == true }
        assertEquals("original", images.file(image.hash).readText())
    }
    @Test fun externalPageCancellationKeepsCancellationSemanticsAndDoesNotPublishFailure() {
        block = true
        model.recoverPhotos(batchId); await { entered.isCompleted && model.photoStorage.value.busy }
        model.photoPageChanged(MobilePage.Inbox, null)
        await { cancelled.isCompleted }
        assertFalse(model.photoStorage.value.busy); assertNull(model.photoStorage.value.message)
        assertFalse(images.file(image.hash).exists())
    }
}
