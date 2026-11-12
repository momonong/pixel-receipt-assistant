package com.momonong.pixelreceipt.mobile

import com.google.gson.JsonNull
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.time.LocalDate

class MobileSpendingControllerTest {
    private val query=SpendingQuery(LocalDate.parse("2026-10-01"),LocalDate.parse("2026-10-31"),LocalDate.parse("2026-09-01"),LocalDate.parse("2026-09-30"))
    private fun body(q:SpendingQuery,amount:Long=170):String {
        val v=jsonObject(javaClass.getResource("/spending-response-synthetic.json")!!.readText())
        v.addProperty("date_from",q.dateFrom?.toString());v.addProperty("date_to",q.dateTo?.toString())
        val summary=v.getAsJsonObject("summary")
        listOf("printed_total_yuan","allocated_total_yuan","self_yuan","personal_yuan").forEach{summary.addProperty(it,amount)}
        val point=v.array("series")[0].asJsonObject;val day=q.dateFrom?:LocalDate.parse("2026-10-20")
        point.addProperty("date_from",day.toString());point.addProperty("date_to",day.toString());point.addProperty("label",day.toString());point.addProperty("personal_yuan",amount)
        v.array("merchants")[0].asJsonObject.addProperty("personal_yuan",amount)
        if(q.compareFrom==null)v.add("comparison",JsonNull.INSTANCE)
        else {val c=v.getAsJsonObject("comparison");c.addProperty("date_from",q.compareFrom.toString());c.addProperty("date_to",q.compareTo.toString());c.addProperty("delta_personal_yuan",amount-100)}
        return v.toString()
    }
    private open inner class Source:MobileSpendingSource {
        var saved:MobileSpendingCache?=null;var stamp="marker-1";val savedKeys=mutableListOf<String>()
        override fun marker()=stamp
        override fun cached()=saved
        override suspend fun report(query:SpendingQuery)=body(query)
        override suspend fun entries(query:SpendingQuery,selection:SpendingSelection,offset:Int):String=throw IOException("synthetic unavailable")
        override suspend fun saveCache(value:MobileSpendingCache,expectedMarker:String,stillCurrent:()->Boolean):Boolean {
            if(!stillCurrent()||stamp!=expectedMarker)return false
            saved=value;savedKeys+=value.queryPath;return true
        }
    }
    @Test fun lateCancelledPeriodCannotOverwriteTheNewReportOrItsCache()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val old=CompletableDeferred<String>();val source=object:Source(){override suspend fun report(query:SpendingQuery):String=if(query==this@MobileSpendingControllerTest.query)withContext(NonCancellable){old.await()}else body(query,900)}
        try {
            val c=MobileSpendingController(scope,source,initialQuery=query)
            c.refresh();c.custom(LocalDate.parse("2026-11-01"),LocalDate.parse("2026-11-30"));c.awaitIdle()
            assertEquals(900L,c.state.value.report!!.summary.personalYuan)
            old.complete(body(query,170));yield()
            assertEquals(LocalDate.parse("2026-11-01"),c.state.value.query.dateFrom)
            assertEquals(900L,c.state.value.report!!.summary.personalYuan)
            assertEquals(1,source.savedKeys.size);assertTrue(source.savedKeys.single().contains("2026-11-01"))
        }finally{old.complete(body(query));scope.cancel()}
    }
    @Test fun anOfflineNewPeriodNeverBorrowsTheOldPeriodsNumbers()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val source=object:Source(){var offline=false;override suspend fun report(query:SpendingQuery):String{if(offline)throw IOException("offline");return body(query)}}
        try {
            val c=MobileSpendingController(scope,source,initialQuery=query);c.refresh();c.awaitIdle()
            assertNotNull(c.state.value.report);assertFalse(c.state.value.stale)
            source.offline=true;c.custom(LocalDate.parse("2026-11-01"),LocalDate.parse("2026-11-30"));c.awaitIdle()
            assertNull(c.state.value.report);assertTrue(c.state.value.stale);assertNotNull(c.state.value.error)
            assertFalse(c.state.value.loading)
        }finally{scope.cancel()}
    }
    @Test fun badDerivedCacheDoesNotPreventOpeningOrReadingReceipts() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined);val source=Source()
        try {
            source.saved=MobileSpendingCache(query.summaryPath(),"{damaged-cache",1,source.marker())
            val c=MobileSpendingController(scope,source,initialQuery=query);c.restore()
            assertNull(c.state.value.report);assertTrue(c.state.value.stale)
        }finally{scope.cancel()}
    }
    @Test fun aLedgerChangeMarksTheSnapshotStaleAndSessionChangeClearsVisibleData()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined);val source=Source()
        try {
            val c=MobileSpendingController(scope,source,initialQuery=query);c.refresh();c.awaitIdle()
            source.stamp="marker-2";c.invalidate()
            assertTrue(c.state.value.stale);assertEquals(170L,c.state.value.report!!.summary.personalYuan)
            c.sessionChanged();assertNull(c.state.value.report);assertNull(c.state.value.entriesJson)
        }finally{scope.cancel()}
    }
}
