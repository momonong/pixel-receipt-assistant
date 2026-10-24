package com.momonong.pixelreceipt.mobile

import android.app.Application
import androidx.test.core.app.ApplicationProvider
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
import java.io.IOException
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ReceiptActionsStateTest {
    private lateinit var directory: File
    private lateinit var store: MobileStore
    private lateinit var api: ReceiptActionsApi
    private lateinit var repo: MobileRepository
    private lateinit var batch: MobileBatch
    @Before fun setup() {
        directory = File(ApplicationProvider.getApplicationContext<Application>().cacheDir, UUID.randomUUID().toString()).apply { mkdirs() }
        store = MobileStore(directory); api = ReceiptActionsApi()
        repo = repository(store)
        batch = store.create(UUID.randomUUID().toString(), "合成收據")
        batch = store.update(batch.id) { it.copy(importComplete = true) }
    }
    private fun repository(local: MobileStore) = MobileRepository(local, ImageStore(File(directory, "images")), MemoryVault().apply { save("prm_fixture") }, api)
    private fun remoteBatch() {
        batch = store.update(batch.id) { it.copy(intakeId = api.intakeId, draftId = api.draftId, status = "succeeded", draftStatus = "needs_review", review = createReview(api.remote)) }
    }
    @After fun cleanup() { directory.deleteRecursively() }
    @Test fun localDeleteIsDurableAndCannotBeResurrectedOrSubmitRecognition() = runBlocking {
        repo.deleteBatch(batch.id)
        assertTrue(MobileStore(directory).batch(batch.id).deleted)
        assertFalse(store.batch(batch.id).editable)
        assertTrue(repo.sync(batch.id).deleted)
        try { store.update(batch.id) { it.copy(deletedAt = null, status = null) }; fail() } catch (_: IllegalStateException) { }
        assertEquals(0, api.submissions)
        assertEquals(0, api.deletes.size)
    }
    @Test fun frozenIntakeWithoutCentralIdMustNeverSubmitToDiscoverAnIdForDeletion() = runBlocking {
        store.update(batch.id) { it.copy(frozen = FrozenIntake(UUID.randomUUID().toString(), "{}", sha256("{}".toByteArray()))) }
        try { repo.deleteBatch(batch.id); fail() } catch (error: MobileFailure) { assertTrue(error.userMessage.contains("原收件")) }
        assertEquals(0, api.submissions); assertFalse(store.batch(batch.id).deleted)
    }
    @Test fun unresolvedReviewSaveOrConfirmationBlocksDeletion() = runBlocking {
        remoteBatch()
        store.update(batch.id) { it.copy(review = it.review!!.copy(saveAttempt = FrozenReviewSave("{\"expected_revision\":1}", 1, 0))) }
        try { repo.deleteBatch(batch.id); fail() } catch (_: MobileFailure) { }
        assertEquals(0, api.deletes.size)
    }
    @Test fun lostAcceptedDeleteResponseReconcilesOriginalIntakeAcrossRestartWithoutAnotherPost() = runBlocking {
        remoteBatch(); api.loseResponse = true
        try { repo.deleteBatch(batch.id); fail() } catch (_: IOException) { }
        val pending = MobileStore(directory).load().mutations!!.single()
        assertEquals("pending", pending.phase)
        repository(MobileStore(directory)).deleteBatch(batch.id)
        assertEquals(1, api.deletes.size)
        assertTrue(MobileStore(directory).batch(batch.id).deleted)
        assertEquals(pending.key, MobileStore(directory).load().mutations!!.single().key)
    }
    @Test fun unknownUncommittedDeleteRepeatsIdenticalKeyBodyAndBlocksReviewAndDoubleTaps() = runBlocking {
        remoteBatch(); api.failBeforeCommit = true
        try { repo.deleteBatch(batch.id, "重複收據"); fail() } catch (_: IOException) { }
        val pending = MobileStore(directory).load().mutations!!.single()
        try { repo.confirmReview(batch.id); fail() } catch (_: MobileFailure) { }
        repository(MobileStore(directory)).deleteBatch(batch.id, "不同原因也不能改未知請求")
        repo.deleteBatch(batch.id)
        assertEquals(2, api.deletes.size)
        assertEquals(pending.key, api.deletes[1].key); assertEquals(pending.body, api.deletes[1].body)
        assertEquals("重複收據", jsonObject(api.deletes[1].body).text("reason"))
        assertEquals(0, api.submissions)
    }
    @Test fun deleteWithoutDraftRequiresExplicitNullRevisionAndDraftOnlyUsesRevisionCas() = runBlocking {
        store.update(batch.id) { it.copy(intakeId = api.intakeId, status = "processing") }; api.hasDraft = false
        repo.deleteBatch(batch.id)
        assertTrue(jsonObject(api.deletes.single().body).has("expected_draft_revision"))
        assertTrue(jsonObject(api.deletes.single().body)["expected_draft_revision"].isJsonNull)
        val other = store.create(UUID.randomUUID().toString(), "草稿"); api.deleted = false; api.hasDraft = true
        store.update(other.id) { it.copy(importComplete = true, draftId = api.draftId, review = createReview(api.remote)) }
        repo.deleteBatch(other.id)
        assertEquals("draft_delete", api.deletes.last().kind)
        assertEquals(1, jsonObject(api.deletes.last().body)["expected_revision"].asInt)
    }
    @Test fun crossDeviceDeletionStaysDeletedWhenAnOldNonDeletedProjectionArrives() = runBlocking {
        remoteBatch(); api.deleted = true
        repo.sync(batch.id)
        assertTrue(store.batch(batch.id).deleted)
        api.deleted = false
        repo.sync(batch.id)
        assertTrue(store.batch(batch.id).deleted)
        assertEquals(0, api.submissions)
    }
    @Test fun voidRefreshesInboxLedgerListDeepDetailAndCentralOverviewAndRetainsHistory() = runBlocking {
        remoteBatch(); api.posted = true
        store.cacheEntry(api.entry(), confirmed = true)
        store.update(batch.id) { it.copy(ledgerId = api.entryId, ledgerStatus = "active", draftStatus = "confirmed") }
        repo.voidEntry(api.entryId)
        val result = MobileStore(directory).load()
        assertEquals("voided", result.batches.single().ledgerStatus)
        assertEquals("voided", jsonObject(result.ledgerJson!!).array("entries").single().asJsonObject.text("status"))
        assertEquals("voided", jsonObject(result.ledgerDetailsJson!!).getAsJsonObject(api.entryId).text("status"))
        assertEquals(0, jsonObject(result.overviewJson!!)["receipt_count"].asInt)
        assertEquals(1, result.ledgerNextOffset) // Server cursor counts fetched rows, independent of filtering.
        assertFalse(result.accountingStale == true)
    }
    @Test fun successfulMutationWithFailedRefreshPersistsAnExplicitStaleStatus() = runBlocking {
        remoteBatch(); api.failOverview = true
        repo.deleteBatch(batch.id)
        assertTrue(store.batch(batch.id).deleted)
        assertTrue(store.load().accountingStale == true)
        assertTrue(store.load().accountingError!!.contains("尚未同步"))
    }
    @Test fun crashAfterDurableTombstoneFinishesTheOriginalJournalOnRetry() = runBlocking {
        remoteBatch(); api.failBeforeCommit = true
        try { repo.deleteBatch(batch.id); fail() } catch (_: IOException) { }
        val pending = store.load().mutations!!.single()
        store.update(batch.id) { it.copy(deletedAt = System.currentTimeMillis(), status = "deleted", draftStatus = "deleted") }
        repository(MobileStore(directory)).deleteBatch(batch.id)
        assertEquals("completed", store.load().mutations!!.single().phase)
        assertEquals(pending.key, store.load().mutations!!.single().key)
        assertEquals(1, api.deletes.size)
    }
    @Test fun alreadyVoidedOnAnotherDeviceRefreshesThePreviouslyFreshOverviewWithoutPosting() = runBlocking {
        api.posted = true; store.cacheEntry(api.entry()); repo.ledger()
        assertEquals(180, jsonObject(store.load().overviewJson!!)["personal_yuan"].asInt)
        api.voided = true; api.ledgerRevision++
        repo.voidEntry(api.entryId)
        assertEquals(0, jsonObject(store.load().overviewJson!!)["personal_yuan"].asInt)
        assertFalse(store.load().accountingStale == true)
        assertEquals(0, api.voids.size)
    }
    @Test fun aNewerDraftMustBeInspectedBeforeStartingANewDeleteIntent() = runBlocking {
        remoteBatch(); api.remote = jsonObject(api.remote).apply { addProperty("revision", 2); getAsJsonObject("candidate").addProperty("merchant", "另一台修改") }.toString()
        try { repo.deleteBatch(batch.id); fail() } catch (_: MobileFailure) { }
        assertEquals(0, api.deletes.size); assertTrue(store.load().mutations.isNullOrEmpty())
        assertFalse(store.batch(batch.id).deleted)
    }
    @Test fun passiveDeletedGetAtomicallyCompletesThePendingJournalAcrossRestart() = runBlocking {
        remoteBatch(); api.loseResponse = true
        try { repo.deleteBatch(batch.id); fail() } catch (_: IOException) { }
        val original = MobileStore(directory).load().mutations!!.single()
        repository(MobileStore(directory)).sync(batch.id)
        val result = MobileStore(directory).load()
        assertTrue(result.batches.single().deleted)
        assertEquals(original.copy(phase = "completed"), result.mutations!!.single())
        assertEquals(1, api.deletes.size)
    }
    @Test fun passiveVoidedGetFinishesLedgerOnlyPendingButPreservesOtherTargets() = runBlocking {
        api.posted = true; api.loseResponse = true
        try { repo.voidEntry(api.entryId); fail() } catch (_: IOException) { }
        val original = MobileStore(directory).load().mutations!!.single()
        assertNull(original.batchId)
        val unrelated = ReceiptMutation("ledger_void", UUID.randomUUID().toString(), null, UUID.randomUUID().toString(), """{"expected_revision":1,"reason":"未註明"}""")
        store.recordMutation(unrelated)
        repository(MobileStore(directory)).ledger()
        val attempts = MobileStore(directory).load().mutations!!
        assertEquals(original.copy(phase = "completed"), attempts.first())
        assertEquals(unrelated, attempts.last())
        assertEquals(1, api.voids.size)
    }
    @Test fun voidRefreshKeepsLoadedPageCursorAndInvalidatesAnUnloadedDetail() = runBlocking {
        api.posted = true
        api.extraEntries = (1..40).map { index -> jsonObject(api.entry()).apply {
            val id = UUID.nameUUIDFromBytes("page-$index".toByteArray()).toString()
            val draft = UUID.nameUUIDFromBytes("draft-$index".toByteArray()).toString()
            addProperty("id", id); addProperty("draft_id", draft)
            getAsJsonObject("snapshot").addProperty("id", draft); getAsJsonObject("snapshot").addProperty("ledger_id", id)
        }.toString() }
        store.cacheLedger(api.ledger("prm_fixture", 0)); store.cacheLedger(api.ledger("prm_fixture", 20), append = true)
        val distant = api.extraEntries.last(); val distantId = ledgerObject(distant).text("id"); store.cacheEntry(distant)
        api.ledgerOffsets.clear()
        repo.voidEntry(api.entryId)
        assertEquals(listOf(0, 20), api.ledgerOffsets)
        assertEquals(40, store.load().ledgerNextOffset)
        assertEquals(40, jsonObject(store.load().ledgerJson!!).array("entries").size())
        assertFalse(jsonObject(store.load().ledgerDetailsJson!!).has(distantId))
        repo.ledger(store.load().ledgerNextOffset!!)
        assertEquals(41, store.load().ledgerNextOffset)
        assertEquals(41, jsonObject(store.load().ledgerJson!!).array("entries").size())
    }
    @Test fun olderActiveResponsesNeverResurrectAVoidedLedgerOrItsReceiptProjection() = runBlocking {
        remoteBatch(); api.posted = true
        val active = api.entry(); store.cacheEntry(active, confirmed = true)
        api.voided = true; api.ledgerRevision = 2
        store.cacheEntry(api.entry())
        store.cacheLedger("""{"entries":[$active],"offset":0,"total_count":1}""")
        assertEquals("voided", jsonObject(store.load().ledgerJson!!).array("entries").single().asJsonObject.text("status"))
        assertEquals("voided", jsonObject(store.load().ledgerDetailsJson!!).getAsJsonObject(api.entryId).text("status"))
        api.voided = false; api.ledgerRevision = 1
        repo.sync(batch.id)
        assertEquals("voided", store.batch(batch.id).ledgerStatus)
        assertEquals("voided", store.batch(batch.id).draftStatus)
    }
    @Test fun voidUnknownRetryAndKnownRevisionConflictNeverSilentlyRebase() = runBlocking {
        api.posted = true; api.failBeforeCommit = true
        store.cacheEntry(api.entry())
        try { repo.voidEntry(api.entryId, "重複入帳"); fail() } catch (_: IOException) { }
        val pending = store.load().mutations!!.single()
        api.ledgerRevision = 8
        try { repository(MobileStore(directory)).voidEntry(api.entryId); fail() } catch (_: MobileApiFailure) { }
        assertEquals(pending.key, api.voids.last().key); assertEquals(pending.body, api.voids.last().body)
        assertEquals("rejected", store.load().mutations!!.single().phase)
        assertEquals("active", jsonObject(store.load().ledgerDetailsJson!!).getAsJsonObject(api.entryId).text("status"))
    }
}

internal class ReceiptActionsApi : MobileApi by FakeMobileApi() {
    val intakeId = UUID.randomUUID().toString(); val draftId = UUID.randomUUID().toString(); val entryId = UUID.randomUUID().toString()
    var remote = nativeDraft(draftId)
    var deleted = false; var voided = false; var posted = false; var hasDraft = true
    var loseResponse = false; var failBeforeCommit = false; var failOverview = false; var ledgerRevision = 1L
    var submissions = 0
    var extraEntries = emptyList<String>(); val ledgerOffsets = mutableListOf<Int>()
    val deletes = mutableListOf<ReceiptMutation>(); val voids = mutableListOf<ReceiptMutation>()
    override suspend fun submit(token: String, frozen: FrozenIntake): ServerIntake { submissions++; error("Not authorized in action tests") }
    override suspend fun status(token: String, id: String) = ServerIntake(intakeId, if (deleted) "deleted" else "succeeded", draftId.takeIf { hasDraft }, "{}", if (deleted) "deleted" else if (posted) if (voided) "voided" else "confirmed" else "needs_review", entryId.takeIf { posted }, if (posted) if (voided) "voided" else "active" else null)
    override suspend fun draft(token: String, id: String) = jsonObject(remote).apply { if (deleted) addProperty("status", "deleted"); if (posted) { addProperty("status", if (voided) "voided" else "confirmed"); addProperty("ledger_id", entryId) } }.toString()
    override suspend fun deleteIntake(token: String, id: String, attempt: ReceiptMutation): ServerIntake { performDelete(attempt); return status(token, id) }
    override suspend fun deleteDraft(token: String, id: String, attempt: ReceiptMutation): String { performDelete(attempt); return draft(token, id) }
    private fun performDelete(attempt: ReceiptMutation) {
        deletes += attempt
        if (failBeforeCommit) { failBeforeCommit = false; throw IOException("before commit") }
        if (posted) throw MobileApiFailure(409, "ledger_exists")
        deleted = true
        if (loseResponse) { loseResponse = false; throw IOException("lost response") }
    }
    fun entry() = JsonObject().apply {
        addProperty("id", entryId); addProperty("draft_id", draftId); addProperty("revision", ledgerRevision); addProperty("status", if (voided) "voided" else "active")
        add("snapshot", jsonObject(remote).apply { addProperty("status", "confirmed"); addProperty("ledger_id", entryId) })
        add("events", JsonArray().apply { if (voided) add(JsonObject().apply { addProperty("reason", "未註明") }) })
    }.toString()
    override suspend fun voidLedger(token: String, id: String, attempt: ReceiptMutation): String {
        voids += attempt
        if (failBeforeCommit) { failBeforeCommit = false; throw IOException("before commit") }
        if (jsonObject(attempt.body)["expected_revision"].asLong != ledgerRevision) throw MobileApiFailure(409, "revision_conflict")
        voided = true; ledgerRevision++
        if (loseResponse) { loseResponse = false; throw IOException("lost response") }
        return entry()
    }
    override suspend fun ledger(token: String, offset: Int): String {
        ledgerOffsets += offset
        val entries = (if (posted) listOf(entry()) else emptyList()) + extraEntries
        return """{"entries":[${entries.drop(offset).take(20).joinToString(",")}],"total_count":${entries.size},"offset":$offset}"""
    }
    override suspend fun ledgerDetail(token: String, id: String) = entry()
    override suspend fun overview(token: String): String {
        if (failOverview) throw IOException("overview unavailable")
        return """{"scope":"active_confirmed_ledger","receipt_count":${if (posted && !voided) 1 else 0},"personal_yuan":${if (posted && !voided) 180 else 0},"printed_total_yuan":${if (posted && !voided) 180 else 0}}"""
    }
}
