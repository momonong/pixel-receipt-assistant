package com.momonong.pixelreceipt.mobile

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.momonong.pixelreceipt.MainActivity
import com.momonong.pixelreceipt.app.ReceiptApplication
import com.momonong.pixelreceipt.data.ingestion.ImageStore
import com.momonong.pixelreceipt.data.ingestion.ImportInput
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Real MainActivity/default ViewModel factory/Screen/Repository; only isolated storage/API/vault. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = ReceiptApplication::class, qualifiers = "w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MobileDuplicateShareUiTest {
    @get:Rule val ui = createEmptyComposeRule()
    private lateinit var directory: File
    private lateinit var store: MobileStore
    private lateinit var repo: MobileRepository
    private lateinit var api: FakeMobileApi
    private lateinit var old: MobileBatch
    private lateinit var incoming: MobileBatch
    private lateinit var vm: MobileViewModel
    private lateinit var ledger: ReceiptActionsApi
    private val postCalls = AtomicInteger()
    private val detailGets = AtomicInteger()
    private var scenario: ActivityScenario<MainActivity>? = null
    private val photo = ByteArrayOutputStream().use { out ->
        Bitmap.createBitmap(4, 3, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff123456.toInt()); compress(Bitmap.CompressFormat.PNG, 100, out); recycle() }
        out.toByteArray()
    }
    @Before fun setup() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ReceiptApplication>()
        directory = File(app.cacheDir, UUID.randomUUID().toString()).apply { mkdirs() }
        store = MobileStore(directory); api = FakeMobileApi()
        ledger = ReceiptActionsApi().apply { posted = true }
        val fakeApi = object : MobileApi by api {
            override suspend fun submit(token: String, frozen: FrozenIntake): ServerIntake { postCalls.incrementAndGet(); return api.submit(token, frozen) }
            override suspend fun ledgerDetail(token: String, id: String): String { detailGets.incrementAndGet(); assertEquals(ledger.entryId, id); return ledger.entry() }
        }
        repo = MobileRepository(store, ImageStore(File(directory, "images")), MemoryVault().apply { save("prm_fixture") }, fakeApi)
        old = imported("原本收件")
        incoming = imported("這次新照片與說明")
        val delegate = ReceiptApplication::class.java.getDeclaredField("mobile\$delegate").apply { isAccessible = true }
        assertFalse((delegate.get(app) as Lazy<*>).isInitialized())
        delegate.set(app, lazyOf(repo))
    }
    @After fun close() { scenario?.close(); if (::directory.isInitialized) directory.deleteRecursively() }
    private suspend fun imported(notes: String) = repo.import(UUID.randomUUID().toString(), notes, listOf(ImportInput { ByteArrayInputStream(photo) }))
    @Suppress("DEPRECATION")
    private fun launch(fontScale: Float = 1f) {
        val app = ApplicationProvider.getApplicationContext<ReceiptApplication>()
        val configuration = Configuration(app.resources.configuration).apply { this.fontScale = fontScale }
        app.resources.updateConfiguration(configuration, app.resources.displayMetrics)
        scenario = ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java).apply { action = Intent.ACTION_MAIN })
        scenario!!.onActivity { vm = ViewModelProvider(it)[MobileViewModel::class.java] }
        await { vm.signedIn.value && vm.busy.value == null }
        ui.onNodeWithTag("mobile-inbox").performScrollToNode(hasTestTag("mobile-batch-${incoming.id}"))
        ui.onNodeWithTag("mobile-batch-${incoming.id}").performClick()
    }
    private fun await(condition: () -> Boolean) { ui.waitUntil(10_000) { shadowOf(Looper.getMainLooper()).idle(); condition() } }
    @Test fun firstDuplicateSendShowsTheNativeDecisionWithoutSubmittingOrFreezing() {
        launch()
        ui.onNodeWithTag("mobile-primary").performClick()
        await { vm.busy.value == null }
        ui.onNodeWithText("可能已分享過").assertIsDisplayed()
        ui.onNodeWithText("新照片與說明已保留。你可以查看舊收件，或另建一筆。").assertIsDisplayed()
        assertEquals(0, api.submissions.size); assertEquals(0, api.uploads.size)
        assertNull(store.batch(incoming.id).frozen)
    }
    private fun showReminder() {
        ui.onNodeWithTag("mobile-primary").performClick()
        await { vm.duplicateShare.value != null && vm.busy.value == null }
        ui.onNodeWithTag("duplicate-share-dialog").assertExists()
    }
    private fun stateBytes() = File(directory, "state.json").readBytes()
    private fun capture(name: String, dialog: Boolean = true) {
        val output = System.getProperty("pixelreceipt.screenshots")?.let { File(it, "$name.png") } ?: return
        ui.waitForIdle()
        val bitmap = (if (dialog) ui.onNodeWithTag("duplicate-share-dialog") else ui.onRoot()).captureToImage().asAndroidBitmap()
        output.parentFile!!.mkdirs(); output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
    @Suppress("DEPRECATION")
    @Test fun cancelAndSystemBackPreserveTheDraftAndHaveNoNetworkOrDiskWrite() {
        launch(); val before = stateBytes()
        showReminder(); capture("duplicate-share-dialog-390")
        // Native buttons intentionally have no keyboard focus in touch mode.
        scenario!!.onActivity {
            val decor = ShadowDialog.getLatestDialog().window!!.decorView
            fun compose(view: View): View? = if (view.javaClass.simpleName == "AndroidComposeView") view else
                (view as? ViewGroup)?.let { group -> (0 until group.childCount).firstNotNullOfOrNull { compose(group.getChildAt(it)) } }
            val view = requireNotNull(compose(decor))
            assertTrue(view.requestFocusFromTouch()) // The same native transition requested by Compose InputMode.Keyboard.
            assertFalse(view.isInTouchMode)
        }
        ui.waitForIdle()
        ui.onNodeWithTag("duplicate-share-cancel").assertIsFocused().performClick()
        ui.onNodeWithTag("duplicate-share-dialog").assertDoesNotExist()
        assertArrayEquals(before, stateBytes())
        showReminder()
        scenario!!.onActivity { ShadowDialog.getLatestDialog().onBackPressed() }
        ui.waitForIdle()
        ui.onNodeWithTag("duplicate-share-dialog").assertDoesNotExist()
        assertEquals(MobilePage.Detail.name, vm.destination.value)
        assertEquals(incoming.id, vm.selected.value)
        assertArrayEquals(before, stateBytes())
        assertEquals(0, postCalls.get()); assertEquals(0, api.uploads.size); assertEquals(0, api.statusCalls)
        capture("duplicate-share-cancelled-390", dialog = false)
    }
    @Test fun openingOriginalUnknownDoesNotSubmitAndKeepsTheIncomingDraft() {
        old = store.freeze(old.id)
        launch(); val before = stateBytes(); showReminder()
        ui.onNodeWithTag("duplicate-candidate-${old.id}").performClick()
        ui.waitForIdle()
        assertEquals(old.id, vm.selected.value)
        assertEquals(MobilePage.Detail.name, vm.destination.value)
        assertEquals(0, postCalls.get()); assertEquals(0, api.uploads.size); assertEquals(0, api.statusCalls)
        assertArrayEquals(before, stateBytes())
        assertEquals(incoming, store.batch(incoming.id)); assertEquals(old, store.batch(old.id))
        capture("duplicate-share-open-original-unknown", dialog = false)
        ui.onNodeWithTag("mobile-primary").performClick() // Explicit original retry, not navigation.
        await { vm.busy.value == null && api.submissions.isNotEmpty() }
        assertEquals(old.frozen, api.submissions.single())
        assertNull(store.batch(incoming.id).frozen)
    }
    @Test fun cancelledCallbackCannotApproveAnIdenticalReopenedPromptAndRapidCallbacksSubmitOnce() {
        launch(); showReminder()
        val cancelled = vm.duplicateShare.value!!
        ui.onNodeWithTag("duplicate-share-cancel").performClick()
        showReminder()
        val current = vm.duplicateShare.value!!
        assertNotSame(cancelled, current); assertEquals(cancelled.approval, current.approval)
        scenario!!.onActivity { vm.sendSeparateDuplicate(cancelled) }
        ui.waitForIdle(); assertEquals(0, postCalls.get()); assertSame(current, vm.duplicateShare.value)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        api.beforeSubmit = { entered.complete(Unit); release.await() }
        ui.onNodeWithTag("duplicate-share-separate").performClick()
        scenario!!.onActivity { vm.sendSeparateDuplicate(current); vm.sendSeparateDuplicate(current) }
        await { entered.isCompleted }
        assertEquals(1, postCalls.get())
        release.complete(Unit)
        await { vm.busy.value == null && api.submissions.size == 1 }
        assertEquals(1, postCalls.get()); assertNotNull(store.batch(incoming.id).frozen)
        assertEquals(old, store.batch(old.id))
    }
    @Test fun editsAndRoleChangesInvalidateTheDisplayedChoiceWithoutWritingOrSending() {
        launch(); showReminder()
        val original = vm.duplicateShare.value!!
        scenario!!.onActivity { vm.edit(incoming.id) { it.copy(userNotes = "提示期間的新說明") } }
        await { vm.editState.value.isEmpty() && store.batch(incoming.id).userNotes == "提示期間的新說明" }
        val edited = stateBytes()
        ui.onNodeWithTag("duplicate-share-separate").performClick()
        await { vm.busy.value == null && vm.message.value?.contains("重新按送出") == true }
        assertNull(vm.duplicateShare.value); assertArrayEquals(edited, stateBytes())
        showReminder(); val current = vm.duplicateShare.value!!
        scenario!!.onActivity { vm.sendSeparateDuplicate(original) }
        assertSame(current, vm.duplicateShare.value)
        scenario!!.onActivity { vm.edit(incoming.id) { it.copy(images = it.images.map { image -> image.copy(role = "promotion") }, purpose = "reference") } }
        await { vm.editState.value.isEmpty() && store.batch(incoming.id).purpose == "reference" }
        val rolesEdited = stateBytes()
        ui.onNodeWithTag("duplicate-share-separate").performClick()
        await { vm.busy.value == null && vm.duplicateShare.value == null }
        assertArrayEquals(rolesEdited, stateBytes())
        assertEquals(0, postCalls.get()); assertNull(store.batch(incoming.id).frozen)
    }
    @Test fun candidateDeletionRefreshesTheChoiceAndDeletedHistoryCannotBeResurrected() = runBlocking {
        launch(); showReminder()
        val initial = vm.duplicateShare.value!!
        repo.deleteBatch(old.id)
        val deleted = store.batch(old.id)
        val before = stateBytes()
        ui.onNodeWithTag("duplicate-share-separate").performClick()
        await { vm.busy.value == null && vm.duplicateShare.value !== initial }
        assertEquals(0, postCalls.get()); assertArrayEquals(before, stateBytes())
        ui.onNodeWithText("已刪除 · 僅供查看").assertIsDisplayed()
        scenario!!.onActivity { vm.sendSeparateDuplicate(initial) }
        ui.onNodeWithTag("duplicate-candidate-${old.id}").performClick()
        ui.waitForIdle()
        assertEquals(old.id, vm.selected.value); assertEquals(deleted, store.batch(old.id))
        assertTrue(store.batch(old.id).deleted); assertNull(store.batch(incoming.id).frozen)
        assertEquals(0, postCalls.get()); assertArrayEquals(before, stateBytes())
    }
    @Test fun openingVoidedLedgerUsesOnlyGetAndDoesNotChangeTheNewDraft() {
        ledger.voided = true; ledger.ledgerRevision++
        store.update(old.id) { it.copy(draftId = ledger.draftId, ledgerId = ledger.entryId, ledgerStatus = "voided", draftStatus = "voided") }
        store.cacheEntry(ledger.entry())
        launch(); showReminder()
        ui.onNodeWithTag("duplicate-candidate-${old.id}").performClick()
        await { vm.busy.value == null && detailGets.get() == 1 }
        assertEquals(MobilePage.LedgerDetail.name, vm.destination.value)
        assertEquals(ledger.entryId, vm.selectedLedger.value)
        ui.onNodeWithText("已作廢").assertIsDisplayed()
        assertEquals(0, postCalls.get()); assertEquals(0, api.uploads.size)
        assertEquals("voided", store.batch(old.id).ledgerStatus)
        assertEquals(incoming, store.batch(incoming.id))
    }
    @Test fun unmatchedFirstSendContinuesNormallyWithoutADialog() = runBlocking {
        repo.edit(old.id) { it.copy(images = it.images.map { image -> image.copy(role = "promotion") }) }
        launch(); ui.onNodeWithTag("mobile-primary").performClick()
        await { vm.busy.value == null && api.submissions.size == 1 }
        ui.onNodeWithTag("duplicate-share-dialog").assertDoesNotExist()
        assertEquals(1, postCalls.get()); assertNotNull(store.batch(incoming.id).frozen)
    }
    @Test fun backBeforePreflightReturnsDoesNotShowTheOldPromptOnTheInbox() {
        launch()
        scenario!!.onActivity { vm.sync(incoming.id); vm.back() }
        await { vm.busy.value == null }
        assertEquals(MobilePage.Inbox.name, vm.destination.value)
        ui.onNodeWithTag("duplicate-share-dialog").assertDoesNotExist()
        assertNull(vm.duplicateShare.value); assertEquals(0, postCalls.get())
    }
    @Test @Config(qualifiers = "w320dp-h740dp-mdpi") fun narrowLargeFontLongListKeepsActionsAndLastCandidateReachable() = runBlocking {
        repeat(12) { imported("合成舊收件 ${it + 1}") }
        launch(fontScale = 1.5f); showReminder()
        ui.onNodeWithTag("duplicate-share-cancel").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        ui.onNodeWithTag("duplicate-share-separate").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        capture("duplicate-share-320-font150")
        ui.onNodeWithTag("duplicate-share-candidates").performScrollToNode(hasTestTag("duplicate-candidate-${old.id}"))
        ui.onNodeWithTag("duplicate-candidate-${old.id}").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        capture("duplicate-share-long-list-320-font150")
        ui.onNodeWithTag("duplicate-share-cancel").performClick()
        assertEquals(0, postCalls.get())
    }
    @Test @Config(qualifiers = "w740dp-h320dp-mdpi") fun shortLandscapeLargeTextAllowsScrollingAndCancel() = runBlocking {
        repeat(4) { imported("橫向合成候選 ${it + 1}") }
        launch(fontScale = 1.5f); showReminder()
        ui.onNodeWithTag("duplicate-share-cancel").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        ui.onNodeWithTag("duplicate-share-separate").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        ui.onNodeWithTag("duplicate-share-candidates").performScrollToNode(hasTestTag("duplicate-candidate-${old.id}"))
        ui.onNodeWithTag("duplicate-candidate-${old.id}").assertIsDisplayed()
        capture("duplicate-share-landscape-font150")
        ui.onNodeWithTag("duplicate-share-cancel").performClick()
        assertEquals(0, postCalls.get())
    }
}
