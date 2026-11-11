package com.momonong.pixelreceipt.mobile

import android.app.Application
import android.app.DatePickerDialog
import android.content.ComponentName
import android.content.DialogInterface
import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.momonong.pixelreceipt.core.ui.theme.PixelReceiptTheme
import com.momonong.pixelreceipt.data.ingestion.ImageStore
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.time.LocalDate
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MobileReviewDateRenderingTest {
    @get:Rule val ui = createEmptyComposeRule()
    private lateinit var scenario: ActivityScenario<ComponentActivity>
    private lateinit var directory: File
    private lateinit var images: ImageStore
    private lateinit var batch: MobileBatch
    private val current = mutableStateOf(MobileUiState())

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        directory = File(context.cacheDir, UUID.randomUUID().toString()).apply { mkdirs() }
        images = ImageStore(directory)
        val draft = jsonObject(nativeDraft())
        val review = createReview(draft.toString())
        batch = MobileBatch(UUID.randomUUID().toString(), importComplete = true, status = "succeeded", draftId = draft.text("id"), draftStatus = "needs_review", review = review)
        shadowOf(context.packageManager).addActivityIfNotPresent(ComponentName(context, ComponentActivity::class.java))
        scenario = ActivityScenario.launch(Intent(context, ComponentActivity::class.java))
    }
    @After fun cleanup() { scenario.close(); directory.deleteRecursively() }
    private fun mount(writable: Boolean = true) {
        current.value = MobileUiState(batches = listOf(batch), selectedId = batch.id, page = MobilePage.Review, signedIn = true, reviewer = true, writable = writable)
        scenario.onActivity { activity -> activity.setContent {
            PixelReceiptTheme(darkTheme = false, dynamicColor = false) {
                MobileContent(current.value, images, MobileActions(editReview = { _, change ->
                    val review = current.value.batch!!.review!!
                    current.value = current.value.copy(batches = listOf(batch.copy(review = review.copy(editorJson = review.editor.also(change).toString(), editRevision = review.editRevision + 1))))
                }))
            }
        } }
        ui.waitForIdle()
    }
    private fun editor() = current.value.batch!!.review!!.editor.getAsJsonObject("candidate")
    private fun openHeader() { ui.onNodeWithTag("review-header-edit").performClick() }
    private fun capture(name: String) {
        ui.waitForIdle()
        val file = File(System.getProperty("pixelreceipt.screenshots"), "$name.png")
        file.parentFile!!.mkdirs()
        val image = ui.onRoot().captureToImage().asAndroidBitmap()
        file.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }

    @Test fun shortcutsStoreIsoDatesAndDisplayReadableLocalDates() {
        mount(); openHeader()
        ui.onNodeWithTag("review-date-today").performClick()
        assertEquals(LocalDate.now().toString(), editor().text("receipt_date"))
        ui.onNodeWithTag("review-date").assertTextContains("今天 · ${LocalDate.now().year}年", substring = true)
        capture("native-review-date-390")
        ui.onNodeWithTag("review-date-yesterday").performClick()
        assertEquals(LocalDate.now().minusDays(1).toString(), editor().text("receipt_date"))
        ui.onNodeWithTag("review-date").assertTextContains("${LocalDate.now().minusDays(1).dayOfMonth}日", substring = true)
    }
    @Test fun calendarSelectionAndCancellationPreserveOtherEdits() {
        mount(); openHeader()
        ui.onNodeWithTag("review-merchant").performTextReplacement("已修正店家")
        val original = editor().text("receipt_date")
        ui.onNodeWithTag("review-date").performClick()
        ui.runOnIdle { (ShadowDialog.getLatestDialog() as DatePickerDialog).apply { datePicker.updateDate(2026, 0, 2); cancel() } }
        assertEquals(original, editor().text("receipt_date"))
        assertEquals("已修正店家", editor().text("merchant"))
        ui.onNodeWithTag("review-date").performClick()
        ui.runOnIdle { (ShadowDialog.getLatestDialog() as DatePickerDialog).apply { datePicker.updateDate(2026, 0, 2); getButton(DialogInterface.BUTTON_POSITIVE).performClick() } }
        ui.onNodeWithTag("review-date").assertTextContains("2026年1月2日")
        assertEquals("2026-01-02", editor().text("receipt_date"))
        assertEquals("已修正店家", editor().text("merchant"))
    }
    @Test fun losingWriteAccessDisablesDateControlsAndDismissesOpenCalendar() {
        mount(); openHeader(); ui.onNodeWithTag("review-date").performClick()
        ui.runOnIdle { current.value = current.value.copy(writable = false) }
        ui.onNodeWithTag("review-date").assertIsNotEnabled()
        ui.onNodeWithTag("review-date-today").assertIsNotEnabled()
        ui.onNodeWithTag("review-date-yesterday").assertIsNotEnabled()
        ui.runOnIdle { assertFalse(ShadowDialog.getLatestDialog().isShowing) }
        assertEquals("2026-10-23", editor().text("receipt_date"))
    }
    @Test fun missingReceiptDateHintDoesNotImplyItWasReadFromTheReceipt() {
        val draft = batch.review!!.draft.apply { getAsJsonObject("candidate").add("receipt_date", com.google.gson.JsonNull.INSTANCE) }
        val review = createReview(draft.toString())
        val editor = review.editor.apply { getAsJsonObject("candidate").addProperty("receipt_date", LocalDate.now().toString()) }
        batch = batch.copy(review = review.copy(editorJson = editor.toString()))
        mount()
        ui.onNodeWithText("收據未顯示日期，已先填今天，可修改").assertIsDisplayed()
        assertTrue(current.value.batch!!.review!!.draft.getAsJsonObject("candidate")["receipt_date"].isJsonNull)
    }
    @Test fun invalidDateIsReadableAndItsErrorRemainsNextToTheControl() {
        val review = batch.review!!
        val editor = review.editor.apply { getAsJsonObject("candidate").addProperty("receipt_date", "2026-02-31") }
        batch = batch.copy(review = review.copy(editorJson = editor.toString()))
        mount(); openHeader()
        ui.onNodeWithTag("review-date").assertTextContains("日期格式需修正")
        ui.onNodeWithTag("review-date-today").performClick()
        assertEquals(LocalDate.now().toString(), editor().text("receipt_date"))
    }
    @Test fun missingNamesStayBlankWhileOfferingLaterCompletion() {
        val review = batch.review!!
        val editor = review.editor.apply { getAsJsonObject("candidate").array("items")[0].asJsonObject.addProperty("raw_name", "") }
        batch = batch.copy(review = review.copy(editorJson = editor.toString()))
        mount()
        ui.onNodeWithTag("native-review").performScrollToNode(hasText("1. 待補品名"))
        ui.onNodeWithTag("review-item-0").performClick()
        ui.onNodeWithTag("review-item-0-raw_name").performScrollTo().assertTextContains("品名（可留空）").performClick()
        ui.onNodeWithText("稍後再補也可以").assertIsDisplayed()
        capture("native-review-empty-name-390")
        assertEquals("", editor().array("items")[0].asJsonObject.text("raw_name"))
    }
}
