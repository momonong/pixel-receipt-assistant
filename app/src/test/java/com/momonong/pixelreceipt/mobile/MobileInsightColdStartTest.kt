package com.momonong.pixelreceipt.mobile

import android.app.Application
import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.momonong.pixelreceipt.data.ingestion.ImageStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class MobileInsightColdStartTest {
    @Test fun sameTokenColdOfflineReadsCachedReportButCannotAuthorizeWritesOrLookup(){
        val app=ApplicationProvider.getApplicationContext<Application>()
        val root=File(app.cacheDir,UUID.randomUUID().toString()).apply{mkdirs()}
        val scopeId=UUID.randomUUID().toString();val vault=MemoryVault().apply{save("prm_synthetic_${UUID.randomUUID()}")}
        var offline=false;val scopeCalls=AtomicInteger();val writes=AtomicInteger();val lookups=AtomicInteger()
        val query=InsightQuery()
        val api=object:MobileApi by FakeMobileApi(){
            override suspend fun ledgerScope(token:String):String{scopeCalls.incrementAndGet();if(offline)throw IOException("offline");return scopeId}
            override suspend fun insights(token:String,path:String):String{if(offline)throw IOException("offline");return if(path.startsWith("v1/insights/entries")) """{"entries":[],"total_count":0,"limit":20,"offset":0,"filters":${query.filters()}}""" else insightFixture(query)}
            override suspend fun createManualEntry(token:String,body:String,key:String):String{writes.incrementAndGet();throw IOException("unexpected write")}
            override suspend fun analysisOperation(token:String,key:String):String{lookups.incrementAndGet();throw IOException("unexpected lookup")}
        }
        fun repository()=MobileRepository(MobileStore(File(root,"receipts")),ImageStore(File(root,"images")),vault,api)
        fun saved()=SavedStateHandle(mapOf("mobileDestination" to MobilePage.Spending.name,"mobileInsightQuery" to query.filters().toString()))
        var models=ViewModelStore()
        fun mount():MobileViewModel=MobileViewModel(app,saved(),repository()).also{models.put(UUID.randomUUID().toString(),it)}
        fun await(test:()->Boolean){val until=System.nanoTime()+10_000_000_000L;while(!test()&&System.nanoTime()<until){shadowOf(Looper.getMainLooper()).idle();Thread.sleep(5)};shadowOf(Looper.getMainLooper()).idle();assertTrue("ViewModel did not settle",test())}
        try{
            val warm=mount();await{warm.insightState.value.report!=null&&!warm.insightState.value.stale}
            models.clear();models=ViewModelStore();offline=true
            val cold=mount();await{scopeCalls.get()>=2 && (cold.insightState.value.error!=null || cold.insightState.value.report!=null)}
            assertNotNull("Cold offline start must retain the same-token verified ledger's read-only report",cold.insightState.value.report)
            assertTrue(cold.insightState.value.stale)
            cold.submitManual(manualBody("2026-11-12","","","300","self","",defaultInsightDocument()))
            await{cold.busy.value==null&&scopeCalls.get()>=3}
            cold.insightController.retryWrite()
            assertEquals(0,writes.get());assertEquals(0,lookups.get())
            models.clear();models=ViewModelStore();vault.save("prm_new_synthetic_${UUID.randomUUID()}")
            val different=mount();await{scopeCalls.get()>=4&&different.insightState.value.error!=null}
            assertNull("An unverified new token cannot expose the prior ledger cache",different.insightState.value.report)
        }finally{models.clear();root.deleteRecursively()}
    }
}
