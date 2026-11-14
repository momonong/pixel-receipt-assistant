package com.momonong.pixelreceipt.mobile

import android.app.Application
import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.momonong.pixelreceipt.data.ingestion.ImageStore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
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
import java.io.IOException
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Catch accidental new-key retry and failure to resume the saved send intent after recreation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class MobileAutoRetryTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var root: File
    private lateinit var store: MobileStore
    private lateinit var repo: MobileRepository
    private val models = ViewModelStore()
    private val submitted = CopyOnWriteArrayList<FrozenIntake>()
    private val vault = MemoryVault().apply { save("prm_fixture") }
    private var failure: Exception? = IOException("response lost")
    @Volatile private var holdFirstSubmit=false
    @Volatile private var timeoutFirstSubmit=false
    @Volatile private var failAfterAck=false
    @Volatile private var failWrites=0
    private val api = object : MobileApi by FakeMobileApi() {
        override suspend fun ledgerScope(token: String) = "33333333-3333-4333-8333-333333333333"
        override suspend fun submit(token: String, frozen: FrozenIntake): ServerIntake {
            submitted += frozen
            if (submitted.size == 1) {
                if(holdFirstSubmit)awaitCancellation()
                if(timeoutFirstSubmit)withTimeout(1){delay(50)}
                failure?.let{throw it}
                if(failAfterAck)failWrites=1
            }
            return ServerIntake("11111111-1111-4111-8111-111111111111", "queued", null,
                """{"status":"queued","error_code":"broker_admission_waiting","polling_finished":false}""")
        }
    }
    @Before fun setup() { root = File(app.cacheDir, UUID.randomUUID().toString()).apply { mkdirs() }; reopen() }
    private fun reopen() {
        store = MobileStore(File(root, "state")) { if(failWrites>0){failWrites--;throw IOException("local state write failed")} }; store.load()
        repo = MobileRepository(store, ImageStore(File(root, "images")), vault, api)
    }
    @After fun cleanup() { models.clear(); root.deleteRecursively() }
    private fun ready(): MobileBatch {
        val b = store.create(UUID.randomUUID().toString(), "牙膏是代買，請保留此說明")
        return store.update(b.id) { it.copy(importComplete = true, images = listOf(MobileImage("a".repeat(64), "image/png", 1)), uploadedHashes = listOf("a".repeat(64))) }
    }
    private fun vm(): MobileViewModel = MobileViewModel(app, SavedStateHandle(), repo).also {
        models.put(UUID.randomUUID().toString(), it); await { it.signedIn.value }; it.foreground()
    }
    private fun await(condition: () -> Boolean) {
        val end = System.nanoTime() + 3_000_000_000L
        while (!condition() && System.nanoTime() < end) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5) }
        shadowOf(Looper.getMainLooper()).idle(); assertTrue("Saved original request did not resume", condition())
    }
    private fun advance(seconds: Long) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(seconds)) }

    @Test fun lostResponseResumesTheExactSavedKeyAndNotesAfterRecreation() {
        val b = ready(); val model = vm(); model.sync(b.id)
        await { submitted.size == 1 && store.batch(b.id).message != null && model.busy.value == null }
        val original = submitted.single()
        assertTrue(store.batch(b.id).syncRetryable)
        assertTrue(store.batch(b.id).syncRetryAt!! > System.currentTimeMillis())
        // Android's virtual looper does not advance the JVM wall clock used by the durable deadline.
        // Make only the persisted timer due; the original request and permission must survive reopening.
        store.update(b.id) { it.copy(syncRetryAt = System.currentTimeMillis() - 1) }
        models.clear(); reopen(); vm(); advance(16)
        await { store.batch(b.id).intakeId != null }
        assertEquals(listOf(original, original), submitted.toList())
        assertEquals(original, store.batch(b.id).frozen)
        assertEquals("牙膏是代買，請保留此說明", store.batch(b.id).userNotes)
    }
    @Test fun explicitRejectionNeverAutomaticallyResubmits() {
        failure = MobileApiFailure(422)
        val b = ready(); val model = vm(); model.sync(b.id)
        await { submitted.size == 1 && store.batch(b.id).message != null && model.busy.value == null }
        advance(90)
        assertEquals(1, submitted.size); assertNull(store.batch(b.id).intakeId)
    }
    @Test fun manuallyStoppingSyncPreventsAutomaticResubmission() {
        val b = ready(); val model = vm(); model.sync(b.id)
        await { submitted.size == 1 && store.batch(b.id).message != null && model.busy.value == null }
        model.stopNetwork(); advance(90)
        assertEquals(1, submitted.size); assertNotNull(store.batch(b.id).frozen)
    }
    @Test fun legacyFrozenRecordNeedsAnExplicitSendInsteadOfBeingGuessedSafe() = runBlocking {
        val b = ready(); store.freeze(b.id); vm(); advance(90)
        assertTrue(submitted.isEmpty()); assertNotNull(store.batch(b.id).frozen)
    }
    @Test fun stoppingFromAnotherReceiptPersistsTheStopForTheActualPendingSend(){
        holdFirstSubmit=true
        val a=ready();val b=store.create(UUID.randomUUID().toString(),"另一筆尚未送出")
        val model=vm();model.select(a.id);model.sync(a.id);await{submitted.size==1}
        model.select(b.id);model.stopNetwork()
        await{model.busy.value==null&&!store.batch(a.id).syncRetryable}
        val frozen=store.batch(a.id).frozen
        models.clear();reopen();vm();advance(90)
        assertEquals(1,submitted.size);assertEquals(frozen,store.batch(a.id).frozen)
    }
    @Test fun localStateFailureAfterCentralAckDoesNotGrantAutomaticPostPermission(){
        failure=null;failAfterAck=true
        val b=ready();val model=vm();model.sync(b.id)
        await{submitted.size==1&&store.batch(b.id).message!=null&&model.busy.value==null}
        assertFalse("A local IOException must not be treated as a transport failure",store.batch(b.id).syncRetryable)
        models.clear();reopen();vm();advance(90)
        assertEquals(1,submitted.size);assertNotNull(store.batch(b.id).frozen)
    }
    @Test fun submitDeadlineTimeoutKeepsTheOriginalRequestRetryable(){
        timeoutFirstSubmit=true
        val b=ready();val model=vm();model.sync(b.id)
        await{submitted.size==1&&store.batch(b.id).message!=null&&model.busy.value==null}
        assertTrue(store.batch(b.id).syncRetryable)
        assertNotNull("A request timeout needs a durable backoff, not a manual-cancel marker",store.batch(b.id).syncRetryAt)
        val frozen=store.batch(b.id).frozen
        store.update(b.id){it.copy(syncRetryAt=System.currentTimeMillis()-1)}
        models.clear();reopen();vm();advance(16);await{store.batch(b.id).intakeId!=null}
        assertEquals(listOf(frozen,frozen),submitted.toList())
    }
}
