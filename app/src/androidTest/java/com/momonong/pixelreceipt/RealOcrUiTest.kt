package com.momonong.pixelreceipt

import android.graphics.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import com.momonong.pixelreceipt.app.ReceiptApplication
import com.momonong.pixelreceipt.data.ingestion.ImportInput
import com.momonong.pixelreceipt.domain.model.EvidenceImportSource
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.io.File

class RealOcrUiTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test fun recognizeButtonOpensReviewWithAutomaticallyCreatedItem() {
        val bitmap = Bitmap.createBitmap(1500, 1000, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 48f; typeface = Typeface.MONOSPACE }
        listOf("EXAMPLE STORE", "2026/09/09", "ITEM QTY PRICE AMOUNT", "MILK 2 50 100", "TOTAL 100")
            .forEachIndexed { i, text -> canvas.drawText(text, 60f, 100f + i * 90f, paint) }
        val bytes = ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() }
        bitmap.recycle()
        val graph = ui.activity.application as ReceiptApplication
        val id = runBlocking { graph.importer.import(UUID.randomUUID().toString(), null,
            listOf(ImportInput { bytes.inputStream() }), EvidenceImportSource.Other) { _, _ -> }.draftId!! }
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("transaction-$id").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("transaction-$id").performClick()
        ui.onNodeWithTag("engine-ocr").performScrollTo().performClick()
        ui.onNodeWithTag("recognize-receipt").performScrollTo().performClick()
        ui.waitUntil(30_000) { ui.onAllNodesWithText("1. MILK").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("review-form").performScrollToIndex(0)
        ui.onNodeWithText("商家與日期 ＋").performClick()
        ui.onNodeWithTag("merchant").performScrollTo().assertTextContains("EXAMPLE STORE")
        ui.onNodeWithTag("review-form").performScrollToNode(hasText("修改品項 1"))
        ui.onNodeWithText("修改品項 1").performClick()
        ui.onNodeWithTag("line-name").performScrollTo().assertTextContains("MILK")
        ui.onNodeWithTag("line-quantity").performScrollTo().assertTextContains("2")
        ui.onNodeWithTag("line-amount").performScrollTo().assertTextContains("100")
        File(ui.activity.getExternalFilesDir(null), "receipt-ocr-review.png").outputStream().use {
            ui.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
