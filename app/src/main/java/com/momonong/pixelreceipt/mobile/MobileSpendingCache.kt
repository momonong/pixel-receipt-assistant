package com.momonong.pixelreceipt.mobile

import android.util.AtomicFile
import com.google.gson.Gson
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap

data class MobileSpendingCache(val queryPath: String, val response: String, val fetchedAt: Long, val marker: String) {
    override fun toString() = "MobileSpendingCache(derived report; contents redacted)"
}

/** Bounded, disposable analysis only. Never writes the receipt index, evidence or credentials. */
class MobileSpendingCacheFile(private val root: File) {
    private val gson=Gson()
    private val file=AtomicFile(File(root,"report.json"))
    private val monitor=locks.computeIfAbsent(root.canonicalPath){Any()}
    private fun <T> locked(action:()->T):T=synchronized(monitor){
        check(root.isDirectory || root.mkdirs())
        RandomAccessFile(File(root,"cache.lock"),"rw").use { it.channel.lock().use { action() } }
    }
    fun read():MobileSpendingCache? {
        if(!root.exists())return null
        return runCatching { locked {
            if(!file.baseFile.exists() && !File(root,"report.json.bak").exists())return@locked null
            val bytes=file.openRead().use {readBounded(it,2*1024*1024)}
            gson.fromJson(bytes.toString(Charsets.UTF_8),MobileSpendingCache::class.java).also {validate(it)}
        } }.getOrNull() // A damaged derived cache is never grounds to reject a user's receipt file.
    }
    fun write(value:MobileSpendingCache,currentMarker:()->String,stillCurrent:()->Boolean):Boolean {
        if(runCatching{validate(value)}.isFailure)return false
        return locked {
            if(!stillCurrent() || value.marker!=currentMarker())return@locked false
            val bytes=gson.toJson(value).toByteArray(Charsets.UTF_8)
            val output=file.startWrite()
            try { output.write(bytes);file.finishWrite(output) } catch(error:Exception){file.failWrite(output);throw error}
            true
        }
    }
    private fun validate(value:MobileSpendingCache){
        require(value.queryPath.startsWith("v1/spending?") && allowedSpendingRequest(value.queryPath))
        require(value.response.toByteArray(Charsets.UTF_8).size<=512*1024)
        require(value.fetchedAt>=0 && value.marker.length in 1..128 && value.marker.none(Char::isISOControl))
    }
    companion object {private val locks=ConcurrentHashMap<String,Any>()}
}

/** Local evidence of a changed ledger, not a monetary calculation or a server revision. */
internal fun spendingMarker(state:MobileState):String=sha256(buildString {
    append(state.ledgerJson.orEmpty());append('\u0000');append(state.ledgerDetailsJson.orEmpty());append('\u0000')
    state.batches.filter {it.ledgerId!=null || it.review?.confirmation!=null || it.draftStatus in setOf("confirmed","voided")}.sortedBy{it.id}.forEach {
        append(it.id);append('|');append(it.ledgerId);append('|');append(it.ledgerStatus);append('|');append(it.draftStatus);append('|');append(it.review?.confirmation!=null);append('\u0000')
    }
    state.mutations.orEmpty().filter {it.phase=="pending" && it.kind in setOf("ledger_void","ledger_names")}.forEach {append(it.kind);append('|');append(it.targetId);append('|');append(it.bodySha256);append('\u0000')}
}.toByteArray(Charsets.UTF_8))
