package com.momonong.pixelreceipt.mobile

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.momonong.pixelreceipt.data.ingestion.ImageStore
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
class MobileSpendingIntegrationTest {
    private val query=SpendingQuery(LocalDate.parse("2026-10-01"),LocalDate.parse("2026-10-31"),LocalDate.parse("2026-09-01"),LocalDate.parse("2026-09-30"))
    private fun fixture(name:String)=javaClass.getResource("/$name")!!.readText()
    @Test fun authenticatedSummaryAndExactDrilldownDoNotChangeTheReceiptIndex()=runBlocking {
        val root=File(ApplicationProvider.getApplicationContext<Application>().cacheDir,UUID.randomUUID().toString()).apply{mkdirs()}
        try{
            val folder=File(root,"receipts");val store=MobileStore(folder);store.load();store.create(UUID.randomUUID().toString(),"合成收據說明必須保留")
            val before=File(folder,"state.json").readBytes();val calls=mutableListOf<MobileRequest>()
            val api=HttpsMobileApi(MobileTransport { r->calls+=r;fixture(if(r.path.startsWith("v1/spending?"))"spending-response-synthetic.json" else "spending-ledger-response-synthetic.json") })
            val cache=MobileSpendingCacheFile(File(root,"analysis"));val repo=MobileRepository(store,ImageStore(File(root,"images")),MemoryVault().apply{save("prm_fixture")},api,cache)
            val marker=spendingMarker(store.state.value!!)
            val raw=repo.spendingRead(query.summaryPath());assertEquals(170L,parseSpending(raw,query).summary.personalYuan)
            val rows=repo.spendingRead(query.ledgerPath());assertEquals(1,parseSpendingEntries(rows,query,SpendingSelection(),0).array("entries").size())
            assertTrue(repo.cacheSpending(MobileSpendingCache(query.summaryPath(),raw,2,marker),marker){true})
            assertEquals(raw,repo.cachedSpending()!!.response)
            assertArrayEquals(before,File(folder,"state.json").readBytes());assertTrue(calls.all{it.method=="GET"&&it.token=="prm_fixture"})
            assertTrue(runCatching{repo.spendingRead("https://elsewhere.invalid/steal")}.isFailure);assertEquals(2,calls.size)
        }finally{root.deleteRecursively()}
    }
    @Test fun lateOldAnalytics401DoesNotEraseFreshLogin()=runBlocking {
        val root=File(ApplicationProvider.getApplicationContext<Application>().cacheDir,UUID.randomUUID().toString()).apply{mkdirs()}
        val entered=CompletableDeferred<Unit>();val reply=CompletableDeferred<Unit>()
        try{
            val api=object:MobileApi by FakeMobileApi(){
                override suspend fun spending(token:String,path:String):String{assertEquals("prm_old",token);entered.complete(Unit);reply.await();throw MobileApiFailure(401)}
                override suspend fun login(deviceId:String,password:String)="prm_new"
            }
            val vault=MemoryVault().apply{save("prm_old")};val store=MobileStore(File(root,"receipts"));store.load()
            val repo=MobileRepository(store,ImageStore(File(root,"images")),vault,api)
            val old=async{runCatching{repo.spendingRead(query.summaryPath())}}
            entered.await();repo.login("synthetic-password");reply.complete(Unit)
            assertTrue(old.await().exceptionOrNull() is MobileFailure);assertEquals("prm_new",vault.read())
        }finally{reply.complete(Unit);root.deleteRecursively()}
    }
    @Test fun aChangedFormalLedgerCannotReceiveTheOldReportsCache(){
        val root=File(ApplicationProvider.getApplicationContext<Application>().cacheDir,UUID.randomUUID().toString()).apply{mkdirs()}
        try{
            val store=MobileStore(File(root,"receipts"));store.load();val before=spendingMarker(store.state.value!!)
            val entry=jsonObject(fixture("spending-ledger-response-synthetic.json")).array("entries")[0].toString()
            store.cacheEntry(entry);assertNotEquals(before,spendingMarker(store.state.value!!))
            val cache=MobileSpendingCacheFile(File(root,"analysis"))
            assertFalse(cache.write(MobileSpendingCache(query.summaryPath(),fixture("spending-response-synthetic.json"),2,before),{spendingMarker(store.load())}){true})
            assertNull(cache.read())
        }finally{root.deleteRecursively()}
    }
}
