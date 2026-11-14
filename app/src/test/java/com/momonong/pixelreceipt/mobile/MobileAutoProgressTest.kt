package com.momonong.pixelreceipt.mobile

import android.app.Application
import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.momonong.pixelreceipt.data.ingestion.ImageStore
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.File
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Regression: staying on a screen must update the real saved intake, without another tap/POST. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class MobileAutoProgressTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var root: File
    private lateinit var store: MobileStore
    private lateinit var repo: MobileRepository
    private val models = ViewModelStore()
    private val reads = AtomicInteger()
    private val posts = AtomicInteger()
    private val recognitionReads = AtomicInteger()
    @Volatile private var remoteStatus = "processing"
    @Volatile private var statusError: Int? = null
    private val intakeId = "11111111-1111-4111-8111-111111111111"
    private val draftId = "22222222-2222-4222-8222-222222222222"

    @Before fun setup() {
        root = File(app.cacheDir, UUID.randomUUID().toString()).apply { mkdirs() }
        store = MobileStore(File(root, "state")); store.load()
        val api = object : MobileApi by FakeMobileApi() {
            override suspend fun ledgerScope(token: String) = "33333333-3333-4333-8333-333333333333"
            override suspend fun recognition(token: String): Map<String, EngineAvailability> {
                recognitionReads.incrementAndGet()
                return mapOf("broker" to EngineAvailability(true, null))
            }
            override suspend fun status(token: String, id: String): ServerIntake {
                assertEquals(intakeId, id); reads.incrementAndGet()
                statusError?.let { throw MobileApiFailure(it) }
                val draft = draftId.takeIf { remoteStatus == "succeeded" }
                return ServerIntake(id, remoteStatus, draft,
                    """{"id":"$id","status":"$remoteStatus","draft_id":${draft?.let { "\"$it\"" } ?: "null"},"polling_finished":false}""")
            }
            override suspend fun submit(token: String, frozen: FrozenIntake): ServerIntake {
                posts.incrementAndGet(); error("A status refresh must not submit a new recognition")
            }
        }
        repo = MobileRepository(store, ImageStore(File(root, "images")), MemoryVault().apply { save("prm_fixture") }, api)
    }
    @After fun cleanup() { models.clear(); root.deleteRecursively() }
    private fun batch(status: String = "queued"): MobileBatch {
        val b = store.create(UUID.randomUUID().toString(), "保留我的補充說明")
        return store.update(b.id) { it.copy(importComplete = true, intakeId = intakeId, status = status) }
    }
    private fun vm(): MobileViewModel = MobileViewModel(app, SavedStateHandle(), repo).also {
        models.put(UUID.randomUUID().toString(), it); await { it.signedIn.value }
    }
    private fun await(condition: () -> Boolean) {
        val end = System.nanoTime() + 3_000_000_000L
        while (!condition() && System.nanoTime() < end) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5) }
        shadowOf(Looper.getMainLooper()).idle(); assertTrue("Saved state did not update automatically", condition())
    }
    private fun advance(seconds: Long) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(seconds)) }

    @Test fun openScreenReachesReviewAutomaticallyWithoutAnotherTap() {
        val b = batch(); val model = vm(); model.foreground()
        await { store.batch(b.id).status == "processing" && model.busy.value == null }
        remoteStatus = "succeeded"; advance(6)
        await { store.batch(b.id).status == "succeeded" }
        assertEquals(draftId, store.batch(b.id).draftId)
        assertEquals("保留我的補充說明", store.batch(b.id).userNotes)
        assertEquals(0, posts.get())
    }
    @Test fun unknownOriginalIsPolledAutomaticallyAndNeverReposted() {
        remoteStatus = "unknown"; val b = batch("unknown"); val model = vm(); model.foreground()
        await { reads.get() >= 1 && store.batch(b.id).serverJson != null }
        advance(6); await { reads.get() >= 2 }
        assertEquals(intakeId, store.batch(b.id).intakeId)
        assertEquals(0, posts.get())
    }
    @Test fun unsubmittedDraftIsNeverAutomaticallySent() {
        store.create(UUID.randomUUID().toString(), "還在寫說明")
        val model = vm(); model.foreground(); advance(40)
        assertEquals(0, posts.get()); assertEquals(0, reads.get())
        assertNull(store.load().batches.single().frozen)
    }
    @Test fun repeatedResumeDoesNotCreateConcurrentPollingLoops() {
        val b = batch(); val model = vm(); repeat(4) { model.foreground() }
        await { reads.get() == 1 && store.batch(b.id).serverJson != null }
        advance(6); await { reads.get() >= 2 }
        assertEquals(2, reads.get()); assertEquals(0, posts.get())
    }
    @Test fun expiredLoginStopsAutomaticRequestsAndRetainsSavedReceipt() {
        val b = batch(); statusError = 401; val model = vm(); model.foreground()
        await { !model.signedIn.value }
        advance(90)
        assertEquals(1, reads.get()); assertEquals(0, posts.get())
        assertEquals("保留我的補充說明", store.batch(b.id).userNotes)
        assertEquals(intakeId, store.batch(b.id).intakeId)
    }
}
