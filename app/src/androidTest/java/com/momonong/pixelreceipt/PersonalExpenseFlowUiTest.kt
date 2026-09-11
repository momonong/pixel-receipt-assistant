package com.momonong.pixelreceipt

import android.content.ContentValues
import android.content.Intent
import android.graphics.*
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.momonong.pixelreceipt.app.ReceiptApplication
import com.momonong.pixelreceipt.domain.model.*
import com.momonong.pixelreceipt.domain.rules.PersonalExpenseCalculator
import com.momonong.pixelreceipt.domain.usecase.inputText
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

/** Synthesized image through the real SEND intent, bundled OCR, Compose and Room. Not an accuracy benchmark. */
class PersonalExpenseFlowUiTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private var originalIntent: Intent? = null
    private var source: Uri? = null
    private val graph get() = ui.activity.application as ReceiptApplication
    private fun form() = ui.onNodeWithTag("review-form")
    private fun node(tag: String): SemanticsNodeInteraction {
        form().performScrollToNode(hasTestTag(tag))
        return ui.onNodeWithTag(tag).performScrollTo()
    }
    private fun receipt(id: String) = runBlocking { graph.repository.observeDraft(id).first()!! }
    @After fun restoreIntent() {
        runCatching { File(ui.activity.getExternalFilesDir(null), "expense-last-screen.png").outputStream().use {
            ui.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        } }
        originalIntent?.let { ui.runOnUiThread { ui.activity.intent = it } }
        source?.let { ui.activity.contentResolver.delete(it, null, null) }
    }

    @Test fun shareRecognizeCorrectSplitDiscountSaveReopenAndConfirm() {
        val startedAt = android.os.SystemClock.elapsedRealtime()
        val bitmap = Bitmap.createBitmap(1600, 1400, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap); canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 48f; typeface = Typeface.MONOSPACE }
        listOf("EXPENSE STORE", "2026/09/11", "ITEM QTY PRICE AMOUNT", "TEA 3 33 100", "GIFT 1 40 40", "BREAD 1 20 20", "DISCOUNT 5", "TOTAL 155")
            .forEachIndexed { index, text -> canvas.drawText(text, 70f, 110f + index * 100f, paint) }
        val resolver = ui.activity.contentResolver
        source = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "expense-synthetic-${UUID.randomUUID()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        })!!
        resolver.openOutputStream(source!!)!!.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        val before = runBlocking { graph.repository.drafts.first().map { it.id }.toSet() }
        originalIntent = Intent(ui.activity.intent)
        ui.runOnUiThread { ui.activity.startActivity(Intent(ui.activity, MainActivity::class.java).apply {
            action = Intent.ACTION_SEND; type = "image/png"; putExtra(Intent.EXTRA_STREAM, source)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }) }
        ui.waitUntil(15_000) { ui.onAllNodesWithTag("recognize-receipt").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("merchant").assertDoesNotExist()
        node("engine-ocr").performClick()
        node("recognize-receipt").performClick()
        ui.waitUntil(30_000) { runBlocking { graph.repository.drafts.first().any { it.id !in before && it.extraction != null } } }
        val extracted = runBlocking { graph.repository.drafts.first().single { it.id !in before && it.extraction != null } }
        assertEquals(3, extracted.items.size)
        assertTrue(extracted.personalExpenses.isEmpty())
        assertEquals(ReceiptAssemblyStatus.Collecting, extracted.assemblyStatus)
        assertTrue(extracted.evidenceLinks.all { it.status == EvidenceLinkStatus.Candidate })
        // Large text can place the first line beyond the lazy viewport; scroll to it below.
        ui.waitForIdle()
        form().performScrollToNode(hasText("商家與日期 ＋"))
        ui.onNodeWithText("商家與日期 ＋").performClick()
        node("date").performTextReplacement("2026-09-11")
        ui.onNodeWithText("收起：商家與日期").performScrollTo().performClick()

        form().performScrollToNode(hasText("修改品項 1")); ui.onNodeWithText("修改品項 1").performClick()
        node("line-name").performTextReplacement("TEA corrected")
        val tea = extracted.items[0].id
        node("split-$tea").performClick()
        node("expense-qty-Self").performTextReplacement("1")
        node("expense-qty-Advance").performTextReplacement("2")
        node("accept-split-$tea").performClick()
        ui.onNodeWithText("完成此品項，回到清單").performScrollTo().performClick()
        // Wait for the real IME dismissal before positioning pointer clicks on subsequent rows.
        ui.waitUntil(5_000) { androidx.core.view.ViewCompat.getRootWindowInsets(ui.activity.window.decorView)
            ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) != true }
        node("purpose-${extracted.items[1].id}-Gift").assertIsEnabled().performClick().assertIsSelected()
        node("purpose-${extracted.items[2].id}-Self").assertIsEnabled().performClick().assertIsSelected()
        node("expense-summary").assertTextContains("待分類／待分配", substring = true)
        form().performScrollToNode(hasText("整筆消費")); ui.onNodeWithText("整筆消費", substring = false).performClick()
        form().performScrollToNode(hasText("按適用品項金額比例")); ui.onNodeWithText("按適用品項金額比例").performClick()
        ui.onNodeWithText("確認這筆加減項分配").performScrollTo().performClick()
        node("expense-summary").assertTextContains("個人支出 90 TWD", substring = true)
        ui.onNodeWithTag("expense-summary").assertTextContains("代墊／預期收回 65 TWD", substring = true)
        ui.onNodeWithTag("complete-check").performScrollTo().assertIsOff()
        ui.onNodeWithTag("complete-check").performClick()
        ui.onNodeWithTag("save-draft").performClick()
        ui.waitUntil(10_000) { receipt(extracted.id).personalExpenses.size == 3 }
        // ActivityScenario loses lifecycle tracking after an external SEND changes its launch intent.
        // Exercise navigation reopen here; actual process restart is verified separately on the emulator.
        ui.onNodeWithText("返回", substring = false).performClick()
        ui.onNodeWithTag("transaction-${extracted.id}").performScrollTo().performClick()
        node("expense-summary").assertTextContains("個人支出 90 TWD", substring = true)
        val profile = InstrumentationRegistry.getArguments().getString("profile", "compact")
        File(ui.activity.getExternalFilesDir(null), "expense-summary-$profile.png").outputStream().use {
            ui.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        node("confirm-transaction").performClick()
        ui.onNodeWithTag("confirmation-summary").assertTextContains("個人支出 90 TWD", substring = true)
        if (profile == "compact-large-font") ui.onNodeWithTag("confirmation-summary")
            .assert(hasScrollAction()).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 10_000f) }
        File(ui.activity.getExternalFilesDir(null), "expense-confirmation-$profile.png").outputStream().use {
            ui.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        ui.onNodeWithTag("accept-confirmation").performClick()
        ui.waitUntil(10_000) { receipt(extracted.id).stage == ReceiptStage.Confirmed }
        assertEquals("TEA corrected", receipt(extracted.id).items[0].rawName.inputText())
        assertEquals("2026-09-11", receipt(extracted.id).transactionDate.inputText())
        assertEquals(3, receipt(extracted.id).items.size)
        assertEquals(90L, PersonalExpenseCalculator.calculate(receipt(extracted.id)).personalMinor)
        assertTrue(receipt(extracted.id).evidenceLinks.all { it.status == EvidenceLinkStatus.Candidate })
        File(ui.activity.getExternalFilesDir(null), "expense-flow-$profile.txt").writeText(
            "Synthetic SEND -> actual ML Kit -> name edit and date correction -> partial quantity / gift / self -> explicit order discount -> save -> reopen -> confirm.\n" +
                "observedOcrDate=${extracted.transactionDate.inputText()} expectedDate=2026-09-11\n" +
                "payment=155 personal=90 advance=65 items=3 elapsedAutomationMs=${android.os.SystemClock.elapsedRealtime() - startedAt}\n" +
                "Automation time is not human completion time or real receipt accuracy.")
        ui.onNodeWithTag("save-draft").assertDoesNotExist()
    }
}
