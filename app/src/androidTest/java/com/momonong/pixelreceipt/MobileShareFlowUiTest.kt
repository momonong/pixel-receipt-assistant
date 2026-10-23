package com.momonong.pixelreceipt

import android.content.Context
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.view.WindowManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.TextRange
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/** Run only on an authorized test device. Synthetic images; never logs in or sends to a model. */
class MobileShareFlowUiTest {
    @get:Rule val ui = createEmptyComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private var scenario: ActivityScenario<MainActivity>? = null
    private val sources = arrayListOf<Uri>()
    private var keepScreenOnWasSet: Boolean? = null
    @After fun cleanupOwnSources() {
        try {
            if (keepScreenOnWasSet == false) scenario?.onActivity { it.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        } finally {
            scenario?.close()
            sources.forEach { context.contentResolver.delete(it, null, null) }
        }
    }
    @Test fun multiplePhotoShareWithNotesSurvivesRecreationAndStaysInCentralIntake() {
        val resolver = context.contentResolver
        repeat(3) { index ->
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "mobile-fixture-${UUID.randomUUID()}.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            })!!
            sources += uri
            val bitmap = Bitmap.createBitmap(64, 96, Bitmap.Config.ARGB_8888).apply { eraseColor(0xffeeeeee.toInt() - index * 0x10101) }
            resolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
        val notes = "合成分享驗證 ${UUID.randomUUID()}；牙膏幫朋友買。"
        // ActivityScenario filters lifecycle events by its original launch Intent. Launch
        // the share directly: MAIN -> onNewIntent.setIntent(SEND_MULTIPLE) would make the
        // scenario ignore real RESUMED callbacks and fail before recreation even starts.
        val share = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_SEND_MULTIPLE; type = "image/png"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, sources)
            putExtra(Intent.EXTRA_TEXT, notes)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val activityScenario = ActivityScenario.launch<MainActivity>(share)
        scenario = activityScenario
        var originalActivity: MainActivity? = null
        activityScenario.onActivity {
            originalActivity = it
            keepScreenOnWasSet = it.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0
            it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            assertEquals(Intent.ACTION_SEND_MULTIPLE, it.intent.action)
        }
        assertEquals(Lifecycle.State.RESUMED, activityScenario.state)
        ui.waitForIdle()
        ui.waitUntil(15_000) {
            try {
                ui.onAllNodesWithTag("mobile-detail").fetchSemanticsNodes().isNotEmpty() &&
                    ui.onAllNodesWithText("3 張原圖與補充說明，會一起送交辨識。").fetchSemanticsNodes().isNotEmpty()
            }
            catch (error: IllegalStateException) {
                // A share Intent can briefly replace the resumed Activity. Keep the bounded
                // wait alive only for this exact transient; a permanent absence still times out.
                if (error.message?.startsWith("No compose hierarchies found") == true) false else throw error
            }
        }
        ui.onNodeWithText("3 張原圖與補充說明，會一起送交辨識。").assertExists()
        ui.onNodeWithTag("mobile-detail").performScrollToNode(hasTestTag("mobile-batch-notes"))
        ui.onNodeWithTag("mobile-batch-notes").assertTextContains(notes)
        ui.onNodeWithTag("review-form").assertDoesNotExist()
        val suffix = "；手機補充後保存。"
        val updated = notes + suffix
        ui.onNodeWithTag("mobile-detail").performScrollToNode(hasTestTag("mobile-batch-notes"))
        val batchNotes = ui.onNodeWithTag("mobile-batch-notes")
        batchNotes.performScrollTo().performClick().performTextInputSelection(TextRange(notes.length))
        var expected = notes
        suffix.forEach { character ->
            expected += character
            batchNotes.performTextInput(character.toString())
            ui.waitUntil(10_000) {
                ui.onAllNodesWithText("正在保存目前編輯，完成前不會送出。").fetchSemanticsNodes().isEmpty() &&
                    ui.onAllNodesWithText("正在保存編輯…").fetchSemanticsNodes().isEmpty()
            }
            batchNotes.assertIsFocused().assertTextContains(expected, substring = false)
        }
        ui.onNodeWithTag("mobile-detail").performScrollToNode(hasTestTag("mobile-photo-edit-1"))
        ui.onNodeWithTag("mobile-photo-edit-1").performClick()
        ui.onNodeWithTag("mobile-photo-notes-1").performScrollTo().performTextInput("第2張是價標，手機補充。")
        ui.waitUntil(10_000) { ui.onAllNodesWithText("正在保存目前編輯，完成前不會送出。").fetchSemanticsNodes().isEmpty() }
        activityScenario.recreate()
        assertEquals(Lifecycle.State.RESUMED, activityScenario.state)
        activityScenario.onActivity {
            assertNotSame("Recreation must replace the actual share Activity", originalActivity, it)
            assertEquals(Intent.ACTION_SEND_MULTIPLE, it.intent.action)
            it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        ui.waitUntil(15_000) { ui.onAllNodesWithTag("mobile-detail").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("mobile-detail").performScrollToNode(hasTestTag("mobile-batch-notes"))
        ui.onNodeWithTag("mobile-batch-notes").assertTextContains(updated)
        ui.onNodeWithTag("mobile-primary").assertIsDisplayed()
        ui.onNodeWithTag("mobile-detail").performScrollToNode(hasTestTag("mobile-photo-edit-1"))
        ui.onNodeWithTag("mobile-photo-notes-1").performScrollTo().assertTextContains("第2張是價標，手機補充。")
    }
}
