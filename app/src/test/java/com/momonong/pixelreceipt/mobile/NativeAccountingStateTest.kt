package com.momonong.pixelreceipt.mobile

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.google.gson.JsonObject
import com.momonong.pixelreceipt.data.ingestion.ImageStore
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.util.UUID

internal fun nativeDraft(id: String = UUID.randomUUID().toString(), revision: Long = 1): String = """{
 "id":"$id","revision":$revision,"status":"needs_review","ledger_id":null,"review_document":null,
 "candidate":{"schema":"pixelreceipt-central-candidate-1","currency":"TWD","amount_unit":"TWD_YUAN","merchant":"生活商店","receipt_date":"2026-10-23","printed_total_yuan":180,
 "items":[{"raw_name":"牙膏","quantity":2,"printed_unit_price_yuan":90,"printed_line_total_yuan":180,"brand":"保留品牌","specification":"120g","content_quantity":{"value_decimal":"120","unit":"g"}}],
 "adjustments":[{"raw_label":"會員價已折","direction":"subtract","amount_yuan":20,"amount_effect":"included_in_line_totals","scope":"receipt","item_positions":[]}]},
 "calculation":{"ready":false,"blockers":[{"code":"receipt_completeness_unconfirmed"}],"printed_total_yuan":180,"computed_total_yuan":180,"reconciliation_difference_yuan":0,"personal_yuan":180,"self_yuan":180,"gift_yuan":0,"advance_yuan":0},
 "evidence":[],"source":{"kind":"ai_candidate"},"original_candidate":{},"created_at":"2026-10-23T00:00:00Z","updated_at":"2026-10-23T00:00:00Z"
}"""

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class NativeAccountingStateTest {
    private lateinit var directory: File
    private lateinit var store: MobileStore
    private lateinit var api: NativeFixtureApi
    private lateinit var repo: MobileRepository
    private lateinit var batch: MobileBatch
    @Before fun setup() {
        directory = File(ApplicationProvider.getApplicationContext<Application>().cacheDir, UUID.randomUUID().toString()).apply { mkdirs() }
        store = MobileStore(directory)
        api = NativeFixtureApi()
        repo = MobileRepository(store, ImageStore(File(directory, "images")), MemoryVault().apply { save("prm_fixture") }, api)
        val initial = store.create(UUID.randomUUID().toString(), "原說明，牙膏幫朋友買")
        batch = store.update(initial.id) { it.copy(importComplete = true, status = "succeeded", intakeId = UUID.randomUUID().toString(), draftId = jsonObject(api.remote).text("id"), review = createReview(api.remote)) }
    }
    @After fun cleanup() { directory.deleteRecursively() }
    @Test fun onlyUnspecifiedPurposesDefaultToSelfAndIncludedDiscountIsNeverAllocated() {
        val initial = createReview(api.remote)
        assertEquals("self", initial.editor.array("line_decisions")[0].asJsonObject.text("whole_purpose"))
        assertEquals(0, initial.editor.array("adjustment_decisions").size())
        for (decision in listOf(wholeDecision(0, "gift"), wholeDecision(0, "advance"), jsonObject("""{"item_position":0,"method":"explicit_amounts","whole_purpose":null,"quantities":{"self":1,"gift":0,"advance":1},"amounts_yuan":{"self":90,"gift":0,"advance":90}}"""))) {
            val remote = jsonObject(api.remote).apply { add("review_document", initial.editor.deepCopy().apply { remove("candidate"); remove("review_note"); add("line_decisions", com.google.gson.JsonArray().apply { add(decision) }) }) }
            assertEquals(decision, createReview(remote.toString()).editor.array("line_decisions")[0])
        }
    }
    @Test fun renamePreservesBrandPackageAndSplitWhileFractionalAndOverflowInputsStayInvalid() {
        val review = batch.review!!
        val editor = review.editor
        val line = editor.getAsJsonObject("candidate").array("items")[0].asJsonObject
        line.addProperty("raw_name", "修正牙膏")
        line.addProperty("quantity", "1.5")
        val invalid = review.copy(editorJson = editor.toString())
        assertNull(prepareReview(invalid).body)
        assertTrue("item.0.quantity" in prepareReview(invalid).errors)
        line.addProperty("quantity", "2"); line.addProperty("printed_line_total_yuan", "9223372036854775808")
        assertNull(prepareReview(review.copy(editorJson = editor.toString())).body)
        line.addProperty("printed_line_total_yuan", "180")
        val saved = jsonObject(prepareReview(review.copy(editorJson = editor.toString())).body!!)
        assertEquals("保留品牌", saved.getAsJsonObject("candidate").array("items")[0].asJsonObject.text("brand"))
        assertEquals("120", saved.getAsJsonObject("candidate").array("items")[0].asJsonObject.getAsJsonObject("content_quantity").text("value_decimal"))
        assertEquals("unknown", saved.text("assembly_status"))
    }
    @Test fun partialInvalidTextSurvivesDiskReopenWithoutSendingAPut() = runBlocking {
        val editor = batch.review!!.editor.apply { getAsJsonObject("candidate").array("items")[0].asJsonObject.addProperty("quantity", "1.") }
        repo.persistReview(batch.id, batch.review!!.copy(editorJson = editor.toString(), editRevision = 1), 0)
        repo.saveReview(batch.id)
        assertEquals(0, api.puts.size)
        val reopened = MobileStore(directory).batch(batch.id).review!!
        assertEquals("1.", reopened.editor.getAsJsonObject("candidate").array("items")[0].asJsonObject.text("quantity"))
    }
    @Test fun unknownAutosaveGetsTheDraftBeforeRetryAndAdoptsAnAlreadyAcceptedRevision() = runBlocking {
        api.losePutResponse = true
        try { repo.saveReview(batch.id); fail() } catch (_: IOException) { }
        assertNotNull(MobileStore(directory).batch(batch.id).review!!.saveAttempt)
        val reopened = MobileRepository(MobileStore(directory), repo.images, MemoryVault().apply { save("prm_fixture") }, api)
        reopened.saveReview(batch.id)
        assertEquals(1, api.puts.size)
        assertTrue(api.gets > 0)
        assertFalse(MobileStore(directory).batch(batch.id).review!!.dirty)
    }
    @Test fun unknownFinalPutAndUnknownConfirmKeepOneFrozenIntentAcrossRecreation() = runBlocking {
        repo.saveReview(batch.id)
        api.losePutResponse = true
        try { repo.confirmReview(batch.id); fail() } catch (_: IOException) { }
        val frozen = MobileStore(directory).batch(batch.id).review!!.confirmation!!
        api.loseConfirmResponse = true
        val reopenedStore = MobileStore(directory)
        val reopened = MobileRepository(reopenedStore, repo.images, MemoryVault().apply { save("prm_fixture") }, api)
        try { reopened.confirmReview(batch.id); fail() } catch (_: IOException) { }
        assertEquals(frozen, MobileStore(directory).batch(batch.id).review!!.confirmation)
        reopened.confirmReview(batch.id)
        assertEquals(1, api.confirmations.size)
        assertEquals("confirmed", reopenedStore.batch(batch.id).draftStatus)
        assertEquals("active", reopenedStore.batch(batch.id).ledgerStatus)
        assertTrue(jsonObject(reopenedStore.load().ledgerJson!!).array("entries").size() > 0)
        reopened.confirmReview(batch.id)
        assertEquals(1, api.confirmations.size)
    }
    @Test fun historicalConfirmedAndVoidedRefreshUseFormalLedgerStateWithoutNewRecognition() = runBlocking {
        repo.saveReview(batch.id); repo.confirmReview(batch.id)
        api.entry = jsonObject(api.entry!!).apply { addProperty("status", "voided") }.toString()
        api.remote = jsonObject(api.remote).apply { addProperty("status", "voided") }.toString()
        repo.openReview(batch.id)
        assertEquals("voided", store.batch(batch.id).ledgerStatus)
        assertEquals(0, api.ingest.submissions.size)
        assertEquals("confirmed", ledgerObject(api.entry!!).getAsJsonObject("snapshot").text("status"))
    }
    @Test fun revisionConflictKeepsTheLocalEditorAndDoesNotSilentlyOverwriteTheRemote() = runBlocking {
        api.losePutResponse = true
        try { repo.saveReview(batch.id) } catch (_: IOException) { }
        api.remote = jsonObject(api.remote).apply { addProperty("revision", 8); getAsJsonObject("candidate").addProperty("merchant", "另一個編輯者") }.toString()
        try { repo.saveReview(batch.id); fail() } catch (_: MobileFailure) { }
        assertTrue(store.batch(batch.id).review!!.conflict)
        assertEquals("生活商店", store.batch(batch.id).review!!.editor.getAsJsonObject("candidate").text("merchant"))
        assertEquals(1, api.puts.size)
    }
    @Test fun fractionalOverflowAndWrongTypedServerIntegersAreRejectedBeforeAnyMutation() {
        for (invalid in listOf("1.5", "9223372036854775808", "\"2\"")) {
            val corrupt = api.remote.replace("\"revision\":1", "\"revision\":$invalid")
            try { draftObject(corrupt); fail("Invalid revision must be rejected: $invalid") } catch (_: Exception) { }
        }
        val corrupt = jsonObject(api.remote).apply { getAsJsonObject("candidate").array("items")[0].asJsonObject.addProperty("quantity", 1.5) }
        try { draftObject(corrupt.toString()); fail() } catch (_: Exception) { }
    }
    @Test fun savedStateRecoveryRetainsPartialTextUntilTheRealDiskWriteAcknowledgesIt() = runBlocking {
        val handle = SavedStateHandle()
        val edits = MobileReviewEdits(handle)
        edits.edit(batch) { it.getAsJsonObject("candidate").array("items")[0].asJsonObject.addProperty("quantity", "1.") }
        val recovered = MobileReviewEdits(SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) }))
        assertTrue(recovered.pending(batch.id))
        assertNotNull(recovered.state.value[batch.id]!!.error)
        assertEquals("1.", recovered.overlay(store.load())!!.batches.single().review!!.editor.getAsJsonObject("candidate").array("items")[0].asJsonObject.text("quantity"))
        recovered.persist(batch.id, retry = true) { repo.persistReview(it.id, it.value, it.expectedRevision) }
        assertFalse(recovered.pending(batch.id))
        assertEquals("1.", MobileStore(directory).batch(batch.id).review!!.editor.getAsJsonObject("candidate").array("items")[0].asJsonObject.text("quantity"))
    }
    @Test fun unknownUncommittedPutGetsBeforeRepeatingTheSameBodyAndRevision() = runBlocking {
        api.failPutBeforeCommit = true
        try { repo.saveReview(batch.id); fail() } catch (_: IOException) { }
        val frozen = MobileStore(directory).batch(batch.id).review!!.saveAttempt!!
        repo.saveReview(batch.id)
        assertEquals(listOf(frozen.body, frozen.body), api.putRequests)
        assertEquals(listOf("PUT", "GET", "PUT"), api.events)
    }
    @Test fun uncommittedConfirmationRetryPreservesKeyAndBodyAfterReopen() = runBlocking {
        repo.saveReview(batch.id); api.failConfirmBeforeCommit = true
        try { repo.confirmReview(batch.id); fail() } catch (_: IOException) { }
        val reopened = MobileRepository(MobileStore(directory), repo.images, MemoryVault().apply { save("prm_fixture") }, api)
        reopened.confirmReview(batch.id)
        assertEquals(2, api.confirmRequests.size)
        assertEquals(api.confirmRequests[0], api.confirmRequests[1])
        assertEquals("confirmed", MobileStore(directory).batch(batch.id).draftStatus)
    }
    @Test fun readingAConflictNeverRebasesOrWritesUntilAnExplicitVersionChoice() = runBlocking {
        val current = batch.review!!
        store.update(batch.id) { it.copy(review = current.copy(conflict = true)) }
        api.remote = jsonObject(api.remote).apply { addProperty("revision", 3); getAsJsonObject("candidate").addProperty("merchant", "中央新名稱") }.toString()
        repo.compareReview(batch.id)
        assertEquals(1, store.batch(batch.id).review!!.draft["revision"].asInt)
        assertEquals(0, api.puts.size)
        repo.resolveReview(batch.id, useLocal = false)
        assertEquals("中央新名稱", store.batch(batch.id).review!!.editor.getAsJsonObject("candidate").text("merchant"))
        assertEquals(current.editorJson, store.batch(batch.id).review!!.retainedEditorJson)
    }
    @Test fun splitInputsPreserveFullQuantitiesAndExplicitAmountsAndDoNotAllocateIncludedDiscounts() {
        val editor = batch.review!!.editor
        editor.array("line_decisions").set(0, jsonObject("""{"item_position":0,"method":"explicit_amounts","whole_purpose":null,"quantities":{"self":"1","gift":"0","advance":"1"},"amounts_yuan":{"self":"90","gift":"0","advance":"90"}}"""))
        val request = jsonObject(prepareReview(batch.review!!.copy(editorJson = editor.toString())).body!!)
        assertEquals(1, request.array("line_decisions")[0].asJsonObject.getAsJsonObject("quantities")["advance"].asInt)
        assertEquals(90, request.array("line_decisions")[0].asJsonObject.getAsJsonObject("amounts_yuan")["advance"].asInt)
        assertEquals(0, request.array("adjustment_decisions").size())
        editor.array("line_decisions")[0].asJsonObject.getAsJsonObject("quantities").addProperty("gift", "")
        assertNull(prepareReview(batch.review!!.copy(editorJson = editor.toString())).body)
    }
    @Test fun openingADeepLedgerDetailDoesNotChangeTheServerPageMembershipOrLoseDetailOnRefresh() {
        fun entry(index: Int): JsonObject {
            val draft = jsonObject(nativeDraft()); val id = UUID.nameUUIDFromBytes("entry-$index".toByteArray()).toString()
            draft.addProperty("status", "confirmed"); draft.addProperty("ledger_id", id)
            return JsonObject().apply { addProperty("id", id); addProperty("draft_id", draft.text("id")); addProperty("revision", 1); addProperty("status", "active"); add("snapshot", draft) }
        }
        val page = JsonObject().apply { add("entries", com.google.gson.JsonArray().apply { (0 until 20).forEach { add(entry(it)) } }); addProperty("offset", 0); addProperty("total_count", 80) }
        store.cacheLedger(page.toString())
        val detail = entry(50); store.cacheEntry(detail.toString())
        assertEquals(20, jsonObject(store.load().ledgerJson!!).array("entries").size())
        assertEquals(20, store.load().ledgerNextOffset)
        store.cacheLedger(page.toString())
        assertEquals(20, jsonObject(store.load().ledgerJson!!).array("entries").size())
        assertEquals(detail, jsonObject(store.load().ledgerDetailsJson!!)[detail.text("id")])
        val latest = detail.deepCopy().apply { addProperty("status", "voided") }
        store.cacheEntry(latest.toString())
        assertEquals("voided", jsonObject(store.load().ledgerDetailsJson!!).getAsJsonObject(detail.text("id")).text("status"))
        val overlap = page.deepCopy().apply { addProperty("offset", 20); array("entries")[0].asJsonObject.addProperty("status", "voided") }
        store.cacheLedger(overlap.toString(), append = true)
        assertEquals("voided", jsonObject(store.load().ledgerJson!!).array("entries")[0].asJsonObject.text("status"))
        assertEquals(40, store.load().ledgerNextOffset)
    }
    @Test fun aPreflightRevisionConflictReleasesAnUnsentFinalIntentForExplicitResolution() = runBlocking {
        repo.saveReview(batch.id)
        api.remote = jsonObject(api.remote).apply { addProperty("revision", 3); getAsJsonObject("candidate").addProperty("merchant", "另一台更新") }.toString()
        try { repo.confirmReview(batch.id); fail() } catch (_: MobileFailure) { }
        val current = store.batch(batch.id).review!!
        assertNull("No mutation was dispatched, so this must be safely resolvable", current.confirmation)
        assertTrue(current.conflict)
        assertEquals(0, api.confirmRequests.size)
        assertEquals(1, api.puts.size)
        repo.compareReview(batch.id); repo.resolveReview(batch.id, useLocal = true)
        assertFalse(store.batch(batch.id).review!!.conflict)
    }
    @Test fun unknownDispatchedConfirmationKeepsItsFrozenBodyWhenTheRemoteRevisionChangesAfterReopen() = runBlocking {
        repo.saveReview(batch.id); api.failConfirmBeforeCommit = true
        try { repo.confirmReview(batch.id); fail() } catch (_: IOException) { }
        val frozen = MobileStore(directory).batch(batch.id).review!!.confirmation!!
        api.remote = jsonObject(api.remote).apply { addProperty("revision", 8); getAsJsonObject("candidate").addProperty("merchant", "中央另一版本") }.toString()
        val reopenedStore = MobileStore(directory)
        val reopened = MobileRepository(reopenedStore, repo.images, MemoryVault().apply { save("prm_fixture") }, api)
        try { reopened.confirmReview(batch.id); fail() } catch (_: MobileFailure) { }
        assertTrue(reopenedStore.batch(batch.id).review!!.confirmationDispatched)
        assertEquals(frozen, reopenedStore.batch(batch.id).review!!.confirmation)
        reopened.compareReview(batch.id)
        try { reopened.resolveReview(batch.id, true); fail() } catch (_: MobileFailure) { }
        assertEquals(1, api.confirmRequests.size)
        assertEquals(frozen.key, reopenedStore.batch(batch.id).review!!.confirmation!!.key)
        assertEquals(frozen.body, reopenedStore.batch(batch.id).review!!.confirmation!!.body)
    }
    @Test fun frozenConfirmationIsNotAllowedToChangeAfterItWasPersisted() {
        val final = FrozenConfirmation(UUID.randomUUID().toString(), "{\"expected_revision\":2}", "{}", 1)
        store.update(batch.id) { it.copy(review = it.review!!.copy(confirmation = final)) }
        try {
            store.update(batch.id) { it.copy(review = it.review!!.copy(confirmation = final.copy(key = UUID.randomUUID().toString()))) }
            fail("A pending confirmation must be immutable")
        } catch (_: IllegalStateException) { }
    }
}

internal class NativeFixtureApi(val ingest: FakeMobileApi = FakeMobileApi()) : MobileApi by ingest {
    var remote = nativeDraft()
    var entry: String? = null
    var gets = 0
    var failPutBeforeCommit = false
    var failConfirmBeforeCommit = false
    val putRequests = mutableListOf<String>()
    val confirmRequests = mutableListOf<FrozenConfirmation>()
    val events = mutableListOf<String>()
    val puts = mutableListOf<String>()
    val confirmations = mutableListOf<FrozenConfirmation>()
    var losePutResponse = false
    var loseConfirmResponse = false
    override suspend fun draft(token: String, id: String): String { gets++; events += "GET"; return remote }
    override suspend fun review(token: String, id: String, body: String): String {
        putRequests += body; events += "PUT"
        if (failPutBeforeCommit) { failPutBeforeCommit = false; throw IOException("fixture before commit") }
        val request = jsonObject(body); val value = jsonObject(remote)
        if (request["expected_revision"].asLong != value["revision"].asLong) throw MobileApiFailure(409, "revision_conflict")
        puts += body
        value.add("candidate", request["candidate"])
        value.add("review_document", request.deepCopy().apply { remove("candidate"); remove("expected_revision"); remove("review_note") })
        value.addProperty("revision", value["revision"].asLong + 1)
        val complete = request.text("assembly_status") == "complete_by_user"
        value.getAsJsonObject("calculation").apply { addProperty("ready", complete); if (complete) add("blockers", com.google.gson.JsonArray()) }
        remote = value.toString()
        if (losePutResponse) { losePutResponse = false; throw IOException("fixture lost response") }
        return remote
    }
    override suspend fun confirm(token: String, id: String, attempt: FrozenConfirmation): String {
        confirmRequests += attempt
        if (failConfirmBeforeCommit) { failConfirmBeforeCommit = false; throw IOException("fixture before confirm commit") }
        entry?.let { return it }
        confirmations += attempt
        val ledgerId = UUID.randomUUID().toString()
        val value = jsonObject(remote).apply { addProperty("revision", get("revision").asLong + 1); addProperty("status", "confirmed"); addProperty("ledger_id", ledgerId) }
        remote = value.toString()
        entry = JsonObject().apply { addProperty("id", ledgerId); addProperty("draft_id", id); addProperty("revision", 1); addProperty("status", "active"); add("snapshot", value); addProperty("confirmed_at", "2026-10-23"); add("events", com.google.gson.JsonArray()) }.toString()
        if (loseConfirmResponse) { loseConfirmResponse = false; throw IOException("fixture lost confirm") }
        return entry!!
    }
    override suspend fun ledgerDetail(token: String, id: String) = entry!!
    override suspend fun ledger(token: String, offset: Int) = """{"entries":[${entry.orEmpty()}],"total_count":${if (entry == null) 0 else 1}}"""
}
