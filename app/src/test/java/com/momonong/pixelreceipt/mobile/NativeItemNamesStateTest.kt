package com.momonong.pixelreceipt.mobile

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.gson.JsonArray
import com.google.gson.JsonNull
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
class NativeItemNamesStateTest {
    private lateinit var directory: File
    private lateinit var store: MobileStore
    private lateinit var api: NamesApi
    @Before fun setup() {
        directory = File(ApplicationProvider.getApplicationContext<Application>().cacheDir, UUID.randomUUID().toString()).apply { mkdirs() }
        store = MobileStore(directory); api = NamesApi()
        store.cacheEntry(api.entry())
        api.beforePost = { attempt -> assertEquals(attempt, MobileStore(directory).load().pendingLedgerMutation(api.base.entryId)) }
    }
    @After fun cleanup() { directory.deleteRecursively() }
    private fun repo() = MobileRepository(MobileStore(directory), ImageStore(File(directory, "images")), MemoryVault().apply { save("prm_fixture") }, api)
    @Test fun lostAcceptedNamesResponseReconcilesDurablyByExactRevisionEventWithoutAnotherPost() = runBlocking {
        val snapshot = ledgerObject(api.entry())["snapshot"].toString()
        api.lose = true
        try { repo().updateItemNames(api.base.entryId, mapOf(0 to "合成飯")); fail() } catch (_: IOException) { }
        val pending = MobileStore(directory).load().pendingLedgerMutation(api.base.entryId)!!
        assertEquals("ledger_names", pending.kind)
        repo().updateItemNames(api.base.entryId, mapOf(0 to "不可取代未知請求"))
        val result = MobileStore(directory).load()
        assertEquals(1, api.posts.size)
        assertEquals("completed", result.mutations!!.single().phase)
        assertEquals(pending.key, result.mutations!!.single().key)
        assertEquals(snapshot, jsonObject(result.ledgerDetailsJson!!).getAsJsonObject(api.base.entryId)["snapshot"].toString())
        assertEquals(0, jsonObject(result.overviewJson!!)["missing_item_name_count"].asInt)
    }
    @Test fun uncommittedUnknownNamesRetriesOnlyTheIdenticalIntentAndBlocksVoid() = runBlocking {
        api.beforeCommit = true
        try { repo().updateItemNames(api.base.entryId, mapOf(0 to "合成飯")); fail() } catch (_: IOException) { }
        val pending = MobileStore(directory).load().pendingLedgerMutation(api.base.entryId)!!
        try { repo().voidEntry(api.base.entryId); fail() } catch (_: MobileFailure) { }
        assertEquals(0, api.base.voids.size)
        repo().updateItemNames(api.base.entryId, mapOf(0 to null))
        assertEquals(2, api.posts.size)
        assertEquals(pending.key, api.posts.last().key); assertEquals(pending.body, api.posts.last().body)
    }
    @Test fun pendingVoidPreventsStartingANewNamesIntent() = runBlocking {
        val attempt = ReceiptMutation("ledger_void", api.base.entryId, null, UUID.randomUUID().toString(), """{"expected_revision":1,"reason":"未註明"}""")
        store.recordMutation(attempt)
        try { repo().updateItemNames(api.base.entryId, mapOf(0 to "不可越過作廢")); fail() } catch (_: MobileFailure) { }
        assertEquals(0, api.posts.size)
        assertEquals(attempt, MobileStore(directory).load().pendingLedgerMutation(api.base.entryId))
    }
    @Test fun newerConflictingRevisionAndVoidedAccountsNeverReceiveNamesWrites() = runBlocking {
        api.beforeCommit = true
        try { repo().updateItemNames(api.base.entryId, mapOf(0 to "合成飯")); fail() } catch (_: IOException) { }
        api.base.ledgerRevision = 2; api.base.voided = true
        try { repo().updateItemNames(api.base.entryId, emptyMap()); fail() } catch (_: MobileFailure) { }
        assertEquals(1, api.posts.size)
        assertEquals("rejected", MobileStore(directory).load().mutations!!.single().phase)
        assertEquals("voided", jsonObject(MobileStore(directory).load().ledgerDetailsJson!!).getAsJsonObject(api.base.entryId).text("status"))
        try { repo().updateItemNames(api.base.entryId, mapOf(0 to "不能復活")); fail() } catch (_: MobileFailure) { }
        assertEquals(1, api.posts.size)
    }
    @Test fun confirmedNameFollowedByVoidStaysVoidedWhenUnknownResultIsQueried() = runBlocking {
        api.lose = true
        try { repo().updateItemNames(api.base.entryId, mapOf(0 to "合成飯")); fail() } catch (_: IOException) { }
        api.base.voided = true; api.base.ledgerRevision++
        repo().updateItemNames(api.base.entryId, emptyMap())
        assertEquals("completed", MobileStore(directory).load().mutations!!.single().phase)
        assertEquals("voided", jsonObject(MobileStore(directory).load().ledgerDetailsJson!!).getAsJsonObject(api.base.entryId).text("status"))
        assertEquals(1, api.posts.size)
    }
}

private class NamesApi(val base: ReceiptActionsApi = ReceiptActionsApi()) : MobileApi by base {
    val posts = mutableListOf<ReceiptMutation>()
    var beforePost: (ReceiptMutation) -> Unit = {}
    var lose = false; var beforeCommit = false
    private var names = JsonArray().apply { add(JsonNull.INSTANCE) }
    private val events = JsonArray()
    init { base.posted = true; base.remote = jsonObject(base.remote).apply { getAsJsonObject("candidate").array("items")[0].asJsonObject.add("raw_name", JsonNull.INSTANCE) }.toString() }
    fun entry() = jsonObject(base.entry()).apply { add("display_item_names", names); addProperty("missing_item_name_count", names.count { it.isJsonNull }); add("events", events) }.toString()
    override suspend fun ledgerDetail(token: String, id: String) = entry()
    override suspend fun ledger(token: String, offset: Int) = """{"entries":[${entry()}],"total_count":1,"offset":0}"""
    override suspend fun overview(token: String) = """{"scope":"active_confirmed_ledger","receipt_count":1,"personal_yuan":180,"printed_total_yuan":180,"missing_item_name_count":${names.count { it.isJsonNull }},"receipts_missing_item_names":${if (names.any { it.isJsonNull }) 1 else 0}}"""
    override suspend fun updateItemNames(token: String, id: String, attempt: ReceiptMutation): String {
        beforePost(attempt); posts += attempt
        if (beforeCommit) { beforeCommit = false; throw IOException("before commit") }
        val body = jsonObject(attempt.body)
        if (base.voided || body["expected_revision"].asLong != base.ledgerRevision) throw MobileApiFailure(409, "conflict")
        body.array("item_names").forEach { names.set(it.asJsonObject["item_position"].asInt, it.asJsonObject["name"]) }
        base.ledgerRevision++
        events.add(JsonObject().apply { addProperty("revision", base.ledgerRevision); addProperty("action", "item_names_updated"); addProperty("reason", JsonObject().apply { add("item_names", body.array("item_names")) }.toString()) })
        if (lose) { lose = false; throw IOException("lost response") }
        return entry()
    }
}
