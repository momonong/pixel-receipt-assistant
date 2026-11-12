package com.momonong.pixelreceipt.mobile

import android.util.AtomicFile
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.security.MessageDigest
import java.util.UUID

internal data class InsightWrite(val key:String,val kind:String,val target:String?,val ledger:Boolean,val body:String)
internal class InsightPendingStore(private val directory:File){
    private fun file(account:String)=AtomicFile(File(directory,"analysis-${MessageDigest.getInstance("SHA-256").digest(account.toByteArray()).joinToString(""){"%02x".format(it)}}.json"))
    @Synchronized fun read(account:String):InsightWrite? {val f=file(account);if(!f.baseFile.exists() && !File(f.baseFile.path+".bak").exists())return null;return f.openRead().use { input-> val j=jsonObject(readBounded(input,1024*1024).toString(Charsets.UTF_8));InsightWrite(j.text("key"),j.text("kind"),j["target"]?.takeUnless{it.isJsonNull}?.asString,j["ledger"].asBoolean,j.text("body")).also{r->UUID.fromString(r.key);require(r.kind in setOf("manual","metadata"));if(r.kind=="metadata")UUID.fromString(r.target);require(j.text("body_sha256")==sha256(r.body.toByteArray(Charsets.UTF_8)));jsonObject(r.body)} }}
    @Synchronized fun write(account:String,value:InsightWrite){directory.mkdirs();val f=file(account);val out=f.startWrite();try{out.write(JsonObject().apply{addProperty("key",value.key);addProperty("kind",value.kind);addProperty("target",value.target);addProperty("ledger",value.ledger);addProperty("body",value.body);addProperty("body_sha256",sha256(value.body.toByteArray(Charsets.UTF_8)))}.toString().toByteArray());f.finishWrite(out)}catch(e:Exception){f.failWrite(out);throw e}}
    @Synchronized fun clear(account:String){file(account).delete()}
}
internal data class InsightUiState(
    val enabled:Boolean=false,val query:InsightQuery=InsightQuery.month(),val report:InsightReport?=null,
    val loading:Boolean=false,val error:String?=null,val stale:Boolean=true,val entries:List<JsonObject> = emptyList(),val nextOffset:Int?=null,val entriesLoading:Boolean=false,
    val metadata:JsonObject?=null,val metadataId:String?=null,val metadataLedger:Boolean=false,val metadataReadOnly:Boolean=false,
    val saving:Boolean=false,val feedback:String?=null,val pending:InsightWrite?=null,val completedLedger:String?=null,
)
internal class MobileInsightController(
    private val api:MobileApi,private val scope:CoroutineScope,private val token:()->String?,private val unauthorized:(String)->Unit,
    private val changed:()->Unit,cacheDir:File,initialQuery:InsightQuery=InsightQuery.month(),private val saveQuery:(InsightQuery)->Unit={},private val accountScope:()->String?={null},private val cachedScope:()->String?={null},
){
    private val store=InsightPendingStore(File(cacheDir,"analysis-writes"))
    private val cache=InsightReportCache(File(cacheDir,"analysis-cache"))
    private val mutable=MutableStateFlow(InsightUiState(enabled=true,query=initialQuery));val state=mutable.asStateFlow()
    private var recoveryBlocked=false
    private var generation=0L;private var readGeneration=0L;private var reads:Job?=null;private var pages:Job?=null;private var write:Job?=null;private var metadataRead:Job?=null
    private fun current(g:Long,t:String)=generation==g && token()==t
    private fun failure(e:Exception,g:Long,t:String){if(current(g,t) && e is MobileApiFailure && e.statusCode==401)unauthorized(t)}
    fun accountChanged(){generation++;reads?.cancel();pages?.cancel();metadataRead?.cancel();write?.cancel();mutable.value=InsightUiState(enabled=true);restorePending()}
    fun newManual(){if(state.value.pending==null)mutable.update{it.copy(feedback=null,completedLedger=null)}}
    fun restorePending(){
        val t=token()?:return
        val verified=accountScope()
        if(verified!=null)try{val pending=store.read(verified);recoveryBlocked=false;mutable.update{it.copy(pending=pending)}}
        catch(_:Exception){recoveryBlocked=true;mutable.update{it.copy(error="分析操作紀錄無法讀取；為避免重複記帳，請保留本機資料後重試。")}}
        val readScope=verified?:cachedScope()?:return
        if(state.value.report==null)runCatching{cache.read(readScope,state.value.query)}.getOrNull()?.let{report->
            if(token()==t && (accountScope()?:cachedScope())==readScope)mutable.update{it.copy(report=report,stale=true)}
        }
    }
    fun select(query:InsightQuery){query.filters();saveQuery(query);readGeneration++;reads?.cancel();pages?.cancel();mutable.update{it.copy(query=query,report=null,entries=emptyList(),nextOffset=null,loading=false,stale=true)};refresh()}
    fun refresh(){
        if(reads?.isActive==true)return
        val t=token()?:return;val g=generation;val read=readGeneration;val q=state.value.query;val account=accountScope()
        mutable.update{it.copy(loading=true,error=null)}
        reads=scope.launch {try{val report=withContext(Dispatchers.IO){val raw=api.insights(t,q.path());val parsed=parseInsightReport(raw,q);if(current(g,t)&&readGeneration==read&&state.value.query==q&&account!=null&&accountScope()==account)runCatching{cache.write(account,raw)};parsed};if(current(g,t)&&readGeneration==read&&state.value.query==q){mutable.update{it.copy(report=report,loading=false,error=null,stale=false)};loadEntries()}}
        catch(e:Exception){if(e is CancellationException && e !is TimeoutCancellationException)throw e;if(current(g,t)&&readGeneration==read&&state.value.query==q){failure(e,g,t);mutable.update{it.copy(loading=false,stale=true,error="分析暫時無法更新；保留上次範圍，請重試。")}}}}
    }
    fun loadEntries(more:Boolean=false){
        if(pages?.isActive==true)return
        val t=token()?:return;val g=generation;val read=readGeneration;val q=state.value.query;val offset=if(more)state.value.nextOffset?:return else 0
        mutable.update{it.copy(entriesLoading=true)}
        pages=scope.launch{try{val response=withContext(Dispatchers.IO){jsonObject(api.insights(t,q.path(true,offset))).also{v->checkInsightFilters(v,q);require(insightLong(v["offset"])==offset.toLong() && insightLong(v["limit"])==20L)}}
            val entries=response.array("entries").map{it.asJsonObject.also{e->UUID.fromString(e.text("id"));require(e.text("status")=="active");insightLong(e["matched_personal_yuan"]);require(e["insights"].isJsonObject)}}
            val total=insightLong(response["total_count"]);require(entries.size<=20 && offset.toLong()+entries.size<=total)
            if(current(g,t)&&readGeneration==read&&state.value.query==q){val all=if(more)state.value.entries+entries else entries;require(all.map{it.text("id")}.distinct().size==all.size);mutable.update{it.copy(entries=all,entriesLoading=false,nextOffset=if(offset+entries.size<total && entries.isNotEmpty())offset+entries.size else null)}}
        }catch(e:Exception){if(e is CancellationException && e !is TimeoutCancellationException)throw e;if(current(g,t)&&readGeneration==read&&state.value.query==q){failure(e,g,t);mutable.update{it.copy(entriesLoading=false,error="對應帳目尚未更新，請重新讀取分析。")}}}}
    }
    fun openMetadata(id:String,ledger:Boolean,readOnly:Boolean=false){
        UUID.fromString(id);metadataRead?.cancel();val t=token()?:return;val g=generation
        mutable.update{it.copy(metadata=null,metadataId=id,metadataLedger=ledger,metadataReadOnly=readOnly,feedback=null)}
        metadataRead=scope.launch{try{val response=withContext(Dispatchers.IO){parseMetadata(api.insightMetadata(t,id,ledger),id,ledger)};if(current(g,t)&&state.value.metadataId==id)mutable.update{it.copy(metadata=response)}}
        catch(e:Exception){if(e is CancellationException && e !is TimeoutCancellationException)throw e;if(current(g,t)){failure(e,g,t);mutable.update{it.copy(feedback="分析標籤尚未讀取，請返回後重試。")}}}}
    }
    fun saveMetadata(document:JsonObject){try{val s=state.value;if(s.metadataReadOnly)return;val metadata=s.metadata?:return;val body=JsonObject().apply{add("expected_revision",metadata["revision"]);add("document",validateInsightDocument(document))}.toString();begin(InsightWrite(UUID.randomUUID().toString(),"metadata",s.metadataId,s.metadataLedger,body))}catch(_:Exception){mutable.update{it.copy(feedback="請核對時間格式與分析欄位，再保存。")}}}
    fun submitManual(body:String){jsonObject(body);begin(InsightWrite(UUID.randomUUID().toString(),"manual",null,false,body))}
    private fun begin(request:InsightWrite){
        if(recoveryBlocked || write?.isActive==true || state.value.pending!=null)return
        val t=token()?:return;val account=accountScope()?:run{mutable.update{it.copy(feedback="帳本身份尚未驗證，請連線重新登入後再保存。")};return}
        try{store.write(account,request);mutable.update{it.copy(pending=request,feedback=null,completedLedger=null)};perform(false)}catch(_:Exception){mutable.update{it.copy(feedback="本機未能保存請求；這次尚未送出。")}}
    }
    fun retryWrite(){if(write?.isActive!=true)perform(true)}
    private fun perform(reconcile:Boolean){
        val request=state.value.pending?:return;val t=token()?:return;val account=accountScope()?:return;val g=generation
        mutable.update{it.copy(saving=true,feedback=null)}
        write=scope.launch {var definitiveRejection=false;try{
            val result=withContext(Dispatchers.IO){
                val operation=if(reconcile)try{jsonObject(api.analysisOperation(t,request.key))}catch(e:MobileApiFailure){if(e.statusCode==404)null else throw e}else null
                if(operation!=null){require(operation.text("operation_id")==request.key);require(operation.text("kind")==if(request.kind=="manual")"manual_entry"else if(request.ledger)"insight_ledger"else"insight_draft");UUID.fromString(operation.text("target_id"));if(request.target!=null)require(operation.text("target_id")==request.target);operation.getAsJsonObject("result").also{if(request.kind=="manual")require(it.text("id")==operation.text("target_id"))}}
                else try { jsonObject(if(request.kind=="manual")api.createManualEntry(t,request.body,request.key)else api.saveInsightMetadata(t,request.target!!,request.ledger,request.body,request.key)) }
                catch(error:MobileApiFailure){definitiveRejection=error.statusCode in setOf(400,403,409,422);throw error}
            }
            val ledgerId=if(request.kind=="manual")validateManualResult(result,request.body)else{
                parseMetadata(result.toString(),request.target!!,request.ledger)
                val frozen=jsonObject(request.body);require(insightLong(result["revision"])==Math.addExact(insightLong(frozen["expected_revision"]),1));require(result.getAsJsonObject("document")==frozen.getAsJsonObject("document"));null
            }
            if(current(g,t)&&accountScope()==account){
                store.clear(account)
                mutable.update { current ->
                    val sameEditor=request.kind=="metadata" && current.metadataId==request.target && current.metadataLedger==request.ledger
                    current.copy(pending=null,saving=false,
                        feedback=if(request.kind=="manual")"已確認記帳。"else if(sameEditor)"分析標籤已保存。"else"另一筆帳目的分析標籤已保存。",
                        metadata=if(sameEditor)result else current.metadata,completedLedger=ledgerId,stale=true)
                }
                changed()
            }
        }catch(e:Exception){
            if(e is CancellationException && e !is TimeoutCancellationException)throw e
            if(current(g,t)&&accountScope()==account){failure(e,g,t)
                if(definitiveRejection && e is MobileApiFailure){store.clear(account);mutable.update{it.copy(pending=null,saving=false,feedback=if(e.statusCode==409)"中央版本已變更；請重新讀取後再修改，未覆蓋他人內容。" else "中央未接受這次內容，請修正後再送出。")}}
                else mutable.update{it.copy(saving=false,feedback="結果尚未確認，原請求已保存。請查詢原操作結果。")}
            }
        }}
    }
    fun invalidate(){readGeneration++;reads?.cancel();pages?.cancel();mutable.update{it.copy(stale=true,loading=false,entriesLoading=false)}}
    internal suspend fun awaitIdle(){reads?.join();pages?.join();write?.join();metadataRead?.join()}
}
internal fun parseMetadata(raw:String,id:String,ledger:Boolean):JsonObject=jsonObject(raw).also{v->
    UUID.fromString(v.text("draft_id"));if(ledger)require(v.text("ledger_id")==id)else require(v.text("draft_id")==id)
    insightLong(v["revision"]);validateInsightDocument(v.getAsJsonObject("document"));require(v["sources"].isJsonObject)
}

/** Successful transport is insufficient: the response must describe this exact confirmed intent. */
internal fun validateManualResult(result:JsonObject,body:String):String {
    val requested=jsonObject(body);val amount=insightLong(requested["amount_yuan"]);val purpose=requested.text("purpose")
    val id=result.text("id");UUID.fromString(id);UUID.fromString(result.text("draft_id"));require(result.text("status")=="active")
    val snapshot=result.getAsJsonObject("snapshot");require(snapshot.getAsJsonObject("source").text("kind")=="manual" && snapshot.array("evidence").size()==0)
    val candidate=snapshot.getAsJsonObject("candidate");require(candidate.text("currency")=="TWD" && candidate.text("amount_unit")=="TWD_YUAN")
    require(candidate.text("receipt_date")==requested.text("receipt_date") && insightLong(candidate["printed_total_yuan"])==amount)
    val items=candidate.array("items");require(items.size()==1);val line=items[0].asJsonObject;require(insightLong(line["quantity"])==1L && insightLong(line["printed_line_total_yuan"])==amount)
    val calculation=snapshot.getAsJsonObject("calculation");require(insightLong(calculation["printed_total_yuan"])==amount)
    listOf("self","gift","advance").forEach { key->require(insightLong(calculation["${key}_yuan"])==if(key==purpose)amount else 0L) }
    val decisions=snapshot.getAsJsonObject("review_document").array("line_decisions");require(decisions.size()==1 && decisions[0].asJsonObject.text("whole_purpose")==purpose)
    return id
}
