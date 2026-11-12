package com.momonong.pixelreceipt.mobile

import android.util.AtomicFile
import com.google.gson.JsonObject
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate

internal fun insightAccountKey(account:String)=MessageDigest.getInstance("SHA-256").digest(account.toByteArray()).joinToString(""){"%02x".format(it)}
internal fun insightQueryFromJson(raw:String):InsightQuery {
    val j=jsonObject(raw)
    fun text(k:String)=j[k]?.takeUnless{it.isJsonNull}?.asString
    fun date(k:String)=text(k)?.let(LocalDate::parse)
    return InsightQuery(date("date_from"),date("date_to"),date("compare_from"),date("compare_to"),text("category"),text("meal"),text("brand_key"),text("branch_key"),text("city_key"),text("district_key"),text("channel"),text("day_type"),j["undated_only"]?.asBoolean?:false,text("merchant_key")).also{it.filters()}
}
internal class InsightReportCache(private val directory:File){
    private fun file(account:String)=AtomicFile(File(directory,"insight-report-${insightAccountKey(account)}.json"))
    @Synchronized fun read(account:String,query:InsightQuery):InsightReport? {
        val f=file(account);if(!f.baseFile.exists() && !File(f.baseFile.path+".bak").exists())return null
        return f.openRead().use{parseInsightReport(readBounded(it,16*1024*1024).toString(Charsets.UTF_8),query)}
    }
    @Synchronized fun write(account:String,raw:String){directory.mkdirs();val f=file(account);val out=f.startWrite();try{out.write(raw.toByteArray());f.finishWrite(out)}catch(e:Exception){f.failWrite(out);throw e}}
}

/** A credential-bound hint permits cached reads only. It never authorizes a mutation or lookup. */
internal class InsightScopeHintStore(directory:File){
    private val file=AtomicFile(File(directory,"analysis-last-verified-scope.json"))
    @Synchronized fun read(token:String):String? {
        if(!file.baseFile.exists() && !File(file.baseFile.path+".bak").exists())return null
        return file.openRead().use{input->val value=jsonObject(readBounded(input,4096).toString(Charsets.UTF_8));
            if(value.text("credential_fingerprint")!=sha256(token.toByteArray(Charsets.UTF_8)))null
            else value.text("ledger_id").also{java.util.UUID.fromString(it)}
        }
    }
    @Synchronized fun write(token:String,ledgerId:String){
        java.util.UUID.fromString(ledgerId);file.baseFile.parentFile?.mkdirs()
        val output=file.startWrite()
        try{output.write(JsonObject().apply{addProperty("credential_fingerprint",sha256(token.toByteArray(Charsets.UTF_8)));addProperty("ledger_id",ledgerId)}.toString().toByteArray(Charsets.UTF_8));file.finishWrite(output)}
        catch(error:Exception){file.failWrite(output);throw error}
    }
}
