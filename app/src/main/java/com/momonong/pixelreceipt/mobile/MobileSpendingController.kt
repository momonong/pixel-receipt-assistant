package com.momonong.pixelreceipt.mobile

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicLong

internal interface MobileSpendingSource {
    fun marker():String
    fun cached():MobileSpendingCache?
    suspend fun report(query:SpendingQuery):String
    suspend fun entries(query:SpendingQuery,selection:SpendingSelection,offset:Int):String
    suspend fun saveCache(value:MobileSpendingCache,expectedMarker:String,stillCurrent:()->Boolean):Boolean
}
internal data class SpendingUiState(
    val period:SpendingPeriod=SpendingPeriod.MonthToDate,
    val query:SpendingQuery=SpendingQuery.forPeriod(period),
    val selection:SpendingSelection=SpendingSelection(),
    val report:SpendingReport?=null,
    val entriesJson:String?=null,
    val loading:Boolean=false,
    val entriesLoading:Boolean=false,
    val error:String?=null,
    val entriesError:String?=null,
    val stale:Boolean=true,
    val fetchedAt:Long?=null,
    val nextOffset:Int?=null,
)

internal class MobileSpendingController(
    private val scope:CoroutineScope,private val source:MobileSpendingSource,
    initialPeriod:SpendingPeriod=SpendingPeriod.MonthToDate,
    initialQuery:SpendingQuery=SpendingQuery.forPeriod(initialPeriod),
    initialSelection:SpendingSelection=SpendingSelection(),
    private val saveSelection:(SpendingPeriod,SpendingQuery,SpendingSelection)->Unit={_,_,_->},
    private val authenticationRequired:()->Unit={},
){
    private val mutable=MutableStateFlow(SpendingUiState(initialPeriod,initialQuery,runCatching{initialQuery.ledgerPath(initialSelection);initialSelection}.getOrDefault(SpendingSelection())))
    val state=mutable.asStateFlow()
    private val generation=AtomicLong();private val pageGeneration=AtomicLong()
    private var job:Job?=null;private var pageJob:Job?=null
    fun restore(){
        val attempt=generation.get();val query=state.value.query
        val cache=runCatching{source.cached()}.getOrNull() ?: return
        if(cache.queryPath!=query.summaryPath())return
        val report=runCatching{parseSpending(cache.response,query)}.getOrNull() ?: return
        if(generation.get()==attempt && job?.isActive!=true && state.value.report==null)mutable.update{it.copy(report=report,fetchedAt=cache.fetchedAt,stale=true)}
    }
    fun show(period:SpendingPeriod,today:LocalDate=LocalDate.now()){
        if(period!=SpendingPeriod.Custom)change(period,SpendingQuery.forPeriod(period,today))
    }
    fun custom(from:LocalDate,to:LocalDate)=change(SpendingPeriod.Custom,SpendingQuery(from,to))
    private fun change(period:SpendingPeriod,query:SpendingQuery){
        generation.incrementAndGet();job?.cancel();cancelPage()
        mutable.value=SpendingUiState(period,query)
        saveSelection(period,query,SpendingSelection());refresh()
    }
    fun select(selection:SpendingSelection){
        val current=state.value
        if(runCatching{current.query.ledgerPath(selection)}.isFailure)return
        if(selection.merchantKey!=null && current.report?.merchants?.none{it.key==selection.merchantKey}!=false)return
        cancelPage();mutable.update{it.copy(selection=selection,entriesJson=null,entriesError=null,nextOffset=null)}
        saveSelection(current.period,current.query,selection);loadPage(false)
    }
    fun ensureLoaded(){if(state.value.report==null || state.value.stale)refresh()}
    fun refresh(){
        if(job?.isActive==true)return
        val request=state.value.query;val attempt=generation.incrementAndGet()
        cancelPage();mutable.update{it.copy(loading=true,error=null,stale=true)}
        job=scope.launch {
            try{
                val marker=source.marker();val raw=source.report(request);val report=parseSpending(raw,request)
                if(generation.get()!=attempt)return@launch
                val fetched=System.currentTimeMillis()
                // Cache failures do not invalidate a valid live read or make receipt storage unwritable.
                runCatching{source.saveCache(MobileSpendingCache(request.summaryPath(),raw,fetched,marker),marker){generation.get()==attempt}}
                    .exceptionOrNull()?.let{if(it is CancellationException)throw it}
                if(generation.get()!=attempt)return@launch
                val changed=source.marker()!=marker
                mutable.update{it.copy(report=report,loading=false,stale=changed,fetchedAt=fetched,error=if(changed)"帳目已更新，請重新讀取分析。" else null)}
                loadPage(false)
            }catch(error:Exception){
                if(error is CancellationException && error !is TimeoutCancellationException)throw error
                if(generation.get()==attempt){
                    if(error is MobileApiFailure && error.statusCode==401)authenticationRequired()
                    mutable.update{it.copy(loading=false,stale=true,error=spendingError(error))}
                }
            }
        }
    }
    fun loadMore(){if(state.value.nextOffset!=null)loadPage(true)}
    private fun loadPage(more:Boolean){
        if(pageJob?.isActive==true)return
        val current=state.value;if(current.report==null)return
        val offset=if(more)current.nextOffset ?: return else 0
        val attempt=pageGeneration.incrementAndGet();val reportAttempt=generation.get()
        mutable.update{it.copy(entriesLoading=true,entriesError=null)}
        pageJob=scope.launch {
            try{
                val raw=source.entries(current.query,current.selection,offset)
                val response=parseSpendingEntries(raw,current.query,current.selection,offset)
                if(pageGeneration.get()!=attempt || generation.get()!=reportAttempt)return@launch
                val prior=if(more)current.entriesJson?.let(::jsonObject)?.array("entries") else null
                val incoming=response.array("entries")
                if(prior!=null){
                    val ids=prior.map{it.asJsonObject.text("id")}.toSet()
                    check(incoming.none{it.asJsonObject.text("id") in ids}){"Ledger changed while paging"}
                    response.add("entries",JsonArray().apply{prior.forEach{add(it.deepCopy())};incoming.forEach{add(it.deepCopy())}})
                }
                val next=offset+incoming.size();val total=strictLong(response["total_count"])
                mutable.update{it.copy(entriesJson=response.toString(),entriesLoading=false,entriesError=null,nextOffset=if(next<total && incoming.size()>0 && next<=100_000)next else null)}
            }catch(error:Exception){
                if(error is CancellationException && error !is TimeoutCancellationException)throw error
                if(pageGeneration.get()==attempt && generation.get()==reportAttempt){
                    if(error is MobileApiFailure && error.statusCode==401)authenticationRequired()
                    mutable.update{it.copy(entriesLoading=false,entriesError="帳目明細暫時無法更新，請重試。")}
                }
            }
        }
    }
    fun invalidate(){generation.incrementAndGet();job?.cancel();cancelPage();mutable.update{it.copy(stale=true,loading=false)}}
    fun sessionChanged(){generation.incrementAndGet();job?.cancel();cancelPage();mutable.update{it.copy(report=null,entriesJson=null,loading=false,entriesLoading=false,stale=true,error=null,entriesError=null,nextOffset=null)}}
    private fun cancelPage(){pageGeneration.incrementAndGet();pageJob?.cancel();mutable.update{it.copy(entriesLoading=false)}}
    internal suspend fun awaitIdle(){job?.join();pageJob?.join()}
}

internal fun parseSpendingEntries(raw:String,query:SpendingQuery,selection:SpendingSelection,offset:Int):JsonObject{
    query.ledgerPath(selection,offset)
    val value=jsonObject(raw);require(value["entries"].isJsonArray)
    require(strictLong(value["limit"])==20L && strictLong(value["offset"])==offset.toLong() && value.text("status")=="active")
    val entries=value.array("entries");val total=strictLong(value["total_count"],0,Int.MAX_VALUE.toLong())
    require(entries.size()<=20 && offset.toLong()+entries.size()<=total)
    val from=selection.from?:query.dateFrom;val to=selection.to?:query.dateTo
    fun nullable(key:String)=value[key]?.takeUnless{it.isJsonNull}?.asString
    require(nullable("date_from")==from?.toString() && nullable("date_to")==to?.toString() && nullable("merchant_key")==selection.merchantKey)
    require(value["undated_only"]?.isJsonPrimitive==true && value["undated_only"].asJsonPrimitive.isBoolean && value["undated_only"].asBoolean==selection.undated)
    val ids=mutableSetOf<String>()
    entries.forEach {
        val entry=ledgerNamesObject(it.toString());require(entry.text("status")=="active" && validUuid(entry.text("id")) && ids.add(entry.text("id")))
        val date=entry.getAsJsonObject("snapshot").getAsJsonObject("candidate")["receipt_date"]?.takeUnless{it.isJsonNull}?.asString
        require(!selection.undated || date==null)
        require(from==null || (date!=null && LocalDate.parse(date)>=from && LocalDate.parse(date)<=to))
        require(selection.merchantKey==null || entry.text("merchant_key")==selection.merchantKey)
    }
    return value
}
private fun spendingError(error:Exception):String=when{
    error is MobileApiFailure && error.statusCode==401->"請先登入，再查看支出分析。"
    error is MobileApiFailure && error.statusCode==404->"中央尚未提供支出分析，請更新服務後再試。"
    error is MobileApiFailure && error.code=="spending_bucket_limit_exceeded"->"這個期間太長，請縮小日期範圍。"
    else->"支出分析暫時無法更新；已保存的紀錄保留，請重試。"
}
