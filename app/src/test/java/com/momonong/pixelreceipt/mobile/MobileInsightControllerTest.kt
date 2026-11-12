package com.momonong.pixelreceipt.mobile

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
class MobileInsightControllerTest {
    @Test fun unknownManualQueriesOriginalOperationThenResendsSameFrozenBodyOnlyAfter404()=runBlocking {
        val requests=mutableListOf<MobileRequest>()
        val transport=MobileTransport { req -> requests.add(req); if(req.path.startsWith("v1/analysis-operations/")) throw MobileApiFailure(404); throw IOException("offline") }
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val dir=File(ApplicationProvider.getApplicationContext<Application>().cacheDir,UUID.randomUUID().toString()).apply{mkdirs()}
        try {
            val controller=MobileInsightController(HttpsMobileApi(transport),scope,{"session"},{},{},dir,accountScope={"ledger-a"})
            controller.submitManual(manualBody("2026-11-12","","","300","self","",defaultInsightDocument()))
            controller.awaitIdle()
            assertNotNull(controller.state.value.pending)
            val first=requests.single()
            controller.retryWrite(); controller.awaitIdle()
            assertTrue(requests[1].path.startsWith("v1/analysis-operations/"))
            assertEquals(first.json,requests[2].json)
            assertEquals(first.idempotencyKey,requests[2].idempotencyKey)
        } finally {scope.cancel();dir.deleteRecursively()}
    }
    @Test fun forbiddenOperationLookupCannotEraseUnknownWrite()=runBlocking {
        var lookups=0
        val api=HttpsMobileApi(MobileTransport{req->if(req.path.startsWith("v1/analysis-operations/")){lookups++;throw MobileApiFailure(403)}else throw IOException("unknown")})
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val dir=File(ApplicationProvider.getApplicationContext<Application>().cacheDir,UUID.randomUUID().toString()).apply{mkdirs()}
        try {
            val c=MobileInsightController(api,scope,{"session"},{},{},dir,accountScope={"ledger-a"})
            c.submitManual(manualBody("2026-11-12","","","300","self","",defaultInsightDocument()));c.awaitIdle()
            val original=c.state.value.pending
            c.retryWrite();c.awaitIdle();assertEquals(1,lookups);assertEquals(original,c.state.value.pending)
            val restored=MobileInsightController(api,scope,{"new-session"},{},{},dir,accountScope={"ledger-a"});restored.restorePending();assertEquals(original,restored.state.value.pending)
        }finally{scope.cancel();dir.deleteRecursively()}
    }
    @Test fun mismatchedManualAmountCannotClearPending()=runBlocking {
        val ledger=UUID.randomUUID().toString();val draft=UUID.randomUUID().toString()
        val api=HttpsMobileApi(MobileTransport { """{"id":"$ledger","draft_id":"$draft","status":"active","snapshot":{"source":{"kind":"manual"},"evidence":[],"candidate":{"receipt_date":"2026-11-12","printed_total_yuan":999,"currency":"TWD","amount_unit":"TWD_YUAN","items":[{"quantity":1,"printed_line_total_yuan":999}]},"calculation":{"printed_total_yuan":999,"self_yuan":999,"gift_yuan":0,"advance_yuan":0},"review_document":{"line_decisions":[{"item_position":0,"whole_purpose":"self"}]}}}""" })
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val dir=File(ApplicationProvider.getApplicationContext<Application>().cacheDir,UUID.randomUUID().toString()).apply{mkdirs()}
        try {
            val controller=MobileInsightController(api,scope,{"session"},{},{},dir,accountScope={"ledger-a"})
            controller.submitManual(manualBody("2026-11-12","","","300","self","",defaultInsightDocument()));controller.awaitIdle()
            assertNotNull(controller.state.value.pending);assertNull(controller.state.value.completedLedger)
        }finally{scope.cancel();dir.deleteRecursively()}
    }
    @Test fun newTokenForSameLedgerRestoresOriginalWriteButDifferentLedgerCannot()=runBlocking {
        val requests=mutableListOf<MobileRequest>()
        val api=HttpsMobileApi(MobileTransport{req->requests.add(req);throw IOException("offline")})
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val dir=File(ApplicationProvider.getApplicationContext<Application>().cacheDir,UUID.randomUUID().toString()).apply{mkdirs()}
        try {
            val first=MobileInsightController(api,scope,{"old-token"},{},{},dir,accountScope={"ledger-a"})
            first.submitManual(manualBody("2026-11-12","","","300","self","",defaultInsightDocument()));first.awaitIdle()
            val original=first.state.value.pending
            val unknown=MobileInsightController(api,scope,{"new-token"},{},{},dir)
            unknown.restorePending();unknown.submitManual(manualBody("2026-11-12","","","400","self","",defaultInsightDocument()));unknown.awaitIdle()
            assertNull(unknown.state.value.pending);assertEquals(1,requests.size)
            val same=MobileInsightController(api,scope,{"new-token"},{},{},dir,accountScope={"ledger-a"})
            same.restorePending();assertEquals(original,same.state.value.pending)
            val other=MobileInsightController(api,scope,{"new-token"},{},{},dir,accountScope={"ledger-b"})
            other.restorePending();assertNull(other.state.value.pending)
            assertFalse(dir.walkTopDown().filter{it.isFile}.any{it.readText().contains("old-token")||it.readText().contains("new-token")})
        } finally {scope.cancel();dir.deleteRecursively()}
    }
    @Test fun recoveringMetadataForAKeepsCurrentBEditorAndNextSaveScopedToB()=runBlocking {
        val a=jsonObject(javaClass.getResource("/insight-metadata-synthetic.json")!!.readText())
        val idA=a.text("ledger_id");val idB=UUID.randomUUID().toString()
        a.getAsJsonObject("document").addProperty("brand","A brand")
        val b=a.deepCopy().apply{addProperty("ledger_id",idB);addProperty("draft_id",UUID.randomUUID().toString());addProperty("revision",5);getAsJsonObject("document").addProperty("brand","B brand")}
        var keyA="";var resultA=a.deepCopy();val saved=mutableListOf<Pair<String,com.google.gson.JsonObject>>()
        val api=object:MobileApi by FakeMobileApi(){
            override suspend fun insightMetadata(token:String,id:String,ledger:Boolean)=if(id==idA)a.toString()else b.toString()
            override suspend fun saveInsightMetadata(token:String,id:String,ledger:Boolean,body:String,key:String):String {
                val request=jsonObject(body);saved+=id to request
                val result=(if(id==idA)a else b).deepCopy().apply{addProperty("revision",request["expected_revision"].asLong+1);add("document",request.getAsJsonObject("document").deepCopy())}
                if(id==idA){keyA=key;resultA=result;throw IOException("accepted response lost")}
                return result.toString()
            }
            override suspend fun analysisOperation(token:String,key:String)=com.google.gson.JsonObject().apply{addProperty("operation_id",keyA);addProperty("kind","insight_ledger");addProperty("target_id",idA);add("result",resultA)}.toString()
        }
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val dir=File(ApplicationProvider.getApplicationContext<Application>().cacheDir,UUID.randomUUID().toString()).apply{mkdirs()}
        try{
            val c=MobileInsightController(api,scope,{"session"},{},{},dir,accountScope={"ledger-a"})
            c.openMetadata(idA,true);c.awaitIdle();c.saveMetadata(c.state.value.metadata!!.getAsJsonObject("document"));c.awaitIdle()
            assertNotNull(c.state.value.pending)
            c.openMetadata(idB,true);c.awaitIdle();c.retryWrite();c.awaitIdle()
            assertNull(c.state.value.pending);assertEquals(idB,c.state.value.metadataId)
            assertEquals("Recovering A must not replace B's editor response",idB,c.state.value.metadata!!.text("ledger_id"))
            assertEquals("B brand",c.state.value.metadata!!.getAsJsonObject("document").text("brand"))
            c.saveMetadata(c.state.value.metadata!!.getAsJsonObject("document"));c.awaitIdle()
            assertEquals(idB,saved.last().first);assertEquals(5L,saved.last().second["expected_revision"].asLong);assertEquals("B brand",saved.last().second.getAsJsonObject("document").text("brand"))
        }finally{scope.cancel();dir.deleteRecursively()}
    }
    @Test fun late401CannotSignOutNewSession()=runBlocking {
        val response=CompletableDeferred<String>();var token="old";var unauthorized=0
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val dir=File(ApplicationProvider.getApplicationContext<Application>().cacheDir,UUID.randomUUID().toString()).apply{mkdirs()}
        try {
            val controller=MobileInsightController(HttpsMobileApi(MobileTransport { withContext(NonCancellable){response.await()};throw MobileApiFailure(401) }),scope,{token},{unauthorized++},{},dir,accountScope={"ledger-a"})
            controller.refresh();token="new";controller.accountChanged();response.complete("ignored");controller.awaitIdle()
            assertEquals(0,unauthorized)
            assertNull(controller.state.value.report)
        } finally {scope.cancel();dir.deleteRecursively()}
    }
}
