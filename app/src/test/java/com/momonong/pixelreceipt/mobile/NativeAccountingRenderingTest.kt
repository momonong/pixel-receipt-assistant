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
class NativeAccountingRenderingTest {
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
    private fun mount(state: () -> MobileUiState, actions: MobileActions = MobileActions(), font: Float = 1f) {
        scenario.onActivity { it.setContent { PixelReceiptTheme(darkTheme = false, dynamicColor = false) { CompositionLocalProvider(LocalDensity provides Density(1f, font)) { MobileContent(state(), images, actions) } } } }
        ui.waitForIdle()
    }
    private fun capture(name: String) {
        ui.waitForIdle(); ui.onNodeWithTag("mobile-primary").assertIsDisplayed()
        val image = ui.onRoot().captureToImage().asAndroidBitmap()
        val file = File(System.getProperty("pixelreceipt.screenshots"), "$name.png"); file.parentFile!!.mkdirs()
        file.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
    private fun state(page: MobilePage = MobilePage.Review) = MobileUiState(batches = listOf(batch), selectedId = batch.id, page = page, signedIn = true, reviewer = true, ledgerJson = ledger, selectedLedgerId = jsonObject(ledger).array("entries")[0].asJsonObject.text("id"))
    @Test fun receiptFirstScreenIsReadableAndEditsExpandOnlyTheChosenItem() {
        mount({ state() })
        ui.onNodeWithText("生活商店").assertIsDisplayed(); ui.onNodeWithText("2 件 · 自己用").assertIsDisplayed()
        ui.onNodeWithTag("review-item-0-quantity").assertDoesNotExist()
        ui.onNodeWithText("確認入帳 NT$ 180").assertIsDisplayed().assertIsEnabled()
        capture("native-review-390")
        ui.onNodeWithTag("native-review").performScrollToNode(hasTestTag("review-item-0")); ui.onNodeWithTag("review-item-0").performClick()
        ui.onNodeWithTag("review-item-0-quantity").performScrollTo().assertIsDisplayed()
        capture("native-item-editor-390")
    }
    @Test fun originalPhotoOpensAndReturnsInsideTheSameNativeReview() {
        mount({ state() })
        ui.onNodeWithTag("review-photo-0").performClick()
        ui.onNodeWithText("對照原圖").assertIsDisplayed(); ui.onNodeWithText("返回核對").performClick()
        ui.onNodeWithTag("native-review").assertExists()
        capture("native-review-after-photo-390")
    }
    @Test fun formalCompletedAndVoidedLedgerStatesRenderWithoutUsingSnapshotStatusForVoid() {
        val current = mutableStateOf(state(MobilePage.LedgerDetail))
        mount({ current.value }); ui.onNodeWithText("已完成").assertIsDisplayed(); capture("native-ledger-detail-390")
        val voided = jsonObject(ledger).apply { array("entries")[0].asJsonObject.addProperty("status", "voided") }.toString()
        ui.runOnIdle { current.value = current.value.copy(ledgerJson = voided) }
        ui.onNodeWithText("已作廢").assertIsDisplayed(); capture("native-ledger-voided-390")
    }
    @Test fun ledgerAndPersistentHelpAreNativePages() {
        val current = mutableStateOf(state(MobilePage.Ledger))
        mount({ current.value }, MobileActions(help = { current.value = current.value.copy(page = MobilePage.Help) }, back = { current.value = current.value.copy(page = MobilePage.Ledger) }))
        capture("native-ledger-390")
        ui.onNodeWithTag("mobile-help").performClick(); ui.onNodeWithTag("native-help").assertExists(); capture("native-help-390")
        ui.onNodeWithTag("mobile-back").performClick(); ui.onNodeWithTag("native-ledger").assertExists()
    }
    @Test @Config(qualifiers = "w320dp-h740dp-mdpi") fun narrowLargeTextKeepsReceiptAndFinalActionUsableWithInlineErrors() {
        val current = mutableStateOf(state())
        mount({ current.value }, MobileActions(editReview = { _, change ->
            val next = current.value.batch!!.review!!; val editor = next.editor.also(change)
            current.value = current.value.copy(batches = listOf(batch.copy(review = next.copy(editorJson = editor.toString(), editRevision = 1))))
        }), 1.5f)
        capture("native-review-320-font150")
        ui.onNodeWithTag("native-review").performScrollToNode(hasTestTag("review-item-0")); ui.onNodeWithTag("review-item-0").performClick()
        ui.onNodeWithTag("review-item-0-quantity").performScrollTo().performTextReplacement("1.5")
        ui.onNodeWithTag("mobile-primary").assertIsDisplayed().assertIsNotEnabled()
        ui.onNodeWithTag("review-item-0-quantity").assertTextContains("1.5")
        capture("native-review-invalid-320-font150")
    }
    @Test fun confirmedAndVoidedIntakesShowTheFormalStateImmediatelyInInboxAndDetail() {
        val active = batch.copy(draftStatus = "confirmed", ledgerStatus = "active", ledgerId = UUID.randomUUID().toString())
        val current = mutableStateOf(state(MobilePage.Inbox).copy(batches = listOf(active)))
        mount({ current.value })
        ui.onNodeWithText("已完成").assertIsDisplayed(); capture("native-inbox-completed-390")
        ui.runOnIdle { current.value = current.value.copy(page = MobilePage.Detail) }
        ui.onNodeWithText("查看已完成帳目").assertIsDisplayed()
        ui.runOnIdle { current.value = current.value.copy(batches = listOf(active.copy(draftStatus = "voided", ledgerStatus = "voided")), page = MobilePage.Inbox) }
        ui.onNodeWithText("已作廢").assertIsDisplayed(); capture("native-inbox-voided-390")
        ui.runOnIdle { current.value = current.value.copy(page = MobilePage.Detail) }
        ui.onNodeWithText("查看作廢紀錄").assertIsDisplayed()
    }
    @Test fun aFormalCompletionObservedDuringReviewReplacesConfirmationWithTheLedgerAction() {
        val id = UUID.randomUUID().toString()
        val current = mutableStateOf(state())
        var opened: String? = null; var confirms = 0
        mount({ current.value }, MobileActions(ledgerDetail = { opened = it }, confirmReview = { confirms++ }))
        ui.runOnIdle { current.value = current.value.copy(batches = listOf(batch.copy(ledgerId = id, ledgerStatus = "active", draftStatus = "confirmed"))) }
        ui.onNodeWithText("已完成").assertIsDisplayed()
        ui.onNodeWithText("確認入帳 NT$ 180").assertDoesNotExist()
        ui.onNodeWithTag("mobile-primary").assertIsEnabled().performClick()
        assertEquals(id, opened); assertEquals(0, confirms)
    }
    @Test fun missingHistoricalPhotoHasAnExplicitNativeUnavailableState() {
        val missing = jsonObject(ledger).apply { array("entries")[0].asJsonObject.getAsJsonObject("snapshot").array("evidence")[0].asJsonObject.addProperty("sha256", "f".repeat(64)) }.toString()
        mount({ state(MobilePage.LedgerDetail).copy(ledgerJson = missing) })
        ui.onNodeWithTag("native-ledger-detail").performScrollToNode(hasText("原圖 1 · 此裝置未保存")); ui.onNodeWithText("原圖 1 · 此裝置未保存").performClick()
        ui.onNodeWithText("此裝置未保存原圖。帳目與核對內容仍可在 App 查看。").assertIsDisplayed()
    }
}
