package com.momonong.pixelreceipt.data

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.momonong.pixelreceipt.data.ingestion.*
import com.momonong.pixelreceipt.data.local.*
import com.momonong.pixelreceipt.domain.model.*
import com.momonong.pixelreceipt.domain.port.DraftWriteResult
import com.momonong.pixelreceipt.domain.usecase.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import android.database.sqlite.SQLiteDatabase
import com.google.gson.JsonParser

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReceiptPersistenceTest {
    private lateinit var db: ReceiptDatabase
    private lateinit var repository: RoomReceiptRepository
    private lateinit var store: ImageStore
    private lateinit var importer: ReceiptImporter
    private lateinit var directory: File
    private lateinit var context: Context
    private lateinit var name: String

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        name = "${UUID.randomUUID()}.db"
        directory = File(context.cacheDir, UUID.randomUUID().toString()).apply { mkdirs() }
        reopen()
    }
    private fun reopen() {
        db = Room.databaseBuilder(context, ReceiptDatabase::class.java, name).build()
        repository = RoomReceiptRepository(db)
        store = ImageStore(directory)
        importer = ReceiptImporter(db, repository, store)
    }
    @After fun cleanup() { db.close(); directory.deleteRecursively(); context.deleteDatabase(name) }
    private fun png(color: Int = 0xffaabbcc.toInt()): ByteArray = ByteArrayOutputStream().use { output ->
        Bitmap.createBitmap(8, 12, Bitmap.Config.ARGB_8888).also { bitmap ->
            bitmap.eraseColor(color); bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); bitmap.recycle()
        }
        output.toByteArray()
    }
    private fun input(bytes: ByteArray) = ImportInput { bytes.inputStream() }
    private suspend fun batch(id: String, target: String? = null, vararg inputs: ImportInput) =
        importer.import(id, target, inputs.toList(), EvidenceImportSource.PhotoPicker)

    @Test fun mixedBatchAppendRetryAndSeparateTransactionsSurviveReopen() = runBlocking {
        val bytes = png()
        val first = batch("one", null, input(bytes), ImportInput { throw SecurityException() }, input(bytes), input(byteArrayOf(1, 2)))
        assertTrue(first.report, first.report.contains("新增 1 張，重複 1 張，失敗 2 張"))
        val draftId = first.draftId!!
        val before = repository.observeDraft(draftId).first()!!
        assertEquals(1, before.evidenceAssetIds.size)
        // A replay after Activity recreation must not even reopen a revoked URI.
        assertEquals(first, batch("one", null, ImportInput { error("URI must not be read again") }))
        val append = batch("two", draftId, input(bytes), input(png(0xff000000.toInt())))
        assertTrue(append.report.contains("新增 1 張，重複 1 張"))
        val after = repository.observeDraft(draftId).first()!!
        assertEquals(2, after.evidenceAssetIds.size)
        assertTrue(after.evidenceAssetIds.containsAll(before.evidenceAssetIds))
        assertEquals(1L, after.revision)
        val other = batch("three", null, input(bytes))
        assertNotEquals(draftId, other.draftId)
        assertEquals(2, directory.listFiles()!!.size) // Physical blob reuse only.
        db.close(); reopen(); importer.recover()
        assertEquals(after, repository.observeDraft(draftId).first())
        val assets = repository.evidence(draftId).first()
        assertEquals(2, assets.size)
        assets.forEach { assertTrue(it.kind is Fact.Unknown); assertNotNull(store.preview(it.contentSha256)) }
    }

    @Test fun casPreservesFactsAndRejectsStaleRevision() = runBlocking {
        val initial = ReceiptDraft("cas", merchant = Fact.Known("商店", FactProvenance.UserConfirmed(10)))
        assertEquals(DraftWriteResult.Written(0), repository.createDraft(initial))
        val next = initial.copy(revision = 1, total = Fact.Unknown(UnknownFactReason.MissingEvidence))
        assertEquals(DraftWriteResult.Written(1), repository.compareAndSetDraft(next, 0))
        assertEquals(DraftWriteResult.Conflict, repository.compareAndSetDraft(initial.copy(revision = 1), 0))
        assertEquals(next, repository.observeDraft("cas").first())
        assertEquals(DraftWriteResult.Conflict, repository.createDraft(initial))
        assertEquals(DraftWriteResult.NotFound, repository.compareAndSetDraft(ReceiptDraft("missing", revision = 1), 0))
    }

    @Test fun reviewPhotoAppendRetainsData() = runBlocking {
        val imported = batch("flow-first", null, input(png()))
        val captured = repository.observeDraft(imported.draftId!!).first()!!
        val opened = (TransitionReceiptStage(repository)(captured, ReceiptStage.NeedsReview) as TransitionReceiptStageResult.Updated).draft
        val form = ReviewInput("餐廳", total = "120", complete = true,
            lines = listOf(ReviewLineInput("meal", "午餐", "2", "120", opened.evidenceAssetIds)))
        val before = (ManualReceiptReview(repository).save(opened, form, 123) as ReviewSaveResult.Saved).draft
        batch("flow-duplicate", before.id, input(png()))
        assertEquals(before, repository.observeDraft(before.id).first())
        batch("flow-append", before.id, input(png(0xff123456.toInt())))
        val after = repository.observeDraft(before.id).first()!!
        assertEquals(before.items, after.items)
        assertEquals(before.evidenceLinks, after.evidenceLinks)
        assertEquals(before.merchant, after.merchant)
        assertEquals(before.transactionDate, after.transactionDate)
        assertEquals(before.total, after.total)
        assertEquals(before.extraction, after.extraction)
        assertEquals(before.revision + 1, after.revision)
        assertEquals(ReceiptStage.NeedsReview, after.stage)
        assertEquals(ReceiptAssemblyStatus.Collecting, after.assemblyStatus)
        assertEquals(2, after.evidenceAssetIds.size)
        assertTrue(TransitionReceiptStage(repository)(after, ReceiptStage.Confirmed) is TransitionReceiptStageResult.ConfirmationBlocked)
        db.close(); reopen()
        assertEquals(after, repository.observeDraft(after.id).first())
        assertTrue(repository.evidence(after.id).first().all { store.preview(it.contentSha256) != null })
        val completed = (ManualReceiptReview(repository).save(after, ReviewInput.from(after).copy(complete = true).withSelfExpenses(after), 124) as ReviewSaveResult.Saved).draft
        val confirmed = (TransitionReceiptStage(repository)(completed, ReceiptStage.Confirmed) as TransitionReceiptStageResult.Updated).draft
        val refused = batch("flow-confirmed", after.id, input(png(0xff222222.toInt())))
        assertEquals("interrupted", refused.state)
        assertEquals(confirmed, repository.observeDraft(after.id).first())
    }

    @Test fun manualReviewFromImportedPhotoSurvivesDatabaseReopenAndConfirmedReplay() = runBlocking {
        val imported = batch("manual-photo", null, input(png()))
        val captured = repository.observeDraft(imported.draftId!!).first()!!
        val opened = (TransitionReceiptStage(repository)(captured, ReceiptStage.NeedsReview) as TransitionReceiptStageResult.Updated).draft
        val refs = opened.evidenceAssetIds
        val form = ReviewInput("商店", "2026-09-08", "99", true, refs,
            listOf(ReviewLineInput("line", "商品", "1", "100", refs)),
            listOf(ReviewAdjustmentInput("coupon", "1", scope = "order", evidenceIds = refs)))
        val saved = (ManualReceiptReview(repository).save(opened, form.withSelfExpenses(opened), 42) as ReviewSaveResult.Saved).draft
        db.close(); reopen()
        assertEquals(saved, repository.observeDraft(saved.id).first())
        assertEquals(1, saved.personalExpenses.size)
        assertEquals(1, saved.expenseAdjustments.size)
        assertEquals(99L, com.momonong.pixelreceipt.domain.rules.PersonalExpenseCalculator.calculate(saved).personalMinor)
        assertTrue(repository.evidence(saved.id).first().all { store.preview(it.contentSha256) != null })
        val confirmed = (TransitionReceiptStage(repository)(saved, ReceiptStage.Confirmed) as TransitionReceiptStageResult.Updated).draft
        db.close(); reopen()
        assertEquals(confirmed, repository.observeDraft(saved.id).first())
        assertEquals(TransitionReceiptStageResult.Conflict, TransitionReceiptStage(repository)(saved, ReceiptStage.Confirmed))
        assertTrue(ManualReceiptReview(repository).save(confirmed, form.copy(total = "0"), 43) is ReviewSaveResult.Rejected)
        assertEquals(1, repository.drafts.first().size)
        assertEquals(3, confirmed.evidenceLinks.size)
    }

    @Test fun existingSqlSchemaAndLegacyPayloadUpgradeOnlyOnCasWrite() = runBlocking {
        val payload = javaClass.getResource("/legacy-draft-v1.json")!!.readText()
        db.receipts().insertDraft(DraftRow("legacy-review", 3, 10, payload))
        db.close(); reopen()
        val legacy = repository.observeDraft("legacy-review").first()!!
        assertTrue(legacy.transactionDate is Fact.Unknown)
        assertEquals(payload, db.receipts().draft(legacy.id)!!.payload)
        val opened = (TransitionReceiptStage(repository)(legacy, ReceiptStage.NeedsReview) as TransitionReceiptStageResult.Updated).draft
        assertEquals(4L, opened.revision)
        assertEquals(4, JsonParser.parseString(db.receipts().draft(legacy.id)!!.payload).asJsonObject["format"].asInt)
        assertEquals(DraftWriteResult.Conflict, repository.compareAndSetDraft(legacy.copy(revision = 4), 3))
        db.close(); reopen()
        assertEquals(opened, repository.observeDraft(legacy.id).first())
        assertEquals(1, db.openHelper.readableDatabase.version)
    }

    @Test fun confirmedV3PayloadRemainsUnclassifiedReadOnlyAndUnchangedAfterDatabaseReopen() = runBlocking {
        val payload = javaClass.getResource("/legacy-draft-v3-confirmed.json")!!.readText()
        db.receipts().insertDraft(DraftRow("legacy-review", 3, 10, payload))
        db.close(); reopen()
        val legacy = repository.observeDraft("legacy-review").first()!!
        assertEquals(ReceiptStage.Confirmed, legacy.stage)
        assertEquals("100", legacy.total.inputText())
        assertEquals(1, legacy.items.size)
        assertTrue(legacy.personalExpenses.isEmpty())
        assertFalse(com.momonong.pixelreceipt.domain.rules.PersonalExpenseCalculator.calculate(legacy).ready)
        assertTrue(ManualReceiptReview(repository).save(legacy, ReviewInput.from(legacy).withSelfExpenses(legacy), 200) is ReviewSaveResult.Rejected)
        assertEquals(payload, db.receipts().draft(legacy.id)!!.payload)
        assertEquals(3L, repository.observeDraft(legacy.id).first()!!.revision)
        assertEquals(1, db.openHelper.readableDatabase.version)
    }

    @Test fun allFailuresAndCancellationDoNotCreateDraftsOrLeaveFiles() = runBlocking {
        val failed = batch("bad", null, input(png().dropLast(8).toByteArray()), ImportInput { throw IOException() })
        assertNull(failed.draftId)
        assertTrue(repository.drafts.first().isEmpty())
        assertTrue(directory.listFiles()!!.isEmpty())
        try {
            batch("cancelled", null, input(png()), ImportInput { throw CancellationException() })
            fail("Expected cancellation")
        } catch (_: CancellationException) { /* Expected; the successful first copy was not committed. */ }
        assertTrue(repository.drafts.first().isEmpty())
        assertTrue(directory.listFiles()!!.isEmpty())
        assertEquals("interrupted", db.receipts().operation("cancelled")!!.state)
    }

    @Test fun startupRecoversCrashBetweenFilePublishAndDatabaseCommit() = runBlocking {
        val ok = batch("good", null, input(png()))
        val original = directory.listFiles()!!.single()
        File(directory, "orphan.part").writeText("partial")
        File(directory, "b".repeat(64)).writeText("uncommitted")
        db.receipts().insertOperation(ImportRow("crash", null, "running", "", 9))
        db.close(); reopen(); importer.recover()
        assertEquals(setOf(original.name), directory.listFiles()!!.map { it.name }.toSet())
        assertEquals("interrupted", db.receipts().operation("crash")!!.state)
        assertNotNull(repository.observeDraft(ok.draftId!!).first())
    }

    @Test fun concurrentEditRollsBackBatchAndGarbageCollectsUncommittedBlob() = runBlocking {
        val initial = ReceiptDraft("race")
        repository.createDraft(initial)
        importer.import("race-op", "race", listOf(input(png())), EvidenceImportSource.ShareSheet) { done, _ ->
            if (done == 1) runBlocking { repository.compareAndSetDraft(initial.copy(revision = 1), 0) }
        }.also { assertEquals("interrupted", it.state) }
        assertEquals(initial.copy(revision = 1), repository.observeDraft("race").first())
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun itemLimitEmptySelectionAndByteLimitAreEnforced() = runBlocking {
        assertNull(batch("empty").draftId)
        assertEquals("interrupted", batch("many", null, *Array(21) { input(png()) }).state)
        val result = batch("large", null, ImportInput { object : java.io.InputStream() {
            private var remaining = ImageStore.MAX_BYTES + 1
            override fun read(): Int = if (remaining-- > 0) 1 else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (remaining <= 0) return -1
                val count = minOf(remaining, len.toLong()).toInt()
                remaining -= count
                return count
            }
        } })
        assertNull(result.draftId)
        assertTrue(result.report.contains("20 MiB"))
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun exportedInitialSchemaOpensWithoutRebuildingAndRetainsRows() = runBlocking {
        db.close()
        val schema = requireNotNull(javaClass.classLoader!!.getResourceAsStream("com.momonong.pixelreceipt.data.local.ReceiptDatabase/1.json"))
            .bufferedReader().use { JsonParser.parseReader(it).asJsonObject["database"].asJsonObject }
        val path = context.getDatabasePath(name)
        path.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { sqlite ->
            for (entry in schema["entities"].asJsonArray) {
                val entity = entry.asJsonObject
                val table = entity["tableName"].asString
                fun sql(value: String) = value.replace("\${TABLE_NAME}", table)
                sqlite.execSQL(sql(entity["createSql"].asString))
                entity.getAsJsonArray("indices")?.forEach { sqlite.execSQL(sql(it.asJsonObject["createSql"].asString)) }
            }
            sqlite.execSQL("INSERT INTO drafts VALUES (?, ?, ?, ?)", arrayOf<Any>("schema-draft", 0, 1, DraftCodec().encode(ReceiptDraft("schema-draft"))))
            sqlite.version = 1 // No Room identity table: opening must validate the exported DDL.
        }
        reopen()
        assertEquals(ReceiptDraft("schema-draft"), repository.observeDraft("schema-draft").first())
        assertEquals(DraftWriteResult.Written(1), repository.compareAndSetDraft(ReceiptDraft("schema-draft", revision = 1), 0))
    }

    @Test fun unknownDatabaseVersionFailsWithoutDestructiveFallback() = runBlocking {
        repository.createDraft(ReceiptDraft("preserve"))
        db.close()
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { it.version = 2 }
        reopen()
        try {
            repository.observeDraft("preserve").first()
            fail("Missing migration must fail")
        } catch (_: IllegalStateException) { /* Expected: no 2 -> 1 downgrade is registered. */ }
        db.close()
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READONLY).use { sqlite ->
            sqlite.rawQuery("SELECT id FROM drafts", null).use { assertTrue(it.moveToFirst()); assertEquals("preserve", it.getString(0)) }
        }
    }

    @Test fun databaseInsertFailureRollsBackMetadataAndCleansPublishedFile() = runBlocking {
        withContext(Dispatchers.IO) {
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_draft BEFORE INSERT ON drafts BEGIN SELECT RAISE(ABORT, 'injected failure'); END")
        }
        val result = batch("sql-failure", null, input(png()))
        assertEquals("interrupted", result.state)
        assertTrue(repository.drafts.first().isEmpty())
        assertTrue(db.receipts().hashes().isEmpty())
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun originalBytesDigestAndDuplicateOnlyRevisionAreStable() = runBlocking {
        val bytes = png()
        val first = batch("original", null, input(bytes))
        val draftId = first.draftId!!
        val asset = repository.evidence(draftId).first().single()
        val expectedHash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals(expectedHash, asset.contentSha256)
        assertArrayEquals(bytes, store.file(asset.contentSha256).readBytes())
        val again = batch("duplicate-only", draftId, input(bytes))
        assertTrue(again.report.contains("新增 0 張，重複 1 張"))
        assertEquals(0L, repository.observeDraft(draftId).first()!!.revision)
        assertEquals(1, directory.listFiles()!!.size)
    }
}
