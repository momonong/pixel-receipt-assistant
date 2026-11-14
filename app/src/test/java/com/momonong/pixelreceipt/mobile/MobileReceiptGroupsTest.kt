package com.momonong.pixelreceipt.mobile

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.momonong.pixelreceipt.data.ingestion.ImageStore
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

internal fun groupedResponse(rootId: String, children: List<String>): String = JsonObject().apply {
    addProperty("id", rootId); addProperty("status", "succeeded"); add("draft_id", com.google.gson.JsonNull.INSTANCE)
    add("receipts", JsonArray().apply { children.forEachIndexed { index, id -> add(JsonObject().apply {
        addProperty("receipt_id", "r${index + 1}"); addProperty("label", if (index == 0) "左側收據" else "右側收據")
        addProperty("intake_id", id); addProperty("status", "succeeded"); addProperty("draft_id", UUID.randomUUID().toString())
        addProperty("draft_status", "needs_review"); addProperty("purpose", if (index == 0) "advance" else "self")
        add("regions", JsonArray().apply { add(JsonObject().apply { addProperty("image_position", 1); add("box", JsonArray().apply { listOf(index * 500, 0, (index + 1) * 500, 1000).forEach(::add) }) }) })
    }) } })
}.toString()

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class MobileReceiptGroupsTest {
    private lateinit var directory: File
    private lateinit var store: MobileStore
    private lateinit var parent: MobileBatch
    private lateinit var response: String
    private val rootId = UUID.randomUUID().toString()
    private val children = List(2) { UUID.randomUUID().toString() }
    @Before fun setup() {
        directory = File(ApplicationProvider.getApplicationContext<Application>().cacheDir, UUID.randomUUID().toString()).apply { mkdirs() }
        store = MobileStore(directory)
        val initial = store.create(UUID.randomUUID().toString(), "原說明：左邊代買、右邊自己用")
        parent = store.update(initial.id) { it.copy(images = listOf(MobileImage("a".repeat(64), "image/png", 1)), importComplete = true) }
        parent = store.freeze(parent.id)
        response = groupedResponse(rootId, children)
    }
    @After fun cleanup() { directory.deleteRecursively() }
    private fun apply(raw: String = response): MobileBatch = runBlocking {
        val result = HttpsMobileApi(MobileTransport { raw }).status("fixture", rootId)
        store.applyIntake(parent.id, result)
    }
    @Test fun singleChildStillUsesGroupedReviewAndEmptyLaterPollingKeepsMembership() {
        val one = jsonObject(response).apply { array("receipts").remove(1) }.toString()
        val group = apply(one)
        assertEquals(listOf(children[0]), group.receiptIds)
        val stale = jsonObject(one).apply { remove("receipts") }.toString()
        assertEquals(group.receiptIds, apply(stale).receiptIds)
        assertEquals(2, store.load().batches.size)
    }
    @Test fun parserPreservesOrderedValidatedChildrenAndLegacyResponses() = runBlocking {
        val result = HttpsMobileApi(MobileTransport { response }).status("fixture", rootId)
        assertEquals(children, result.receipts.map { it.intakeId })
        assertEquals("advance", result.receipts[0].info.purpose)
        assertEquals(listOf(500, 0, 1000, 1000), result.receipts[1].info.regions.single().box)
        assertNull(result.draftId)
        for (extra in listOf("", ",\"receipts\":[]", ",\"receipts\":null")) {
            val legacy = HttpsMobileApi(MobileTransport { """{"id":"$rootId","status":"queued","draft_id":null$extra}""" }).status("fixture", rootId)
            assertTrue(legacy.receipts.isEmpty())
        }
    }
    @Test fun untrustedRegionsIdsAndStatusesAreRejectedBeforePersistence() = runBlocking {
        val corruptions: List<(JsonObject) -> Unit> = listOf(
            { it.addProperty("intake_id", rootId) }, { it.addProperty("intake_id", "bad") },
            { it.addProperty("status", "confirmed") }, { it.addProperty("purpose", "invented") },
            { it.array("regions")[0].asJsonObject.add("box", jsonObject("""{"b":[0,0,1001,1000]}""").array("b")) },
            { it.array("regions")[0].asJsonObject.addProperty("image_position", 1.5) },
        )
        corruptions.forEach { mutate ->
            val bad = jsonObject(response); mutate(bad.array("receipts")[0].asJsonObject)
            try { HttpsMobileApi(MobileTransport { bad.toString() }).status("fixture", rootId); fail("Invalid child accepted") } catch (_: IllegalStateException) { }
        }
        assertEquals(1, store.load().batches.size)
    }
    @Test fun atomicMaterializationReusesChildrenAndPreservesIndependentEditsAndOriginalFrozenIntent() {
        val group = apply()
        assertEquals(children, group.receiptIds)
        val first = store.batch(children[0]); val second = store.batch(children[1])
        assertEquals(parent.images, first.images); assertEquals(parent.userNotes, second.userNotes)
        assertEquals(parent.createdAt, first.createdAt); assertFalse(first.editable)
        children.forEachIndexed { index, id ->
            val draft = nativeDraft(store.batch(id).draftId!!)
            val review = createReview(draft)
            val editor = review.editor.apply { getAsJsonObject("candidate").addProperty("merchant", "獨立店家$index") }
            store.update(id) { it.copy(review = review.copy(editorJson = editor.toString(), editRevision = 1)) }
        }
        val pending = ReceiptMutation("intake_delete", children[0], children[0], UUID.randomUUID().toString(), """{"expected_draft_revision":1,"reason":"原請求"}""")
        store.recordMutation(pending)
        val before = store.load().batches.associateBy { it.id }
        repeat(3) { apply() }
        val reopened = MobileStore(directory).load()
        assertEquals(3, reopened.batches.size)
        assertEquals(listOf(pending), reopened.mutations)
        children.forEach { id -> assertEquals(before[id]!!.review, reopened.batches.first { it.id == id }.review) }
        assertEquals(parent.frozen, store.batch(parent.id).frozen)
        assertEquals(parent.images, store.batch(parent.id).images)
        assertEquals(parent.userNotes, store.batch(parent.id).userNotes)
        assertEquals(listOf(parent.id), reopened.batches.filter { it.parentBatchId == null }.map { it.id })
    }
    @Test fun childConfirmationVoidDeletionAndPendingMutationNeverCompleteParentEarly() {
        val group = apply()
        assertEquals(ReceiptStatus.NeedsReview, groupedReceiptStatus(group, store.load().batches, emptyList()))
        children.forEach { id -> store.update(id) { it.copy(draftStatus = "confirmed", ledgerStatus = "active", ledgerId = UUID.randomUUID().toString()) } }
        assertEquals(ReceiptStatus.Completed, groupedReceiptStatus(group, store.load().batches, emptyList()))
        store.update(children[1]) { it.copy(draftStatus = "voided", ledgerStatus = "voided") }
        val voided = store.load().batches
        assertEquals(1, group.groupSummary(voided).confirmed); assertEquals(1, group.groupSummary(voided).voided)
        assertEquals(ReceiptStatus.GroupIncomplete, groupedReceiptStatus(group, voided, emptyList()))
        // An older root snapshot cannot resurrect a voided child's accounting state.
        apply(); assertEquals("voided", store.batch(children[1]).ledgerStatus)
        store.update(children[0]) { it.copy(status = "deleted", draftStatus = "deleted", deletedAt = 1) }
        apply(); assertTrue(store.batch(children[0]).deleted)
        assertEquals(1, group.groupSummary(store.load().batches).deleted)
        val pending = ReceiptMutation("ledger_void", store.batch(children[1]).ledgerId!!, children[1], UUID.randomUUID().toString(), """{"expected_revision":1,"reason":"未註明"}""")
        assertNotEquals(ReceiptStatus.Completed, groupedReceiptStatus(group, store.load().batches, listOf(pending)))
    }
    @Test fun failedParentRetainsSuccessfulChildAndDoesNotTreatAllKnownConfirmedAsCoverageComplete() {
        val failed = jsonObject(response).apply { addProperty("status", "failed"); addProperty("error_code", "multi_receipt_partial"); array("receipts")[1].asJsonObject.addProperty("status", "failed") }.toString()
        val group = apply(failed)
        assertEquals("succeeded", store.batch(children[0]).status)
        children.forEach { id -> store.update(id) { it.copy(draftStatus = "confirmed", ledgerStatus = "active") } }
        assertEquals(ReceiptStatus.Failed, groupedReceiptStatus(group, store.load().batches, emptyList()))
    }
    @Test fun rootPollingProjectsChildDeletionJournalAndInvalidatesChangedAccounting() {
        apply()
        val child = store.batch(children[0])
        val attempt = ReceiptMutation("intake_delete", children[0], child.id, UUID.randomUUID().toString(), """{"expected_draft_revision":1,"reason":"原請求"}""")
        store.recordMutation(attempt)
        store.accountingRefresh(false)
        val changed = jsonObject(response).apply {
            array("receipts")[0].asJsonObject.apply { addProperty("status", "deleted"); addProperty("draft_status", "deleted") }
            array("receipts")[1].asJsonObject.apply { addProperty("draft_status", "confirmed"); addProperty("ledger_status", "active"); addProperty("ledger_id", UUID.randomUUID().toString()) }
        }.toString()
        apply(changed)
        assertTrue(store.batch(child.id).deleted)
        assertEquals("completed", store.load().mutations!!.single().phase)
        assertEquals(true, store.load().accountingStale)
    }
    @Test fun legacyStateWithoutNewFieldsRetainsUnsentNotesPhotosAndPendingWrite() {
        val legacy = Gson().toJsonTree(store.load()).asJsonObject
        val batch = legacy.array("batches")[0].asJsonObject
        listOf("parentBatchId", "receiptIds", "receiptInfo").forEach(batch::remove)
        File(directory, "state.json").writeText(legacy.toString())
        val old = MobileStore(directory).load().batches.single()
        assertEquals(parent, old); assertFalse(old.isReceiptGroup)
    }
    @Test fun parentDeleteIsBlockedLocallyAndWhenChildrenFirstAppearDuringDeleteGet() = runBlocking {
        var posts = 0
        val api = HttpsMobileApi(MobileTransport { request ->
            if (request.method == "POST") posts++
            response
        })
        val repo = MobileRepository(store, ImageStore(File(directory, "images")), MemoryVault().apply { save("fixture") }, api)
        store.update(parent.id) { it.copy(intakeId = rootId) }
        try { repo.deleteBatch(parent.id); fail() } catch (error: MobileFailure) { assertEquals(GROUP_DELETE_EXPLANATION, error.userMessage) }
        try { repo.deleteBatch(parent.id); fail() } catch (error: MobileFailure) { assertEquals(GROUP_DELETE_EXPLANATION, error.userMessage) }
        assertEquals(0, posts); assertTrue(store.load().mutations.isNullOrEmpty())
        assertFalse(store.batch(parent.id).deleted)
    }
}
