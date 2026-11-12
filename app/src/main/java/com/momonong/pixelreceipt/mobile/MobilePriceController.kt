package com.momonong.pixelreceipt.mobile

import android.util.AtomicFile
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

internal class FrozenPriceWrite(val kind:String,val target:String?,val key:String,val body:String) {
    override fun toString()="FrozenPriceWrite(contents redacted)"
}
/** Separate write-ahead journal. A broken journal blocks writes, never silently permits a duplicate. */
internal class MobilePriceJournal(cacheDir:File,marker:String) {
    private val root=File(cacheDir,"native-product-prices")
    private val file=AtomicFile(File(root,"$marker.json"))
    private val monitor=locks.computeIfAbsent(root.absolutePath){Any()}
    private fun <T> locked(action:()->T):T=synchronized(monitor){
        check(root.isDirectory || root.mkdirs())
        RandomAccessFile(File(root,"journal.lock"),"rw").use{it.channel.lock().use{action()}}
    }
    fun read():FrozenPriceWrite?=locked { readUnlocked() }
    private fun readUnlocked():FrozenPriceWrite? {
        if(!file.baseFile.exists() && !File(file.baseFile.path+".bak").exists())return null
        val value=jsonObject(file.openRead().use{readBounded(it,512*1024)}.toString(Charsets.UTF_8))
        val kind=value.text("kind");val target=value.text("target").ifBlank{null};val key=value.text("key");val body=value.text("body")
        require(kind in setOf("product_create","price_review") && validUuid(key) && body.length<=256*1024)
        require((kind=="product_create" && target==null) || (kind=="price_review" && target!=null && validUuid(target)))
        require(value.text("body_sha256")==sha256(body.toByteArray(Charsets.UTF_8)));jsonObject(body)
        return FrozenPriceWrite(kind,target,key,body)
    }
    fun write(value:FrozenPriceWrite)=locked {
        check(readUnlocked()==null){"尚有結果未知的商品操作"}
        val json=JsonObject().apply{addProperty("kind",value.kind);addProperty("target",value.target);addProperty("key",value.key);addProperty("body",value.body);addProperty("body_sha256",sha256(value.body.toByteArray(Charsets.UTF_8)))}
        val out=file.startWrite();try{out.write(json.toString().toByteArray(Charsets.UTF_8));file.finishWrite(out)}catch(error:Exception){file.failWrite(out);throw error}
    }
    fun clear(key:String)=locked {check(readUnlocked()?.key==key);file.delete()}
    companion object {private val locks=ConcurrentHashMap<String,Any>()}
}
internal class MobilePriceController(
    private val api:MobileApi,private val scope:CoroutineScope,private val token:()->String?,
    private val unauthorized:(String)->Unit,private val changed:()->Unit,private val cacheDir:File,
    private val accountScope:()->String?={null},
) {
    private val mutable=MutableStateFlow(PriceUiState());val state=mutable.asStateFlow()
    private val generation=AtomicLong();private var job:Job?=null
    private fun current(g:Long,t:String,s:String)=generation.get()==g && token()==t && accountScope()==s
    private fun journal(s:String)=MobilePriceJournal(cacheDir,sha256(s.toByteArray(Charsets.UTF_8)))
    private fun start(writing:Boolean=false,action:suspend (Long,String,String)->Unit){
        if(state.value.writing)return
        val t=token();if(t==null){mutable.update{it.copy(error="登入後才能查看商品價格。",loading=false)};return}
        val s=accountScope()?.takeIf{validUuid(it)}
        if(s==null){mutable.update{it.copy(error="中央帳本識別尚未確認，請重新連線確認帳本後操作。",loading=false,writing=false)};return}
        val g=generation.incrementAndGet();job?.cancel()
        mutable.update{it.copy(loading=!writing,writing=writing,error=null,message=null)}
        job=scope.launch {
            try{action(g,t,s)}catch(error:Exception){
                if(error is CancellationException && error !is TimeoutCancellationException)throw error
                if(current(g,t,s)){
                    if(error is MobileApiFailure && error.statusCode==401)unauthorized(t)
                    mutable.update{it.copy(error=priceError(error))}
                }
            }finally{if(current(g,t,s))mutable.update{it.copy(loading=false,writing=false)}}
        }
    }
    fun open(ledgerId:String?=null){
        require(ledgerId==null || validUuid(ledgerId))
        if(ledgerId!=state.value.ledgerId){generation.incrementAndGet();job?.cancel();mutable.value=PriceUiState(open=true,ledgerId=ledgerId)}
        else mutable.update{it.copy(open=true)}
        refresh()
    }
    fun close(){generation.incrementAndGet();job?.cancel();mutable.update{it.copy(open=false,loading=false,writing=false)}}
    fun accountChanged(){generation.incrementAndGet();job?.cancel();mutable.value=PriceUiState()}
    fun search(query:String){if(state.value.writing || state.value.pending)return;require(query.length<=500);mutable.update{it.copy(query=query,products=emptyList(),catalogNext=null)};refresh()}
    fun showCatalog(){if(state.value.writing)return;generation.incrementAndGet();job?.cancel();mutable.update{it.copy(selectedProduct=null,prices=null,loading=false)};refresh()}
    fun refresh()=start {g,t,s->
        val pending=journal(s).read();if(!current(g,t,s))return@start
        mutable.update{it.copy(pending=pending!=null)}
        if(pending!=null){reconcile(g,t,s,pending,false);return@start}
        readScreen(g,t,s)
    }
    private suspend fun readScreen(g:Long,t:String,s:String){
        val screen=state.value
        if(screen.selectedProduct!=null){
            val prices=parseProductPrices(api.productPrices(t,screen.selectedProduct.id,0),screen.selectedProduct.id,0)
            if(current(g,t,s))mutable.update{it.copy(prices=prices,selectedProduct=prices.product)}
        }else{
            val catalog=parsePriceCatalog(api.products(t,screen.query,0),screen.query,0)
            if(current(g,t,s))mutable.update{it.copy(products=catalog.products,catalogNext=catalog.next)}
        }
        if(!current(g,t,s))return
        screen.ledgerId?.let{id->readCurrentReview(g,t,s,id)}
    }
    private suspend fun readCurrentReview(g:Long,t:String,s:String,id:String){
        fun currentScreen()=current(g,t,s) && state.value.ledgerId==id
        if(!currentScreen())return
        val review=parsePriceReview(api.priceReview(t,id),id)
        if(!currentScreen())return
        val (voided,adjustments)=priceAdjustments(api.ledgerDetail(t,id),id)
        if(currentScreen())mutable.update{it.copy(review=review,adjustments=adjustments,voided=voided)}
    }
    fun selectProduct(id:String){
        if(state.value.writing || state.value.pending)return
        val p=state.value.products.firstOrNull{it.id==id} ?: return
        mutable.update{it.copy(selectedProduct=p,prices=null)};refresh()
    }
    fun loadMore()=start {g,t,s->
        val screen=state.value
        if(screen.selectedProduct!=null){
            val offset=screen.prices?.next?:return@start
            val page=parseProductPrices(api.productPrices(t,screen.selectedProduct.id,offset),screen.selectedProduct.id,offset)
            if(current(g,t,s))mutable.update{it.copy(prices=page.copy(observations=screen.prices.observations+page.observations))}
        }else{
            val offset=screen.catalogNext?:return@start;val page=parsePriceCatalog(api.products(t,screen.query,offset),screen.query,offset)
            if(current(g,t,s))mutable.update{it.copy(products=(screen.products+page.products).distinctBy{p->p.id},catalogNext=page.next)}
        }
    }
    fun createProduct(name:String,brand:String?,specification:String,purchaseUnit:String){
        val fields=listOf(name,specification,purchaseUnit)
        if(fields.any{it.trim().isEmpty() || it.length>500} || (brand?.length?:0)>500){mutable.update{it.copy(error="請填商品名稱、確切規格與購買單位，每欄最多 500 字。")};return}
        val body=JsonObject().apply{addProperty("display_name",name.trim());addProperty("brand",brand?.trim()?.takeIf{it.isNotEmpty()});addProperty("specification",specification.trim());addProperty("purchase_unit",purchaseUnit.trim())}.toString()
        mutate(FrozenPriceWrite("product_create",null,UUID.randomUUID().toString(),body))
    }
    fun saveReview(ledgerId:String,body:String){
        if(state.value.voided || state.value.ledgerId!=ledgerId || state.value.review?.ledgerId!=ledgerId){mutable.update{it.copy(error="帳目無法核對，請重新讀取。")};return}
        try{
            val request=jsonObject(body);require(strictLong(request["expected_revision"])==state.value.review!!.revision)
            require(body.length<=256*1024 && request.has("item_mappings") && request.has("adjustment_decisions"))
        }catch(_:Exception){mutable.update{it.copy(error="價格核對內容無效，請重新讀取。")};return}
        mutate(FrozenPriceWrite("price_review",ledgerId,UUID.randomUUID().toString(),body))
    }
    private fun mutate(attempt:FrozenPriceWrite)=start(true){g,t,s->
        val j=journal(s);val pending=j.read()
        if(pending!=null){if(current(g,t,s))mutable.update{it.copy(pending=true,error="先查詢原操作結果，再開始新的商品操作。")};return@start}
        if(!current(g,t,s))return@start
        j.write(attempt);mutable.update{it.copy(pending=true)}
        dispatch(g,t,s,attempt)
    }
    fun retryPending()=start(true){g,t,s->val pending=journal(s).read()?:return@start;mutable.update{it.copy(pending=true)};reconcile(g,t,s,pending,true)}
    private suspend fun reconcile(g:Long,t:String,s:String,attempt:FrozenPriceWrite,retry:Boolean){
        val response=try{api.analysisOperation(t,attempt.key)}catch(error:MobileApiFailure){
            if(error.statusCode!=404)throw error
            if(!current(g,t,s))return
            if(retry){dispatch(g,t,s,attempt);return}
            mutable.update{it.copy(pending=true,error="原操作尚未查到成功紀錄。可查詢，或明確使用原請求重試；內容與識別碼保持不變。")};return
        }
        if(!current(g,t,s))return
        val value=jsonObject(response);require(value.text("operation_id")==attempt.key && value.text("kind")==attempt.kind)
        val target=value.text("target_id");require(validUuid(target) && (attempt.target==null || target==attempt.target))
        val result=value.getAsJsonObject("result");if(attempt.kind=="product_create")require(result.text("id")==target)
        complete(g,t,s,attempt,result.toString(),historical=true)
        // A journal is ledger-wide; its target need not be the editor currently on screen.
        if(attempt.kind=="price_review" && current(g,t,s))readScreen(g,t,s)
    }
    private suspend fun dispatch(g:Long,t:String,s:String,attempt:FrozenPriceWrite){
        try{
            val result=if(attempt.kind=="product_create")api.createProduct(t,attempt.body,attempt.key) else api.savePriceReview(t,attempt.target!!,attempt.body,attempt.key)
            if(current(g,t,s)){
                val otherScreen=attempt.kind=="price_review" && state.value.ledgerId!=attempt.target
                complete(g,t,s,attempt,result,historical=otherScreen)
                if(otherScreen && current(g,t,s))readScreen(g,t,s)
            }
        }catch(error:MobileApiFailure){
            // These responses explicitly reject the operation. Transport loss, cancellation and 5xx stay unknown.
            if(current(g,t,s) && error.statusCode in setOf(400,403,404,409,422)){
                journal(s).clear(attempt.key);mutable.update{it.copy(pending=false,review=if(attempt.kind=="price_review" && error.statusCode==409 && it.ledgerId==attempt.target)null else it.review)}
            }
            throw error
        }
    }
    private fun complete(g:Long,t:String,s:String,attempt:FrozenPriceWrite,result:String,historical:Boolean=false){
        val product=if(attempt.kind=="product_create")parsePriceProduct(jsonObject(result)) else null
        val review=if(attempt.kind=="price_review")parsePriceReview(result,attempt.target!!) else null
        val sent=jsonObject(attempt.body);val received=jsonObject(result)
        if(product!=null)require(listOf("display_name","brand","specification","purchase_unit").all{received[it]==sent[it]})
        if(review!=null){
            require(review.revision==Math.addExact(strictLong(sent["expected_revision"]),1L))
            require(received["item_mappings"]==sent["item_mappings"] && received["adjustment_decisions"]==sent["adjustment_decisions"])
        }
        if(!current(g,t,s))return
        journal(s).clear(attempt.key)
        mutable.update{screen->
            val reviewForScreen=review?.takeIf{it.ledgerId==screen.ledgerId}
            screen.copy(pending=false,selectedProduct=product?:screen.selectedProduct,prices=if(product!=null)null else screen.prices,
                review=if(historical && reviewForScreen!=null)null else reviewForScreen?:screen.review,
                message=if(product!=null)"商品已建立。請逐列確認相同商品、規格與單位。" else if(historical)"原價格核對操作已確認完成；以下顯示目前頁面的資料。" else "商品對應與價格分配已儲存。",error=null)
        }
        changed()
    }
    internal suspend fun awaitIdle(){job?.join()}
}
private fun priceError(error:Exception):String=when(error){
    is MobileApiFailure->when(error.statusCode){401->"登入已過期，請重新登入。";403->"目前帳號沒有核對權限。";409->"資料版本已更新或帳目已作廢。請重新讀取，再確認修改。";422->"分配未通過核對：請檢查各列金額、適用範圍與合計。";else->"中央服務暫時無法完成操作。結果未知時請查詢原操作。"}
    is IOException->"連線中斷，未確認最新資料。若有已送出的操作，請先查詢原結果。"
    else->"資料無法安全讀取或保存，尚未完成。請重新讀取；結果未知時先查詢原操作。"
}
