package com.momonong.pixelreceipt

import android.content.ContentValues
import android.content.Intent
import android.graphics.*
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import android.os.SystemClock
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.momonong.pixelreceipt.app.ReceiptApplication
import com.momonong.pixelreceipt.domain.model.*
import com.momonong.pixelreceipt.domain.usecase.inputText
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.After
import org.junit.Test
import java.io.File
import java.util.UUID

/** Real Compose actions and Room persistence on an isolated emulator; synthetic receipts only. */
class ReceiptFlowUiTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private var launchIntent: Intent? = null
    @After fun restoreScenarioIntent() {
        // ActivityScenario tracks the launch Intent; MainActivity legitimately replaces it on SEND.
        launchIntent?.let { original -> ui.runOnUiThread { ui.activity.intent = original } }
    }
    private val graph get() = ui.activity.application as ReceiptApplication
    private fun form() = ui.onNodeWithTag("review-form")
    private fun field(tag: String, value: String) {
        if (tag == "merchant") {
            form().performScrollToIndex(0)
            if (ui.onAllNodesWithTag("manual-fallback").fetchSemanticsNodes().isNotEmpty()) ui.onNodeWithTag("manual-fallback").performScrollTo().performClick()
            ui.onNodeWithText("商家與日期 ＋").performScrollTo().performClick()
        }
        ui.onNodeWithTag(tag).performScrollTo().performTextReplacement(value)
    }
    private fun classifyAll() {
        form().performScrollToNode(hasTestTag("batch-self")); ui.onNodeWithTag("batch-self").performClick()
        ui.onNodeWithText("確定全部設為自用").performClick()
    }
    private fun scroll(tag: String) { form().performScrollToNode(hasTestTag(tag)) }
    private fun shot(name: String) {
        File(ui.activity.getExternalFilesDir(null), "$name-${InstrumentationRegistry.getArguments().getString("profile", "compact")}.png").outputStream().use {
            ui.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
    private fun add(name: String, quantity: String, amount: String) {
        scroll("add-line"); ui.onNodeWithTag("add-line").performClick()
        field("line-name", name); field("line-quantity", quantity); field("line-amount", amount)
        ui.onNodeWithTag("line-amount").performClick()
        ui.onNodeWithTag("save-draft").assertIsDisplayed()
        shot("flow-keyboard")
        ui.onNodeWithText("完成此品項，回到清單").performScrollTo().performClick()
    }
    private fun savedDraft(merchant: String) = runBlocking { graph.repository.drafts.first().single { it.merchant.inputText() == merchant } }

    @Test fun manualMealThreeItemsEditDeleteSaveResumeBalanceAndReadOnly() {
        val merchant = "午餐驗收-${UUID.randomUUID().toString().take(5)}"
        shot("flow-home")
        ui.onNodeWithText("沒有照片，直接填寫").performClick()
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("review-form").fetchSemanticsNodes().isNotEmpty() }
        field("merchant", merchant)
        add("招牌雞腿飯與季節蔬菜超長品名核對範例", "2", "100")
        add("湯", "1", "70")
        add("茶", "1", "30")
        ui.activityRule.scenario.recreate()
        form().performScrollToNode(hasText("修改品項 1"))
        ui.onNodeWithText("修改品項 1").performClick()
        field("line-amount", "120")
        ui.onNodeWithText("完成此品項，回到清單").performScrollTo().performClick()
        form().performScrollToNode(hasText("修改品項 2"))
        ui.onNodeWithText("修改品項 2").performClick()
        ui.onNodeWithText("刪除此品項").performScrollTo().performClick()
        ui.onNodeWithText("刪除品項", useUnmergedTree = true).performClick()
        scroll("total"); field("total", "200")
        ui.onNodeWithTag("amount-preview").assertTextContains("差額 -50", substring = true)
        ui.onNodeWithTag("save-draft").performClick()
        ui.onNodeWithText("返回", substring = false).performClick()
        ui.waitUntil(10_000) { ui.onAllNodesWithText("消費紀錄").fetchSemanticsNodes().isNotEmpty() }
        val saved = savedDraft(merchant)
        assertEquals(2, saved.items.size)
        assertEquals("2", saved.items[0].quantity.inputText())
        assertEquals("120", saved.items[0].printedTotal.inputText())
        assertTrue(saved.transactionDate is Fact.Unknown)
        assertTrue(saved.evidenceLinks.isEmpty())
        ui.onNodeWithTag("transaction-${saved.id}").performScrollTo().performClick()
        classifyAll(); scroll("complete-check"); ui.onNodeWithTag("complete-check").performClick()
        ui.onNodeWithTag("save-draft").performClick()
        ui.onNodeWithTag("confirm-transaction").performScrollTo().assertIsNotEnabled()
        shot("flow-unbalanced")
        field("total", "150")
        classifyAll(); scroll("complete-check"); ui.onNodeWithTag("complete-check").performClick()
        ui.onNodeWithTag("save-draft").performClick()
        ui.onNodeWithTag("reconciliation").performScrollTo().assertTextContains("完全平衡", substring = true)
        shot("flow-balanced")
        ui.onNodeWithTag("confirm-transaction").performScrollTo().performClick()
        ui.onNodeWithTag("accept-confirmation").performClick()
        ui.waitUntil(10_000) { savedDraft(merchant).stage == ReceiptStage.Confirmed }
        ui.onNodeWithTag("save-draft").assertDoesNotExist()
        ui.onNodeWithText("完成，返回消費紀錄").assertIsDisplayed()
        shot("flow-confirmed")
    }

    @Test fun sharedPhotoIsAttachedAndUnsavedExitCanSaveThenResumeWithTolerance() {
        val uri = makePhoto()
        launchIntent = Intent(ui.activity.intent)
        val intent = Intent(ui.activity, MainActivity::class.java).apply {
            action = Intent.ACTION_SEND; type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        ui.runOnUiThread { ui.activity.startActivity(intent) }
        ui.waitUntil(15_000) { ui.onAllNodesWithTag("review-form").fetchSemanticsNodes().isNotEmpty() }
        val merchant = "照片驗收-${UUID.randomUUID().toString().take(5)}"
        field("merchant", merchant)
        add("午餐", "2", "100")
        ui.onNodeWithTag("preview-photo").performClick()
        ui.onNodeWithText("返回填寫").assertIsDisplayed().performClick()
        scroll("total"); field("total", "99")
        ui.onNodeWithText("返回", substring = false).performClick()
        ui.onNodeWithText("繼續填寫").performClick()
        ui.onNodeWithTag("save-status").assertTextContains("尚有未保存修改")
        ui.onNodeWithText("返回", substring = false).performClick()
        ui.onNodeWithText("保存並離開").performClick()
        ui.waitUntil(10_000) { ui.onAllNodesWithText("消費紀錄").fetchSemanticsNodes().isNotEmpty() }
        val saved = savedDraft(merchant)
        assertEquals(1, saved.evidenceAssetIds.size)
        assertTrue(saved.evidenceLinks.isEmpty())
        assertTrue((saved.items.single().printedTotal as Fact.Known).provenance.evidence.isEmpty())
        ui.onNodeWithTag("transaction-${saved.id}").performScrollTo().performClick()
        classifyAll(); scroll("complete-check"); ui.onNodeWithTag("complete-check").performClick()
        ui.onNodeWithTag("save-draft").performClick()
        ui.onNodeWithTag("reconciliation").performScrollTo().assertTextContains("容差內", substring = true)
        shot("flow-tolerance")
        ui.onNodeWithTag("confirm-transaction").performScrollTo().performClick()
        ui.onNodeWithTag("confirmation-summary").assertTextContains("容差內", substring = true)
        ui.onNodeWithTag("accept-confirmation").performClick()
        ui.waitUntil(10_000) { savedDraft(merchant).stage == ReceiptStage.Confirmed }
        assertEquals("100", savedDraft(merchant).items.single().printedTotal.inputText())
        assertEquals("99", savedDraft(merchant).total.inputText())
    }

    @Test fun systemPickerCreatesTransactionAndAppendsWithoutLosingEdits() {
        makePhoto(); makePhoto()
        ui.onNodeWithTag("new-transaction").performClick()
        chooseSystemPhotos(2)
        ui.waitUntil(15_000) { ui.onAllNodesWithTag("review-form").fetchSemanticsNodes().isNotEmpty() }
        val merchant = "補照片驗收-${UUID.randomUUID().toString().take(5)}"
        field("merchant", merchant)
        add("餐點", "2", "100")
        makePhoto()
        ui.onNodeWithText("補照片", substring = false).performClick()
        ui.onNodeWithText("保存並選照片").performClick()
        chooseSystemPhotos(1)
        ui.waitUntil(15_000) { runBlocking { graph.repository.drafts.first().any { it.merchant.inputText() == merchant && it.evidenceAssetIds.size == 3 } } }
        val draft = savedDraft(merchant)
        assertEquals(1, draft.items.size)
        assertEquals("100", draft.items.single().printedTotal.inputText())
        assertEquals(ReceiptAssemblyStatus.Collecting, draft.assemblyStatus)
        assertTrue(draft.evidenceLinks.isEmpty())
        form().performScrollToIndex(0)
        shot("flow-photos-compact")
        ui.onNodeWithText("補照片", substring = false).performClick()
        nativeWait { nodes().firstOrNull { it.contentDescription?.toString() == "Cancel" }?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true }
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("review-form").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(draft, savedDraft(merchant))
        ui.onNodeWithText("返回", substring = false).performClick()
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null) return
            result += node
            for (i in 0 until node.childCount) visit(node.getChild(i))
        }
        visit(InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow)
        return result
    }
    private fun nativeWait(condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 15_000
        while (!condition()) {
            check(SystemClock.uptimeMillis() < end) { "System Picker element missing: ${nodes().map { it.text ?: it.contentDescription }}" }
            SystemClock.sleep(100)
        }
    }
    private fun chooseSystemPhotos(count: Int) {
        nativeWait { nodes().count { it.contentDescription?.toString()?.startsWith("Photo taken") == true } >= count }
        repeat(count) { index ->
            nodes().filter { it.contentDescription?.toString()?.startsWith("Photo taken") == true }[index]
                .performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        nativeWait { nodes().firstOrNull { it.text?.toString() == "Add ($count)" }?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true }
    }

    private fun makePhoto(): android.net.Uri {
        val resolver = ui.activity.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "receipt-flow-${UUID.randomUUID()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        })!!
        val bitmap = Bitmap.createBitmap(900, 1200, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap); canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 40f }
        listOf("人工核對測試明細", "午餐    2    100", "交易總額 99", "合成圖片 ${UUID.randomUUID().toString().take(6)}")
            .forEachIndexed { i, text -> canvas.drawText(text, 30f, 120f + i * 100f, paint) }
        resolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return uri
    }
}
