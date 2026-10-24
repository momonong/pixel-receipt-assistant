package com.momonong.pixelreceipt.mobile

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.google.gson.JsonArray
import com.google.gson.JsonObject
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReceiptActionsRenderingTest {
    @get:Rule val ui = createEmptyComposeRule()
    private lateinit var scenario: ActivityScenario<ComponentActivity>
    private lateinit var directory: File
    private lateinit var images: ImageStore
    private lateinit var batch: MobileBatch
    private lateinit var ledger: String
    @Before fun setup() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        directory = File(context.cacheDir, UUID.randomUUID().toString()).apply { mkdirs() }; images = ImageStore(directory)
        val bitmap = Bitmap.createBitmap(200, 340, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap); canvas.drawColor(android.graphics.Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.BLACK; textSize = 14f }
        listOf("SYNTHETIC RECEIPT", "2026-10-23", "TOOTHPASTE 2 x 90", "TOTAL 180", "Included discount 20").forEachIndexed { index, text -> canvas.drawText(text, 12f, 34f + index * 35, paint) }
        val bytes = ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() }; bitmap.recycle()
        val image = images.copy({ ByteArrayInputStream(bytes) }, ImageStore.MAX_BATCH_BYTES)
        val remote = jsonObject(nativeDraft()).apply { add("evidence", JsonArray().apply { add(JsonObject().apply { addProperty("sha256", image.hash); add("pages", JsonArray().apply { add(1) }) }) }) }
        val review = createReview(remote.toString()).copy(savedEditRevision = 0)
        batch = MobileBatch(UUID.randomUUID().toString(), images = listOf(MobileImage(image.hash, image.mime, image.bytes)), userNotes = "牙膏若為代買，請調整用途。", importComplete = true, intakeId = UUID.randomUUID().toString(), status = "succeeded", draftId = remote.text("id"), draftStatus = "needs_review", review = review)
        val ledgerId = UUID.randomUUID().toString()
        remote.addProperty("status", "confirmed"); remote.addProperty("ledger_id", ledgerId)
        remote.add("review_document", jsonObject(prepareReview(review, true).body!!).apply { remove("candidate"); remove("expected_revision"); remove("review_note") })
        ledger = JsonObject().apply { add("entries", JsonArray().apply { add(JsonObject().apply { addProperty("id", ledgerId); addProperty("draft_id", remote.text("id")); addProperty("revision", 1); addProperty("status", "active"); add("snapshot", remote); add("events", JsonArray()) }) }); addProperty("total_count", 1) }.toString()
        shadowOf(context.packageManager).addActivityIfNotPresent(ComponentName(context, ComponentActivity::class.java))
        scenario = ActivityScenario.launch(Intent(context, ComponentActivity::class.java))
    }
    @After fun cleanup() { if (::scenario.isInitialized) scenario.close(); if (::directory.isInitialized) directory.deleteRecursively() }
    @Suppress("DEPRECATION")
    private fun mount(state: () -> MobileUiState, actions: MobileActions = MobileActions(), font: Float = 1f) {
        scenario.onActivity { activity ->
            val configuration = android.content.res.Configuration(activity.resources.configuration).apply { fontScale = font }
            activity.resources.updateConfiguration(configuration, activity.resources.displayMetrics)
            activity.setContent { PixelReceiptTheme(darkTheme = false, dynamicColor = false) { CompositionLocalProvider(LocalDensity provides Density(1f, font)) { MobileContent(state(), images, actions) } } } }
        ui.waitForIdle()
    }
    private fun capture(name: String) {
        ui.waitForIdle(); ui.onNodeWithTag("mobile-primary").assertIsDisplayed()
        val dialog = ui.onAllNodesWithTag("receipt-action-dialog").fetchSemanticsNodes().isNotEmpty()
        val image = (if (dialog) ui.onNodeWithTag("receipt-action-dialog") else ui.onRoot()).captureToImage().asAndroidBitmap()
        val file = File(System.getProperty("pixelreceipt.screenshots"), "$name.png"); file.parentFile!!.mkdirs()
        file.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
    private fun state(page: MobilePage = MobilePage.Review) = MobileUiState(batches = listOf(batch), selectedId = batch.id, page = page, signedIn = true, reviewer = true, ledgerJson = ledger, selectedLedgerId = jsonObject(ledger).array("entries")[0].asJsonObject.text("id"))
    @Test fun cancelDeleteAndConfirmDeleteAreDistinctAndKeepTheInboxTab() {
        val current = mutableStateOf(state(MobilePage.Inbox))
        var deletes = 0
        mount({ current.value }, MobileActions(deleteBatch = { id, reason ->
            assertEquals(batch.id, id); assertEquals("未註明", reason); deletes++
            current.value = current.value.copy(batches = listOf(batch.copy(deletedAt = 1L, status = "deleted")), message = "收據已刪除")
        }))
        ui.onNodeWithTag("receipt-more-${batch.id}").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
        ui.onNodeWithTag("receipt-action-${batch.id}").performClick()
        ui.onNodeWithText("刪除收據？").assertIsDisplayed(); capture("receipt-delete-dialog-390")
        ui.onNodeWithTag("receipt-action-cancel").performClick()
        assertEquals(0, deletes); ui.onNodeWithTag("mobile-batch-${batch.id}").assertIsDisplayed()
        ui.onNodeWithTag("receipt-more-${batch.id}").performClick(); ui.onNodeWithTag("receipt-action-${batch.id}").performClick()
        ui.onNodeWithTag("receipt-action-confirm").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, deletes); ui.onNodeWithTag("mobile-inbox").assertExists(); ui.onNodeWithTag("mobile-batch-${batch.id}").assertDoesNotExist()
        capture("receipt-deleted-inbox-390")
    }
    @Test fun voidConfirmationKeepsLedgerAndSeparatesActiveSummaryFromVoidedHistory() {
        val overview = """{"scope":"active_confirmed_ledger","receipt_count":1,"personal_yuan":180,"printed_total_yuan":180}"""
        val current = mutableStateOf(state(MobilePage.Ledger).copy(overviewJson = overview))
        val id = current.value.selectedLedgerId!!
        var voids = 0
        mount({ current.value }, MobileActions(voidEntry = { target, reason ->
            assertEquals(id, target); assertEquals("未註明", reason); voids++
            val changed = jsonObject(ledger).apply { array("entries")[0].asJsonObject.addProperty("status", "voided") }.toString()
            current.value = current.value.copy(ledgerJson = changed, overviewJson = """{"scope":"active_confirmed_ledger","receipt_count":0,"personal_yuan":0,"printed_total_yuan":0}""", message = "帳目已作廢")
        }))
        ui.onNodeWithTag("native-ledger").performScrollToNode(hasTestTag("receipt-more-$id"))
        ui.onNodeWithTag("receipt-more-$id").performClick(); ui.onNodeWithTag("receipt-action-$id").performClick()
        ui.onNodeWithText("作廢帳目？").assertIsDisplayed(); ui.onNodeWithText("未註明").assertIsDisplayed(); capture("receipt-void-dialog-390")
        ui.onNodeWithTag("receipt-action-confirm").performClick()
        assertEquals(1, voids); ui.onNodeWithTag("native-ledger").assertExists()
        ui.onNodeWithTag("ledger-$id").assertDoesNotExist()
        ui.onNodeWithTag("native-ledger").performScrollToNode(hasTestTag("ledger-show-voided")); ui.onNodeWithTag("ledger-show-voided").performClick()
        ui.onNodeWithTag("native-ledger").performScrollToNode(hasText("已作廢")); ui.onNodeWithText("已作廢").assertIsDisplayed()
        capture("receipt-void-history-390")
    }
    @Test @Config(qualifiers = "w320dp-h740dp-mdpi") fun narrowLargeTextDialogKeepsCancelAndConfirmAccessible() {
        mount({ state(MobilePage.Detail) }, font = 1.5f)
        ui.onNodeWithTag("receipt-more-${batch.id}").performClick(); ui.onNodeWithTag("receipt-action-${batch.id}").performClick()
        ui.onNodeWithTag("receipt-action-cancel").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        ui.onNodeWithTag("receipt-action-confirm").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        capture("receipt-delete-dialog-320-font150")
        ui.onNodeWithTag("receipt-action-cancel").performClick()
        capture("receipt-detail-320-font150")
    }
    @Test @Config(qualifiers = "w320dp-h740dp-mdpi") fun narrowLargeTextVoidDialogWrapsReasonsAndKeepsBothActionsVisible() {
        mount({ state(MobilePage.LedgerDetail) }, font = 1.5f)
        val id = state(MobilePage.LedgerDetail).selectedLedgerId!!
        ui.onNodeWithTag("receipt-more-$id").performClick(); ui.onNodeWithTag("receipt-action-$id").performClick()
        ui.onNodeWithTag("receipt-action-cancel").assertIsDisplayed()
        ui.onNodeWithTag("receipt-action-confirm").assertIsDisplayed()
        ui.onNodeWithText("未註明").assertIsDisplayed()
        capture("receipt-void-dialog-320-font150")
    }
    @Test fun retainedDeletedSelectionDoesNotReplaceTheInboxOrSettingsPrimaryAction() {
        val current = mutableStateOf(state(MobilePage.Inbox).copy(batches = listOf(batch.copy(deletedAt = 1, status = "deleted"))))
        var picks = 0; var logins = 0
        mount({ current.value }, MobileActions(pick = { picks++ }, login = { logins++ }))
        ui.onNodeWithText("新增收件・選照片").assertIsDisplayed().performClick()
        assertEquals(1, picks)
        ui.runOnIdle { current.value = current.value.copy(page = MobilePage.Settings, signedIn = false, reviewer = false) }
        ui.onNodeWithText("登入此裝置").assertIsDisplayed()
        assertEquals(0, logins)
    }
    @Test fun unknownMutationSurvivesUiRecreationAndNeverOffersAnUnsafeConfirm() {
        val attempt = ReceiptMutation("intake_delete", batch.intakeId!!, batch.id, UUID.randomUUID().toString(), """{"expected_draft_revision":1,"reason":"未註明"}""")
        var retries = 0; var confirms = 0
        mount({ state().copy(mutations = listOf(attempt)) }, MobileActions(deleteBatch = { _, _ -> retries++ }, confirmReview = { confirms++ }))
        ui.onNodeWithTag("mobile-primary").assertIsDisplayed().performClick()
        assertEquals(1, retries); assertEquals(0, confirms)
        ui.onNodeWithTag("review-header-edit").assertIsNotEnabled()
        capture("receipt-pending-delete-review-390")
    }
    @Test fun unknownVoidInReceiptDetailQueriesTheOriginalVoidRatherThanDeleting() {
        val id = UUID.randomUUID().toString()
        val completed = batch.copy(ledgerId = id, ledgerStatus = "active", draftStatus = "confirmed")
        val pending = ReceiptMutation("ledger_void", id, batch.id, UUID.randomUUID().toString(), """{"expected_revision":1,"reason":"未註明"}""")
        var voids = 0; var deletes = 0
        mount({ state(MobilePage.Detail).copy(batches = listOf(completed), mutations = listOf(pending)) }, MobileActions(voidEntry = { target, _ -> assertEquals(id, target); voids++ }, deleteBatch = { _, _ -> deletes++ }))
        ui.onNodeWithText("查詢作廢結果").assertIsDisplayed().performClick()
        assertEquals(1, voids); assertEquals(0, deletes)
        capture("receipt-pending-void-detail-390")
    }
    @Test fun terminalVoidedProjectionStillExposesRecoveryForAnOlderPendingJournal() {
        val id = state(MobilePage.LedgerDetail).selectedLedgerId!!
        val voided = jsonObject(ledger).apply { array("entries")[0].asJsonObject.addProperty("status", "voided") }.toString()
        val pending = ReceiptMutation("ledger_void", id, null, UUID.randomUUID().toString(), """{"expected_revision":1,"reason":"未註明"}""")
        var retries = 0
        mount({ state(MobilePage.LedgerDetail).copy(batches = emptyList(), ledgerJson = voided, mutations = listOf(pending)) }, MobileActions(voidEntry = { target, _ -> assertEquals(id, target); retries++ }))
        ui.onNodeWithTag("receipt-more-$id").assertIsDisplayed().performClick()
        ui.onNodeWithText("查詢作廢結果").performClick()
        assertEquals(1, retries)
    }
    @Test fun frozenWithoutIdExplainsWhyDeletionIsBlockedAndOffersSafeCancel() {
        val frozen = batch.copy(intakeId = null, draftId = null, review = null, frozen = FrozenIntake(UUID.randomUUID().toString(), "{}", sha256("{}".toByteArray())))
        mount({ state(MobilePage.Detail).copy(batches = listOf(frozen)) })
        ui.onNodeWithTag("receipt-more-${batch.id}").performClick(); ui.onNodeWithTag("receipt-action-${batch.id}").performClick()
        ui.onNodeWithText("暫時無法刪除收據").assertIsDisplayed(); ui.onNodeWithTag("receipt-action-confirm").assertDoesNotExist()
        capture("receipt-delete-blocked-390")
    }
    @Test fun failedRefreshIsVisibleBesideTheCentralSummary() {
        mount({ state(MobilePage.Ledger).copy(overviewJson = """{"scope":"active_confirmed_ledger","receipt_count":1,"personal_yuan":180,"printed_total_yuan":180}""", accountingStale = true, accountingError = "操作已確認，帳本與統計尚未同步") })
        ui.onNodeWithText("操作已確認，帳本與統計尚未同步").assertIsDisplayed()
        capture("receipt-stale-overview-390")
    }
}
