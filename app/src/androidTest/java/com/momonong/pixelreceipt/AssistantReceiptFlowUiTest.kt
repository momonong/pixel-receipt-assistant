package com.momonong.pixelreceipt

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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

/** Exercises app handoff, not Gemini inference or an actual assistant invocation. */
class AssistantReceiptFlowUiTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val graph get() = ui.activity.application as ReceiptApplication
    private var originalIntent: Intent? = null
    private fun drafts() = runBlocking { graph.repository.drafts.first() }
    private fun draft(id: String) = runBlocking { graph.repository.observeDraft(id).first()!! }
    private fun form() = ui.onNodeWithTag("review-form")
    private fun node(tag: String): SemanticsNodeInteraction {
        form().performScrollToNode(hasTestTag(tag))
        return ui.onNodeWithTag(tag).performScrollTo()
    }
    private fun payload(merchant: String) = """{"schema":"pixelreceipt-1","currency":"TWD","merchant":"$merchant","date":"2026-09-14","totalMinor":450,"items":[{"name":"自己買的","quantity":1,"lineTotalMinor":300},{"name":"替人買的","quantity":1,"lineTotalMinor":150}],"adjustments":[]}"""
    @After fun captureAndRestoreIntent() {
        runCatching {
            File(ui.activity.getExternalFilesDir(null), "assistant-last-screen.png").outputStream().use {
                ui.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        originalIntent?.let { ui.runOnUiThread { ui.activity.intent = it } }
    }

    @Test fun pasteRotateImportClassifySaveReopenAndConfirm() {
        val merchant = "Gemini 合成驗證 ${UUID.randomUUID().toString().take(8)}"
        ui.onNodeWithTag("gemini-import").performScrollTo().performClick()
        ui.onNodeWithTag("assistant-text").performTextInput(payload(merchant))
        ui.onNodeWithTag("assistant-preview").performScrollTo().assertTextContains("2 個品項", substring = true)
        ui.activityRule.scenario.recreate()
        ui.onNodeWithTag("assistant-text").assertTextContains(merchant, substring = true)
        ui.onNodeWithTag("import-assistant").performClick()
        ui.waitUntil(10_000) { drafts().any { it.merchant.inputText() == merchant } }
        val imported = drafts().single { it.merchant.inputText() == merchant }
        assertEquals(ReceiptStage.NeedsReview, imported.stage)
        assertEquals(AssistantImportChannel.PastedText, imported.assistantImport!!.channel)
        assertTrue(imported.personalExpenses.isEmpty())
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("review-form").fetchSemanticsNodes().isNotEmpty() }
        node("purpose-${imported.items[0].id}-Self").performClick()
        node("purpose-${imported.items[1].id}-Advance").performClick()
        node("expense-summary").assertTextContains("個人支出 300 TWD", substring = true)
        ui.onNodeWithTag("expense-summary").assertTextContains("代墊／預期收回 150 TWD", substring = true)
        ui.onNodeWithTag("complete-check").performScrollTo().performClick()
        ui.onNodeWithTag("save-draft").performClick()
        ui.waitUntil(10_000) { draft(imported.id).personalExpenses.size == 2 }
        ui.onNodeWithText("返回", substring = false).performClick()
        ui.onNodeWithTag("transaction-${imported.id}").performScrollTo().performClick()
        node("expense-summary").assertTextContains("個人支出 300 TWD", substring = true)
        node("confirm-transaction").performClick()
        ui.onNodeWithTag("accept-confirmation").performClick()
        ui.waitUntil(10_000) { draft(imported.id).stage == ReceiptStage.Confirmed }
        assertEquals(300L, PersonalExpenseCalculator.calculate(draft(imported.id)).personalMinor)
        assertEquals(payload(merchant), draft(imported.id).assistantImport!!.rawInput)
        assertTrue(draft(imported.id).evidenceAssetIds.isEmpty())
    }

    @Test fun incomingSharedTextSavesDirtyReviewAndInvalidInputCreatesNothing() {
        val name = "待保存人工內容 ${UUID.randomUUID().toString().take(8)}"
        ui.onNodeWithText("沒有照片，直接填寫").performScrollTo().performClick()
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("review-form").fetchSemanticsNodes().isNotEmpty() }
        form().performScrollToNode(hasText("商家與日期 ＋"))
        ui.onNodeWithText("商家與日期 ＋").performClick()
        node("merchant").performTextInput(name)
        val before = drafts().map { it.id }.toSet()
        originalIntent = Intent(ui.activity.intent)
        fun share(text: String) = ui.runOnUiThread {
            ui.activity.startActivity(Intent(ui.activity, MainActivity::class.java).apply {
                action = Intent.ACTION_SEND; type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            })
        }
        share("https://gemini.google.com/share/not-a-receipt")
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("assistant-text").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("import-assistant").performClick()
        ui.onNodeWithTag("assistant-error").performScrollTo().assertExists()
        assertEquals(before, drafts().map { it.id }.toSet())
        val newName = "分享合成收據 ${UUID.randomUUID().toString().take(8)}"
        ui.onNodeWithTag("assistant-text").performTextReplacement(payload(newName))
        ui.onNodeWithTag("import-assistant").performClick()
        ui.waitUntil(10_000) { drafts().any { it.merchant.inputText() == newName } }
        assertEquals(1, drafts().count { it.merchant.inputText() == name })
        val imported = drafts().single { it.merchant.inputText() == newName }
        assertEquals(AssistantImportChannel.SharedText, imported.assistantImport!!.channel)
        assertEquals(ReceiptStage.NeedsReview, imported.stage)
        assertTrue(imported.personalExpenses.isEmpty())
    }
}
