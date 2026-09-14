package com.momonong.pixelreceipt.domain.usecase

import com.momonong.pixelreceipt.data.ingestion.AssistantReceiptText
import com.momonong.pixelreceipt.data.local.DraftCodec
import com.momonong.pixelreceipt.domain.model.*
import com.momonong.pixelreceipt.domain.port.*
import com.momonong.pixelreceipt.domain.rules.ReceiptReconciler
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ImportAssistantReceiptTest {
    private val raw = """{"schema":"pixelreceipt-1","currency":"TWD","merchant":"測試商店","date":"2026-09-14","totalMinor":450,"items":[{"name":"茶","quantity":null,"lineTotalMinor":300},{"name":"茶","quantity":1,"lineTotalMinor":150}],"adjustments":[]}"""
    private class Repository : ReceiptRepository {
        val entries = mutableMapOf<String, ReceiptDraft>()
        override fun observeDraft(id: String) = flowOf(entries[id])
        override suspend fun createDraft(draft: ReceiptDraft): DraftWriteResult {
            if (entries.containsKey(draft.id)) return DraftWriteResult.Conflict
            entries[draft.id] = draft
            return DraftWriteResult.Written(0)
        }
        override suspend fun compareAndSetDraft(draft: ReceiptDraft, expectedRevision: Long): DraftWriteResult = error("Import must never update")
    }
    @Test fun candidatesCreatePopulatedReviewDraftWithoutForgedPhotosOwnershipOrConfirmation() = runBlocking {
        val repo = Repository()
        val draft = ImportAssistantReceipt(repo).create(AssistantReceiptText.parse(raw), raw, "request-1", AssistantImportChannel.SharedText, 50)
        assertEquals(ReceiptStage.NeedsReview, draft.stage)
        assertEquals(ReceiptAssemblyStatus.Collecting, draft.assemblyStatus)
        assertEquals(0L, draft.revision)
        assertEquals(2, draft.items.size)
        assertTrue(draft.items[0].quantity is Fact.Unknown)
        assertTrue(draft.personalExpenses.isEmpty() && draft.evidenceAssetIds.isEmpty() && draft.evidenceLinks.isEmpty())
        assertTrue((draft.items[0].printedTotal as Fact.Known).provenance is FactProvenance.AssistantSuggested)
        assertNull(draft.extraction)
        assertEquals(raw, draft.assistantImport!!.rawInput)
        assertEquals(draft, DraftCodec().decode(DraftCodec().encode(draft)))
    }
    @Test fun retryPreservesHumanEditsAndConfirmedStateAndDifferentRequestsStaySeparate() = runBlocking {
        val repo = Repository()
        val importer = ImportAssistantReceipt(repo)
        val input = AssistantReceiptText.parse(raw)
        val first = importer.create(input, raw, "request-1", AssistantImportChannel.AppFunction, 50)
        val edited = first.copy(revision = 8, stage = ReceiptStage.Confirmed,
            merchant = Fact.Known("人工修正商家", FactProvenance.UserConfirmed(70)))
        repo.entries[first.id] = edited
        assertEquals(edited, importer.create(input, raw, "request-1", AssistantImportChannel.AppFunction, 90))
        val second = importer.create(input, raw, "request-2", AssistantImportChannel.AppFunction, 90)
        assertNotEquals(first.id, second.id)
        assertEquals(2, repo.entries.size)
        try { importer.create(input, raw + " ", "request-1", AssistantImportChannel.AppFunction, 90); fail() }
        catch (_: IllegalArgumentException) { }
        assertEquals(edited, repo.entries[first.id])
    }
    @Test fun inconsistentTotalsAndUnknownDiscountScopeAreNotRepairedByTheImporter() = runBlocking {
        val input = AssistantReceiptText.parse(raw).copy(totalMinor = 1,
            adjustments = listOf(AssistantReceiptAdjustment("折扣", "subtract", 10)))
        val draft = ImportAssistantReceipt(Repository()).create(input, "test structured input", "one", AssistantImportChannel.AppFunction, 1)
        assertEquals(1L, (draft.total as Fact.Known).value.minorUnits)
        assertTrue(draft.adjustments.single().scope is Fact.Unknown)
        assertFalse(ReceiptReconciler().reconcile(draft) is com.momonong.pixelreceipt.domain.rules.ReceiptReconciliationResult.Balanced)
    }
    @Test fun invalidStructuredParametersNeverWrite() = runBlocking {
        val repo = Repository()
        val input = AssistantReceiptText.parse(raw)
        listOf(input.copy(currency = "USD"), input.copy(items = emptyList()),
            input.copy(totalMinor = -1), input.copy(date = "2026-02-30"),
            input.copy(items = listOf(AssistantReceiptItem("茶", 0, 1)))).forEach {
            try { ImportAssistantReceipt(repo).create(it, raw, "one", AssistantImportChannel.AppFunction); fail() }
            catch (_: IllegalArgumentException) { }
        }
        assertTrue(repo.entries.isEmpty())
    }
}
