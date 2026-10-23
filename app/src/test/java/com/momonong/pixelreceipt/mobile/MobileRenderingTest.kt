package com.momonong.pixelreceipt.mobile

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.momonong.pixelreceipt.core.ui.theme.PixelReceiptTheme
import com.momonong.pixelreceipt.data.ingestion.ImageStore
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/** Real production Compose + native graphics. Synthetic receipt bytes; no account or model calls. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MobileRenderingTest {
    @get:Rule val ui = createEmptyComposeRule()
    private lateinit var scenario: ActivityScenario<ComponentActivity>
    private lateinit var directory: File
    private lateinit var images: ImageStore
    private lateinit var sample: MobileBatch
    private lateinit var allPhotos: List<MobileImage>
    @Before fun setup() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        directory = File(context.cacheDir, UUID.randomUUID().toString()).apply { mkdirs() }
        images = ImageStore(directory)
        val photos = (1..20).map { index ->
            val bitmap = Bitmap.createBitmap(160, 240, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap).apply { drawColor(Color.WHITE) }
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 10f }
            canvas.drawText("SYNTHETIC RECEIPT", 12f, 24f, paint)
            canvas.drawText("COMPOSE UI FIXTURE $index", 12f, 44f, paint)
            paint.textSize = 8f
            listOf("Receipt original bytes", "Photo order preserved", "No real transaction").forEachIndexed { row, text -> canvas.drawText(text, 12f, 78f + row * 18, paint) }
            paint.strokeWidth = 1f
            canvas.drawLine(12f, 145f, 148f, 145f, paint)
            val bytes = ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() }
            bitmap.recycle()
            val stored = images.copy({ ByteArrayInputStream(bytes) }, ImageStore.MAX_BATCH_BYTES)
            MobileImage(stored.hash, stored.mime, stored.bytes, when (index) { 2 -> "price_tag"; 3 -> "promotion"; else -> "receipt" }, if (index == 2) "牙膏價標，幫朋友買" else "")
        }
        allPhotos = photos
        sample = MobileBatch(UUID.randomUUID().toString(), createdAt = 1792573200000, images = photos.take(3), userNotes = "日用品採買，牙膏幫朋友買。", importComplete = true)
        shadowOf(context.packageManager).addActivityIfNotPresent(ComponentName(context, ComponentActivity::class.java))
        scenario = ActivityScenario.launch(Intent(context, ComponentActivity::class.java))
    }
    @After fun cleanup() { if (::scenario.isInitialized) scenario.close(); if (::directory.isInitialized) directory.deleteRecursively() }
    private fun mount(state: () -> MobileUiState, actions: MobileActions = MobileActions(), fontScale: Float = 1f, imeInsets: WindowInsets? = null) {
        scenario.onActivity { activity -> activity.setContent {
            PixelReceiptTheme(darkTheme = false, dynamicColor = false) {
                CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                    if (imeInsets == null) MobileContent(state(), images, actions)
                    else MobileContent(state(), images, actions, imeInsets)
                }
            }
        } }
        ui.waitForIdle()
    }
    private fun capture(name: String) {
        ui.waitForIdle()
        ui.waitUntil(5000) { ui.onAllNodesWithText("載入照片…").fetchSemanticsNodes().isEmpty() }
        ui.onNodeWithTag("mobile-primary").assertIsDisplayed()
        val bitmap = ui.onRoot().captureToImage().asAndroidBitmap()
        assertTrue(bitmap.width > 100 && bitmap.height > 100)
        val output = File(System.getProperty("pixelreceipt.screenshots", "../.receipt-lab/mobile-sync-20261022/screenshots"), "$name.png").apply { parentFile?.mkdirs() }
        output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
    private fun render(state: MobileUiState, name: String, fontScale: Float = 1f) { mount({ state }, fontScale = fontScale); capture(name) }
    private fun received(status: String, reference: Boolean = false, source: MobileBatch = sample): MobileBatch {
        val input = source.copy(purpose = if (reference) "reference" else "purchase",
            images = if (reference) source.images.mapIndexed { index, image -> image.copy(role = listOf("menu", "price_tag", "promotion")[index]) } else source.images)
        return input.copy(frozen = freezeIntake(input), uploadedHashes = input.images.map { it.hash }, intakeId = UUID.randomUUID().toString(),
            status = status, draftId = if (status == "succeeded" && !reference) UUID.randomUUID().toString() else null)
    }
    @Test fun inboxRendersProductionCompose() {
        render(MobileUiState(batches = listOf(sample), signedIn = true), "inbox-390")
        ui.onNodeWithText("中央收件匣", substring = true).assertExists()
        ui.onNodeWithText("尚未送出").assertExists()
    }
    @Test fun emptyInboxKeepsOneVisiblePrimaryAction() {
        render(MobileUiState(), "inbox-empty-390")
        ui.onNodeWithText("把這筆消費帶回帳本").assertExists()
        ui.onNodeWithText("新增收件・選照片").assertIsDisplayed()
    }
    @Test fun mixedInboxUsesStoredSyntheticThumbnailsAndDistinctStatuses() {
        val editable = sample.copy(id = UUID.randomUUID().toString(), userNotes = "合成收件：尚未送出")
        val serverBatches = listOf("processing", "succeeded", "unknown", "failed").map { status ->
            received(status, source = sample.copy(id = UUID.randomUUID().toString(), userNotes = "合成收件：${statusLabel(status)}"))
        }
        val batches = listOf(editable) + serverBatches
        render(MobileUiState(batches = batches, signedIn = true), "inbox-mixed-390")
        batches.forEach { batch ->
            ui.onNodeWithTag("mobile-inbox").performScrollToNode(hasTestTag("mobile-batch-${batch.id}"))
            ui.onNodeWithTag("mobile-batch-${batch.id}").assertIsDisplayed()
            ui.onNodeWithText(mobileStatus(batch)).assertExists()
        }
    }
    @Test fun editorShowsThreeRolesAndAdvanceNotesWithoutHidingSend() {
        render(MobileUiState(batches = listOf(sample), selectedId = sample.id, page = MobilePage.Detail, signedIn = true), "editor-390")
        ui.onNodeWithTag("mobile-detail").performScrollToNode(hasTestTag("mobile-batch-notes"))
        ui.onNodeWithTag("mobile-batch-notes").assertTextContains("牙膏幫朋友買", substring = true)
        ui.onNodeWithText("送出 3 張照片").assertIsDisplayed()
        ui.onNodeWithTag("mobile-detail").performScrollToNode(hasTestTag("mobile-photo-edit-1"))
        ui.onNodeWithTag("mobile-photo-edit-1").performClick()
        ui.onNodeWithTag("mobile-photo-notes-1").performScrollTo().assertTextContains("牙膏價標", substring = true)
        capture("editor-price-tag-390")
    }
    @Test fun incompleteEmptyBatchCanPickPhotosIntoItsOriginalBatch() {
        val batch = sample.copy(images = emptyList(), importComplete = false)
        var picked: String? = null
        var synced: String? = null
        mount({ MobileUiState(batches = listOf(batch), selectedId = batch.id, page = MobilePage.Detail, signedIn = true) },
            MobileActions(pick = { picked = it }, sync = { synced = it }))
        capture("incomplete-empty-390")
        ui.onNodeWithText("補齊照片").assertIsDisplayed()
        ui.onNodeWithTag("mobile-primary").assertIsEnabled().performClick()
        assertEquals(batch.id, picked)
        assertNull(synced)
    }
    @Test fun incompletePriceTagBatchCanPickPhotosBeforeReceiptValidation() {
        val batch = sample.copy(images = listOf(sample.images[1]), importComplete = false)
        var picked: String? = null
        var synced: String? = null
        mount({ MobileUiState(batches = listOf(batch), selectedId = batch.id, page = MobilePage.Detail, signedIn = true) },
            MobileActions(pick = { picked = it }, sync = { synced = it }))
        capture("incomplete-price-tag-390")
        ui.onNodeWithText("補齊照片").assertIsDisplayed()
        ui.onNodeWithTag("mobile-primary").assertIsEnabled().performClick()
        assertEquals(batch.id, picked)
        assertNull(synced)
    }
    @Test fun busyIncompleteBatchAndCompletePurchaseWithoutReceiptStayDisabled() {
        val batch = sample.copy(images = listOf(sample.images[1]), importComplete = false)
        val state = mutableStateOf(MobileUiState(batches = listOf(batch), selectedId = batch.id,
            page = MobilePage.Detail, signedIn = true, busy = "正在保存原圖…"))
        var picked: String? = null
        var synced: String? = null
        mount({ state.value }, MobileActions(pick = { picked = it }, sync = { synced = it }))
        ui.onNodeWithTag("mobile-primary").assertIsNotEnabled().performTouchInput { click() }
        ui.runOnIdle { state.value = state.value.copy(busy = null, batches = listOf(batch.copy(importComplete = true))) }
        ui.onNodeWithText("送出 1 張照片").assertIsDisplayed()
        ui.onNodeWithTag("mobile-primary").assertIsNotEnabled().performTouchInput { click() }
        ui.onNodeWithText("消費需要至少一張收據", substring = true).assertExists()
        assertNull(picked)
        assertNull(synced)
    }
    @Test fun failedSaveRetainsCurrentTextAndOffersAnExplicitPersistenceRetry() {
        val draft = sample.copy(userNotes = "尚未保存的新文字，牙膏幫朋友買")
        var retried: String? = null
        var synced: String? = null
        val state = MobileUiState(batches = listOf(draft), selectedId = draft.id, page = MobilePage.Detail,
            signedIn = true, writable = false, edits = mapOf(draft.id to MobileEdit(draft, 1, sample.revision, "尚未保存：儲存空間不足，請重試。")))
        mount({ state }, MobileActions(retrySave = { retried = it }, sync = { synced = it }))
        ui.onNodeWithTag("mobile-detail").performScrollToNode(hasTestTag("mobile-batch-notes"))
        ui.onNodeWithTag("mobile-batch-notes").assertTextContains(draft.userNotes).assertIsNotEnabled()
        capture("failed-save-retains-text-390")
        ui.onNodeWithTag("mobile-primary").assertIsEnabled().performClick()
        assertEquals(draft.id, retried)
        assertNull(synced)
    }
    private fun notesKeepFocusAcrossEachPendingSaveAndStoreAck(photo: Boolean) {
        val storeRoot = File(directory, "note-persistence")
        val store = MobileStore(storeRoot)
        store.create(sample.id, "")
        var persisted = store.update(sample.id) { sample.copy(userNotes = "") }
        val edits = MobileEdits(SavedStateHandle())
        val state = mutableStateOf(MobileUiState(batches = listOf(persisted), selectedId = persisted.id,
            page = MobilePage.Detail, signedIn = true))
        fun publish() {
            state.value = state.value.copy(batches = listOf(edits.state.value[persisted.id]?.batch ?: persisted), edits = edits.state.value)
        }
        mount({ state.value }, MobileActions(edit = { id, change ->
            assertEquals(persisted.id, id)
            edits.edit(persisted, change)
            publish()
        }))
        val list = ui.onNodeWithTag("mobile-detail")
        if (photo) {
            list.performScrollToNode(hasTestTag("mobile-photo-edit-0"))
            ui.onNodeWithTag("mobile-photo-edit-0").performClick()
        } else list.performScrollToNode(hasTestTag("mobile-batch-notes"))
        val field = ui.onNodeWithTag(if (photo) "mobile-photo-notes-0" else "mobile-batch-notes")
        field.performScrollTo().performClick().assertIsFocused()
        val input = "one complete note 逐字保存"
        input.forEachIndexed { index, character ->
            val expected = input.take(index + 1)
            // One IME event per character; never refocus or replace the complete field value.
            field.performTextInput(character.toString())
            field.assertIsFocused().assertTextContains(expected, substring = false)
            ui.runOnIdle { assertTrue(edits.pending(persisted.id)) }
            ui.runOnIdle {
                runBlocking {
                    edits.persist(persisted.id) { value, revision ->
                        store.update(value.id, revision) { value }.also { persisted = it }
                    }
                }
                publish() // Remove the saving notice only after the real AtomicFile write acknowledges it.
                assertFalse(edits.pending(persisted.id))
            }
            field.assertIsFocused().assertTextContains(expected, substring = false)
            val reopened = MobileStore(storeRoot).batch(persisted.id)
            assertEquals(expected, if (photo) reopened.images.first().notes else reopened.userNotes)
        }
    }
    @Test fun batchNoteKeepsFocusAndEveryCharacterAcrossSavingNoticeAndDiskAck() =
        notesKeepFocusAcrossEachPendingSaveAndStoreAck(photo = false)
    @Test fun photoNoteKeepsFocusAndEveryCharacterAcrossSavingNoticeAndDiskAck() =
        notesKeepFocusAcrossEachPendingSaveAndStoreAck(photo = true)
    @Test @Config(qualifiers = "w840dp-h432dp-mdpi") fun landscapeImeKeepsAVisibleEditableViewportAboveTheAction() {
        val state = mutableStateOf(MobileUiState(batches = listOf(sample), selectedId = sample.id,
            page = MobilePage.Detail, signedIn = true))
        mount({ state.value }, MobileActions(edit = { id, change ->
            state.value = state.value.copy(batches = state.value.batches.map { if (it.id == id) change(it) else it })
        }), imeInsets = WindowInsets(bottom = 240))
        val list = ui.onNodeWithTag("mobile-detail")
        val primary = ui.onNodeWithTag("mobile-primary")
        ui.onNodeWithTag("mobile-back").assertDoesNotExist()
        primary.assertIsDisplayed().assertIsEnabled()
        list.performScrollToNode(hasTestTag("mobile-batch-notes"))
        val field = ui.onNodeWithTag("mobile-batch-notes")
        field.performScrollTo().assertIsDisplayed().performClick()
        field.performTextInput("IME仍可輸入")
        field.assertIsFocused().assertTextContains("IME仍可輸入", substring = true)
        val viewport = list.fetchSemanticsNode().boundsInRoot
        val visibleField = field.fetchSemanticsNode().boundsInRoot
        val action = primary.fetchSemanticsNode().boundsInRoot
        assertTrue("The keyboard must leave a usable scroll viewport", viewport.height >= 72f)
        assertTrue("The focused text field must have visible area", visibleField.width > 0f && visibleField.height > 0f)
        assertTrue("The action must stay above the simulated keyboard", action.bottom <= 432f - 240f)
        assertTrue("The action must not cover the editor viewport", viewport.bottom <= action.top)
        primary.assertIsDisplayed()
    }
    @Test @Config(qualifiers = "w840dp-h300dp-mdpi") fun smallHeightWithoutImeRetainsTheHeaderAndVisibleAction() {
        mount({ MobileUiState(batches = listOf(sample), selectedId = sample.id, page = MobilePage.Detail, signedIn = true) },
            imeInsets = WindowInsets(0, 0, 0, 0))
        ui.onNodeWithTag("mobile-back").assertIsDisplayed()
        ui.onNodeWithTag("mobile-primary").assertIsDisplayed()
        assertTrue(ui.onNodeWithTag("mobile-detail").fetchSemanticsNode().boundsInRoot.height > 0f)
    }
    @Test fun interruptedImportShowsOriginalAndNewSelectionBeforeExplicitMembershipConfirmation() {
        val batch = sample.copy(importComplete = false, importReview = MobileImportReview(expectedCount = 4,
            originalSlots = listOf(sample.images[0].hash, null, null, null),
            previousHashes = listOf(sample.images[0].hash), selectedHashes = listOf(sample.images[2].hash, sample.images[0].hash)))
        var confirmed: String? = null
        var synced: String? = null
        mount({ MobileUiState(batches = listOf(batch), selectedId = batch.id, page = MobilePage.Detail, signedIn = true) },
            MobileActions(confirmImport = { confirmed = it }, sync = { synced = it }))
        capture("import-review-390")
        ui.onNodeWithText("原選 4 張，目前保存 3 張。").assertExists()
        ui.onNodeWithText("這次選取順序：3 → 1").assertExists()
        ui.onNodeWithText("先前已保存 1 張；這次新增 2 張。").assertExists()
        ui.onNodeWithTag("mobile-confirm-import").performScrollTo().assertIsEnabled().performClick()
        assertEquals(batch.id, confirmed)
        assertNull(synced)
        ui.onNodeWithTag("mobile-detail").performScrollToNode(hasTestTag("mobile-photo-edit-1"))
        ui.onNodeWithTag("mobile-photo-edit-1").performClick()
        ui.onNodeWithTag("mobile-photo-up-1").performScrollTo().assertIsEnabled()
        ui.onNodeWithTag("mobile-photo-remove-1").assertIsEnabled()
        capture("import-review-member-controls-390")
    }
    @Test fun readOnlyInboxCanOpenSavedBatchesAndRetryStorageWithoutCreatingANewBatch() {
        var selected: String? = null
        var retried = false
        var picked = false
        mount({ MobileUiState(batches = listOf(sample), writable = false) }, MobileActions(
            select = { selected = it }, retrySave = { retried = true }, pick = { picked = true }))
        capture("read-only-inbox-390")
        ui.onNodeWithTag("mobile-batch-${sample.id}").performClick()
        assertEquals(sample.id, selected)
        ui.onNodeWithTag("mobile-primary").assertIsEnabled().performClick()
        assertTrue(retried)
        assertFalse(picked)
    }
    @Test fun settingsUnauthorizedKeepsNotesAndPasswordIsMaskedThenCleared() {
        val state = mutableStateOf(MobileUiState(batches = listOf(sample), selectedId = sample.id, page = MobilePage.Settings,
            message = "登入已失效；照片、說明與原請求已保留。"))
        var submitted: String? = null
        mount({ state.value }, MobileActions(login = { submitted = it }))
        ui.onNodeWithTag("mobile-password").performScrollTo().performTextInput("fixture-only-password")
        capture("settings-login-401-390")
        ui.onNodeWithTag("mobile-primary").performClick()
        assertEquals("fixture-only-password", submitted)
        ui.onNodeWithTag("mobile-password").assertTextEquals("")
        assertEquals(sample.userNotes, state.value.batch!!.userNotes)
    }
    @Test fun processingAndSucceededPurchaseKeepServerAndLedgerStatesDistinct() {
        val processing = received("processing")
        val state = mutableStateOf(MobileUiState(batches = listOf(processing), selectedId = processing.id, page = MobilePage.Detail, signedIn = true))
        var dashboard: String? = null
        mount({ state.value }, MobileActions(dashboard = { dashboard = it }))
        capture("processing-390")
        ui.onNodeWithText("更新進度").assertIsDisplayed()
        val done = processing.copy(status = "succeeded", draftId = UUID.randomUUID().toString())
        ui.runOnIdle { state.value = state.value.copy(batches = listOf(done)) }
        capture("succeeded-purchase-390")
        ui.onNodeWithText("待核對").assertExists()
        ui.onNodeWithText("已入帳").assertDoesNotExist()
        ui.onNodeWithTag("mobile-primary").performClick()
        assertEquals(done.draftId, dashboard)
    }
    @Test fun referenceSuccessNeverClaimsConsumptionIsConfirmed() {
        val reference = received("succeeded", reference = true)
        render(MobileUiState(batches = listOf(reference), selectedId = reference.id, page = MobilePage.Detail, signedIn = true), "succeeded-reference-390")
        ui.onNodeWithText("開啟中央帳本").assertIsDisplayed()
        ui.onNodeWithText("成果待查看").assertExists()
        ui.onNodeWithText("參考候選已保存。這批資料不會建立消費帳目。").assertExists()
        ui.onNodeWithText("已入帳").assertDoesNotExist()
    }
    @Test fun interruptedUploadKeepsSnapshotReadOnlyAndOffersOnlyManualResume() {
        val batch = sample.copy(frozen = freezeIntake(sample), uploadedHashes = listOf(sample.images[0].hash))
        val state = mutableStateOf(MobileUiState(batches = listOf(batch), selectedId = batch.id, page = MobilePage.Detail,
            signedIn = true, busy = "正在同步這筆收件…"))
        var resumed: String? = null
        mount({ state.value }, MobileActions(stop = { state.value = state.value.copy(busy = null, message = "同步已停止；照片、說明與原請求已保留。") }, sync = { resumed = it }))
        capture("uploading-390")
        ui.onNodeWithTag("mobile-primary").assertIsNotEnabled()
        ui.onNodeWithTag("mobile-stop").performClick()
        ui.onNodeWithTag("mobile-batch-notes").assertDoesNotExist()
        capture("retry-saved-390")
        ui.onNodeWithTag("mobile-primary").performClick()
        assertEquals(batch.id, resumed)
        assertEquals(batch.frozen, state.value.batch!!.frozen)
    }
    @Test fun failedServerJobIsNotOfferedAsANewRecognition() {
        val failed = received("failed").copy(message = "辨識來源暫時無法完成；原工作已保留。")
        render(MobileUiState(batches = listOf(failed), selectedId = failed.id, page = MobilePage.Detail, signedIn = true), "failed-390")
        ui.onNodeWithText("更新進度").assertIsDisplayed()
        ui.onNodeWithText("送出 3 張照片").assertDoesNotExist()
    }
    @Test fun unknownAndUnavailableOnlySyncTheOriginalFrozenWork() {
        val batch = received("unknown", source = sample.copy(engine = "local"))
        val state = mutableStateOf(MobileUiState(batches = listOf(batch), selectedId = batch.id, page = MobilePage.Detail, signedIn = true,
            engines = mapOf("local" to EngineAvailability(false, "recognition_not_configured"))))
        val synced = mutableListOf<String>()
        var picked: String? = null
        mount({ state.value }, MobileActions(sync = { synced += it }, pick = { picked = it }))
        capture("unknown-390")
        ui.onNodeWithText("更新進度").assertIsDisplayed()
        ui.onNodeWithTag("mobile-primary").assertIsEnabled().performClick()
        ui.runOnIdle { state.value = state.value.copy(batches = listOf(batch.copy(status = "unavailable"))) }
        capture("unavailable-390")
        ui.onNodeWithText("更新進度").assertIsDisplayed()
        ui.onNodeWithTag("mobile-primary").assertIsEnabled().performClick()
        assertEquals(listOf(batch.id, batch.id), synced)
        assertEquals(batch.intakeId, state.value.batch!!.intakeId)
        assertEquals(batch.frozen, state.value.batch!!.frozen)
        assertNull(picked)
    }
    @Test fun frozenPhotoLongDescriptionRendersEveryLineAndItsEndCanBeViewed() {
        val marker = "最後一行 END"
        val notes = (1..36).joinToString("\n") { "合成備註第 $it 行" } + "\n$marker"
        val input = sample.copy(images = sample.images.mapIndexed { index, image -> if (index == 0) image.copy(notes = notes) else image })
        val batch = input.copy(frozen = freezeIntake(input))
        mount({ MobileUiState(batches = listOf(batch), selectedId = batch.id, page = MobilePage.Detail, signedIn = true) })
        val list = ui.onNodeWithTag("mobile-detail")
        list.performScrollToNode(hasTestTag("mobile-photo-edit-0"))
        ui.onNodeWithTag("mobile-photo-edit-0").performClick()
        val description = ui.onNodeWithTag("mobile-photo-description-0")
        val layouts = mutableListOf<TextLayoutResult>()
        description.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertTrue("Expanded description must lay out more than five lines", layout.lineCount > 5)
        assertFalse("Expanded description must not truncate text: size=${layout.size}, constraints=${layout.layoutInput.constraints}, lines=${layout.lineCount}, paragraphHeight=${layout.multiParagraph.height}, overflowHeight=${layout.didOverflowHeight}, overflowWidth=${layout.didOverflowWidth}", layout.hasVisualOverflow)
        assertTrue(layout.layoutInput.text.text.endsWith(marker))
        val lastLine = layout.lineCount - 1
        fun finalLineIsVisible(): Boolean {
            val text = description.getUnclippedBoundsInRoot()
            val viewport = list.getUnclippedBoundsInRoot()
            // mount() fixes density to 1, so layout pixels and root dp values use the same scale.
            return text.top.value + layout.getLineTop(lastLine) >= viewport.top.value &&
                text.top.value + layout.getLineBottom(lastLine) <= viewport.bottom.value
        }
        var attempts = 0
        while (!finalLineIsVisible() && attempts++ < 8) {
            val text = description.getUnclippedBoundsInRoot()
            val viewport = list.getUnclippedBoundsInRoot()
            val lastTop = text.top.value + layout.getLineTop(lastLine)
            val lastBottom = text.top.value + layout.getLineBottom(lastLine)
            val delta = if (lastBottom > viewport.bottom.value) lastBottom - viewport.bottom.value + 12f else lastTop - viewport.top.value - 12f
            val limit = (viewport.bottom.value - viewport.top.value) * 0.8f
            list.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, delta.coerceIn(-limit, limit)) }
            ui.waitForIdle()
        }
        assertTrue("Final rendered line must be fully inside the detail viewport", finalLineIsVisible())
        description.assertIsDisplayed()
        capture("frozen-long-note-end-390")
    }
    @Test fun twentyPhotosRemainSendableAtTheEndOfTheList() {
        val batch = sample.copy(images = allPhotos)
        var sent: String? = null
        mount({ MobileUiState(batches = listOf(batch), selectedId = batch.id, page = MobilePage.Detail, signedIn = true) }, MobileActions(sync = { sent = it }))
        ui.onNodeWithTag("mobile-detail").performScrollToNode(hasTestTag("mobile-photo-edit-19"))
        ui.onNodeWithText("送出 20 張照片").assertIsDisplayed()
        ui.onNodeWithTag("mobile-primary").performClick()
        assertEquals(batch.id, sent)
        capture("editor-20-photos-end-390")
    }
    @Test fun returnFromNthBatchRestoresInboxScroll() {
        val batches = (1..40).map { sample.copy(id = UUID.randomUUID().toString(), userNotes = "第 $it 筆合成收件") }
        val target = batches[30]
        val state = mutableStateOf(MobileUiState(batches = batches, signedIn = true))
        mount({ state.value }, MobileActions(select = { id -> state.value = state.value.copy(selectedId = id, page = MobilePage.Detail) },
            back = { state.value = state.value.copy(selectedId = null, page = MobilePage.Inbox) }))
        ui.onNodeWithTag("mobile-inbox").performScrollToNode(hasTestTag("mobile-batch-${target.id}"))
        ui.onNodeWithTag("mobile-batch-${target.id}").performClick()
        ui.onNodeWithTag("mobile-back").performClick()
        ui.onNodeWithTag("mobile-batch-${target.id}").assertIsDisplayed()
        capture("inbox-restored-scroll-390")
    }
    @Test @Config(qualifiers = "w320dp-h740dp-mdpi") fun narrowLargeTextKeepsActionAndEditableNotesVisible() {
        render(MobileUiState(batches = listOf(sample), selectedId = sample.id, page = MobilePage.Detail, signedIn = true), "editor-320-font150", 1.5f)
        ui.onNodeWithTag("mobile-detail").performScrollToNode(hasTestTag("mobile-batch-notes"))
        ui.onNodeWithTag("mobile-batch-notes").performScrollTo().performClick().performTextInput("補充說明")
        ui.onNodeWithTag("mobile-primary").assertIsDisplayed()
        capture("editor-320-font150-focused")
    }
    @Test @Config(qualifiers = "w320dp-h740dp-mdpi") fun narrowLargeTextInboxKeepsBatchSelectionUsable() {
        var selected: String? = null
        mount({ MobileUiState(batches = listOf(sample), signedIn = true) }, MobileActions(select = { selected = it }), fontScale = 1.5f)
        capture("inbox-320-font150")
        ui.onNodeWithTag("mobile-inbox").performScrollToNode(hasTestTag("mobile-batch-${sample.id}"))
        ui.onNodeWithTag("mobile-batch-${sample.id}").assertIsDisplayed().performClick()
        assertEquals(sample.id, selected)
    }
    @Test @Config(qualifiers = "w320dp-h740dp-mdpi") fun narrowLargeTextSettingsKeepsPasswordAndProvidersUsable() {
        val state = mutableStateOf(MobileUiState(batches = listOf(sample), selectedId = sample.id, page = MobilePage.Settings,
            engines = mapOf("broker" to EngineAvailability(true, null), "local" to EngineAvailability(true, null))))
        var submitted: String? = null
        var refreshed = false
        mount({ state.value }, MobileActions(login = { submitted = it; state.value = state.value.copy(signedIn = true) },
            refresh = { refreshed = true }), fontScale = 1.5f)
        capture("settings-320-font150")
        ui.onNodeWithTag("mobile-password").performScrollTo().assertIsDisplayed().performTextInput("fixture-only-password")
        capture("settings-320-font150-password")
        ui.onNodeWithTag("mobile-primary").assertIsEnabled().performClick()
        assertEquals("fixture-only-password", submitted)
        ui.onNodeWithTag("mobile-password").assertDoesNotExist()
        ui.onNodeWithTag("mobile-settings-page").performScrollToNode(hasText("API Quota Broker"))
        ui.onNodeWithText("API Quota Broker").performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("RTX 4090").performScrollTo().assertIsDisplayed()
        capture("settings-320-font150-providers")
        ui.onNodeWithTag("mobile-refresh-engines").performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
        assertTrue(refreshed)
        ui.onNodeWithTag("mobile-primary").assertIsDisplayed()
    }
    @Test @Config(qualifiers = "w840dp-h900dp-mdpi") fun expandedSettingsShowsRealAvailabilityAndLegacyEntry() {
        val state = MobileUiState(signedIn = true, page = MobilePage.Settings,
            engines = mapOf("broker" to EngineAvailability(true, null), "local" to EngineAvailability(false, "recognition_not_configured")))
        render(state, "settings-840")
        ui.onNodeWithText("中央尚未啟用").assertExists()
        ui.onNodeWithTag("legacy-ledger-entry").performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("本機帳本（此 App）").assertIsDisplayed()
        ui.onNodeWithText("此 App 的本機帳本與中央帳本分開保存，不會自動匯入或上傳資料。").assertIsDisplayed()
        capture("settings-local-ledger-840")
    }
    @Test fun signedInSettingsShowsAvailableEnginesWithoutPasswordInput() {
        val state = MobileUiState(batches = listOf(sample), selectedId = sample.id, signedIn = true, page = MobilePage.Settings,
            engines = mapOf("broker" to EngineAvailability(true, null), "local" to EngineAvailability(true, null)))
        render(state, "settings-signed-in-390")
        ui.onNodeWithTag("mobile-password").assertDoesNotExist()
        ui.onNodeWithTag("mobile-logout").assertIsDisplayed()
        ui.onNodeWithTag("mobile-settings-page").performScrollToNode(hasTestTag("mobile-refresh-engines"))
        ui.onNodeWithTag("mobile-refresh-engines").assertIsEnabled()
        ui.onAllNodesWithText("可使用").assertCountEquals(2)
    }
}
