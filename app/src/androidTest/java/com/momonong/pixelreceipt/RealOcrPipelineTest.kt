package com.momonong.pixelreceipt

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.momonong.pixelreceipt.data.extraction.MlKitReceiptAnalyzer
import com.momonong.pixelreceipt.data.ingestion.*
import com.momonong.pixelreceipt.data.local.*
import com.momonong.pixelreceipt.domain.model.*
import com.momonong.pixelreceipt.domain.rules.*
import com.momonong.pixelreceipt.domain.usecase.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/** Real bundled ML Kit on Android, synthetic images only. No claim about real receipt accuracy. */
@RunWith(AndroidJUnit4::class)
class RealOcrPipelineTest {
    private fun bitmap(vararg rows: String): ByteArray {
        val bitmap = Bitmap.createBitmap(1500, 1100, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 48f; typeface = Typeface.MONOSPACE }
        rows.forEachIndexed { index, text -> canvas.drawText(text, 60f, 100f + index * 90f, paint) }
        return ByteArrayOutputStream().use { output -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); bitmap.recycle(); output.toByteArray() }
    }

    private suspend fun pipeline(images: List<ByteArray>, check: suspend (ReceiptDraft, ExtractReceipt, RoomReceiptRepository) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, ReceiptDatabase::class.java).build()
        val repository = RoomReceiptRepository(database)
        val directory = File(context.cacheDir, "ocr-test-${UUID.randomUUID()}")
        val store = ImageStore(directory)
        try {
            val importer = ReceiptImporter(database, repository, store)
            val result = importer.import(UUID.randomUUID().toString(), null, images.map { bytes -> ImportInput { bytes.inputStream() } }, EvidenceImportSource.Other) { _, _ -> }
            val draft = repository.observeDraft(requireNotNull(result.draftId)).first()!!
            val analyzer = MlKitReceiptAnalyzer(store)
            val useCase = ExtractReceipt(repository, analyzer, { repository.evidence(it).first() }, analyzer::verifyImage)
            check(draft, useCase, repository)
        } finally { database.close(); directory.deleteRecursively() }
    }

    @Test fun realOcrPhotoToReviewPersistenceAndUserConfirmation() = runBlocking {
        withTimeout(120_000) {
            pipeline(listOf(bitmap("EXAMPLE STORE", "2026/09/09", "ITEM QTY PRICE AMOUNT", "MILK   2   50   100", "TOTAL 100"))) { base, useCase, repo ->
                val draft = useCase.run(base, base.evidenceAssetIds, false) { true }
                assertEquals("EXAMPLE STORE", draft.merchant.inputText())
                assertEquals("2026-09-09", draft.transactionDate.inputText())
                assertEquals(1, draft.items.size)
                assertEquals("MILK", draft.items.single().rawName.inputText())
                assertEquals("2", draft.items.single().quantity.inputText())
                assertEquals("100", draft.items.single().printedTotal.inputText())
                assertEquals(draft, repo.observeDraft(draft.id).first())
                assertTrue(draft.extraction!!.regions.isNotEmpty())
                assertTrue(ReceiptReconciler().reconcile(draft) is ReceiptReconciliationResult.Indeterminate)
                val saved = ManualReceiptReview(repo).save(draft, ReviewInput.from(draft).copy(complete = true), 100) as ReviewSaveResult.Saved
                val confirmed = TransitionReceiptStage(repo)(saved.draft, ReceiptStage.Confirmed) as TransitionReceiptStageResult.Updated
                assertEquals(ReceiptStage.Confirmed, confirmed.draft.stage)
            }
        }
    }

    @Test fun realOcrMultiplePagesWithOverlapDoNotDuplicateItems() = runBlocking {
        withTimeout(120_000) {
            pipeline(listOf(bitmap("EXAMPLE STORE", "ITEM QTY PRICE AMOUNT", "MILK   2   50   100", "TOTAL 100", "PAGE 1"),
                bitmap("EXAMPLE STORE", "ITEM QTY PRICE AMOUNT", "MILK   2   50   100", "TOTAL 100", "PAGE 2"))) { base, useCase, _ ->
                val draft = useCase.run(base, base.evidenceAssetIds, false) { true }
                assertEquals(1, draft.items.size)
                assertEquals(ReceiptAssemblyStatus.PossibleDuplicates, draft.assemblyStatus)
                assertTrue(draft.items.single().quantity is Fact.Unknown)
                assertEquals(2, draft.extraction!!.sourceHashes.size)
            }
        }
    }

    @Test fun realOcrBlankPhotoDoesNotProduceSuccessOrLoseDraft() = runBlocking {
        withTimeout(120_000) {
            pipeline(listOf(bitmap())) { base, useCase, repo ->
                try { useCase.run(base, base.evidenceAssetIds, false) { true }; fail("Blank image must fail") }
                catch (_: IllegalStateException) { }
                assertEquals(base, repo.observeDraft(base.id).first())
            }
        }
    }

    @Test fun realChineseOcrMultipleItemsAndSeparateDiscount() = runBlocking {
        withTimeout(120_000) {
            pipeline(listOf(bitmap("範例商店", "2026/09/09", "品名 數量 單價 金額", "牛奶 2 50 100", "麵包 1 25 25", "折扣 -5", "總計 120"))) { base, useCase, _ ->
                val draft = useCase.run(base, base.evidenceAssetIds, false) { true }
                assertEquals("範例商店", draft.merchant.inputText())
                assertEquals(2, draft.items.size)
                assertEquals(listOf("牛奶", "麵包"), draft.items.map { it.rawName.inputText() })
                assertEquals(draft.extraction!!.regions.joinToString { it.rawText.orEmpty() }, listOf("2", "1"), draft.items.map { it.quantity.inputText() })
                assertEquals(listOf("100", "25"), draft.items.map { it.printedTotal.inputText() })
                assertEquals("120", draft.total.inputText())
                assertEquals("5", draft.adjustments.single().amount.inputText())
                assertTrue(draft.adjustments.single().scope is Fact.Unknown)
            }
        }
    }
}
