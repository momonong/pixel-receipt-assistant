package com.momonong.pixelreceipt.mobile

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.google.gson.JsonArray
import com.momonong.pixelreceipt.core.ui.theme.PixelReceiptTheme
import com.momonong.pixelreceipt.data.ingestion.ImageStore
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeNoteReviewRenderingTest {
    @get:Rule val ui = createEmptyComposeRule()
    private lateinit var scenario: ActivityScenario<ComponentActivity>
    private lateinit var directory: File
    private lateinit var images: ImageStore
    private lateinit var batch: MobileBatch
    private val current = mutableStateOf(MobileUiState())
    private val edits = MobileReviewEdits(SavedStateHandle())
    private val quote = "牙膏兩條全部幫小美買"
    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        directory = File(context.cacheDir, UUID.randomUUID().toString()).apply { mkdirs() }
        images = ImageStore(directory)
        val remote = jsonObject(nativeDraft(revision = 2)).apply {
            add("note_review", jsonObject("""{"status":"matched","decisions":[{"item_position":0,"purpose":"advance","note_quote":"$quote"}],"unresolved_notes":[]}"""))
            add("review_document", jsonObject("""{"schema":"pixelreceipt-central-review-2","assembly_status":"unknown","tolerance_acknowledgement":"none","line_decisions":[],"adjustment_decisions":[]}""").apply {
                add("line_decisions", JsonArray().apply { add(wholeDecision(0, "self")) })
            })
        }
        batch = MobileBatch(UUID.randomUUID().toString(), userNotes = quote, importComplete = true, status = "succeeded", draftId = remote.text("id"), draftStatus = "needs_review", review = createReview(remote.toString()))
        shadowOf(context.packageManager).addActivityIfNotPresent(ComponentName(context, ComponentActivity::class.java))
        scenario = ActivityScenario.launch(Intent(context, ComponentActivity::class.java))
    }
    @After fun cleanup() { scenario.close(); directory.deleteRecursively() }
    private fun mount(writable: Boolean = true) {
        current.value = MobileUiState(batches = listOf(batch), selectedId = batch.id, page = MobilePage.Review, signedIn = true, reviewer = true, writable = writable)
        scenario.onActivity { activity -> activity.setContent {
            PixelReceiptTheme(darkTheme = false, dynamicColor = false) {
                MobileContent(current.value, images, MobileActions(editReview = { _, change ->
                    val currentBatch = current.value.batch!!
                    edits.edit(currentBatch, change)
                    current.value = current.value.copy(batches = listOf(currentBatch.copy(review = edits.state.value[currentBatch.id]!!.value)))
                }))
            }
        } }
        ui.waitForIdle()
    }
    private fun acknowledgeAutosave() {
        ui.runOnIdle {
            val currentBatch = current.value.batch!!
            val review = currentBatch.review!!
            current.value = current.value.copy(batches = listOf(currentBatch.copy(review = review.copy(savedEditRevision = review.editRevision))))
        }
    }
    private fun capture(name: String) {
        ui.waitForIdle()
        val image = ui.onRoot().captureToImage().asAndroidBitmap()
        val file = File(System.getProperty("pixelreceipt.screenshots"), "$name.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
    @Test fun oldAutosavedSelfShowsQuoteAndExplicitApplyWithoutChangingMoney() {
        mount()
        ui.onNodeWithTag("review-notes").assertIsDisplayed()
        ui.onNodeWithText("來源敘述 · $quote").assertIsDisplayed()
        ui.onNodeWithTag("mobile-primary").assertIsNotEnabled()
        assertEquals("self", current.value.batch!!.review!!.editor.array("line_decisions")[0].asJsonObject.text("whole_purpose"))
        val before = current.value.batch!!.review!!.editor["candidate"].deepCopy()
        capture("native-note-review-pending-390")
        ui.onNodeWithTag("review-notes-apply").performScrollTo().performClick()
        assertEquals("advance", current.value.batch!!.review!!.editor.array("line_decisions")[0].asJsonObject.text("whole_purpose"))
        assertEquals(before, current.value.batch!!.review!!.editor["candidate"])
        assertNotNull(prepareReview(current.value.batch!!.review!!, complete = true).body)
        ui.onNodeWithTag("mobile-primary").assertIsNotEnabled() // Money stays gated until autosave completes.
        acknowledgeAutosave()
        ui.onNodeWithTag("mobile-primary").assertIsEnabled()
    }
    @Test fun unresolvedNotesRequireExplicitKeepAndShowAcknowledgedFeedback() {
        val review = batch.review!!
        val draft = review.draft.apply { getAsJsonObject("note_review").addProperty("status", "needs_review") }
        batch = batch.copy(review = review.copy(serverJson = draft.toString()))
        mount()
        ui.onNodeWithText("補充敘述仍有無法對應的用途，請逐項核對。").assertIsDisplayed()
        ui.onNodeWithTag("mobile-primary").assertIsNotEnabled()
        ui.onNodeWithTag("review-notes-keep").performScrollTo().performClick()
        assertEquals("checked", current.value.batch!!.review!!.editor.text("_notes_checked"))
        ui.onNodeWithText("已核對敘述，保留目前用途。").assertIsDisplayed()
        assertNotNull(prepareReview(current.value.batch!!.review!!, complete = true).body)
        ui.onNodeWithTag("mobile-primary").assertIsNotEnabled() // Money stays gated until autosave completes.
        acknowledgeAutosave()
        ui.onNodeWithTag("mobile-primary").assertIsEnabled()
        capture("native-note-review-acknowledged-390")
    }
    @Test fun partialSplitAndReadOnlyStateCannotApplyWholeLineProposals() {
        val review = batch.review!!
        val editor = review.editor.apply { array("line_decisions").set(0, jsonObject("""{"item_position":0,"method":"explicit_amounts","whole_purpose":null,"quantities":{"self":1,"gift":0,"advance":1},"amounts_yuan":{"self":90,"gift":0,"advance":90}}""")) }
        batch = batch.copy(review = review.copy(editorJson = editor.toString()))
        mount(writable = false)
        ui.onNodeWithTag("review-notes-apply").performScrollTo().assertIsNotEnabled()
        ui.onNodeWithTag("review-notes-keep").assertIsNotEnabled()
        assertEquals("explicit_amounts", current.value.batch!!.review!!.editor.array("line_decisions")[0].asJsonObject.text("method"))
    }
}
