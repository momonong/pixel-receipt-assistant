package com.momonong.pixelreceipt.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.google.gson.JsonObject
import com.google.gson.JsonNull
import java.time.LocalDate

@Composable internal fun MobileManualScreen(state:MobileUiState,actions:MobileActions,modifier:Modifier){
    var date by rememberSaveable{mutableStateOf(LocalDate.now().toString())}
    var merchant by rememberSaveable{mutableStateOf("")};var description by rememberSaveable{mutableStateOf("")};var amount by rememberSaveable{mutableStateOf("")};var purpose by rememberSaveable{mutableStateOf("self")};var notes by rememberSaveable{mutableStateOf("")}
    var analysis by rememberSaveable{mutableStateOf(defaultInsightDocument().toString())};var extras by rememberSaveable{mutableStateOf(false)}
    val pending=state.insight.pending
    LaunchedEffect(pending?.key) { if(pending?.kind=="manual") { val frozen=jsonObject(pending.body);date=frozen.text("receipt_date");merchant=frozen["merchant"]?.takeUnless{it.isJsonNull}?.asString.orEmpty();description=frozen["description"]?.takeUnless{it.isJsonNull}?.asString.orEmpty();amount=frozen.text("amount_yuan");purpose=frozen.text("purpose");notes=frozen["notes"]?.takeUnless{it.isJsonNull}?.asString.orEmpty();analysis=frozen.getAsJsonObject("analysis").toString() } }
    val locked=state.insight.saving || pending!=null
    val amountValid=amount.matches(Regex("[1-9][0-9]*")) && amount.toLongOrNull()!=null
    val body=runCatching{manualBody(date,merchant,description,amount,purpose,notes,jsonObject(analysis))}.getOrNull()
    LazyColumn(modifier.testTag("manual-form"),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
        item{Text("沒有收據，也能記一筆",style=MaterialTheme.typography.headlineMedium);Text("手動來源・一筆支出一個金額。品名與分類可事後補。",color=MobileMuted)}
        item{OutlinedTextField(amount,{if(!locked)amount=it},label={Text("金額（整數元）")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),singleLine=true,enabled=!locked,modifier=Modifier.fillMaxWidth().testTag("manual-amount"),isError=amount.isNotEmpty()&&!amountValid,supportingText={if(amount.isNotEmpty()&&!amountValid)Text("請輸入大於 0 的整數元，不含逗號或小數。")})}
        item{InsightDateButton("消費日期",LocalDate.parse(date),enabled=!locked){if(!locked){date=it.toString();analysis=jsonObject(analysis).apply{addProperty("date_source","user")}.toString()}}}
        item{InsightChoice("支出歸屬",purpose,linkedMapOf("self" to "自己用","gift" to "送人","advance" to "代買"),enabled=!locked){if(!locked)purpose=it?:"self"}}
        item{OutlinedTextField(description,{if(!locked)description=it.take(500)},label={Text("品名或支出說明（選填）")},enabled=!locked,modifier=Modifier.fillMaxWidth().testTag("manual-description"))}
        item{OutlinedTextField(merchant,{if(!locked)merchant=it.take(500)},label={Text("商家（選填）")},enabled=!locked,modifier=Modifier.fillMaxWidth())}
        item{TextButton(onClick={extras=!extras},modifier=Modifier.heightIn(min=48.dp)){Text(if(extras)"收起分析與備註"else"補充分類、餐別與地區（選填）")}}
        if(extras)item{Column(verticalArrangement=Arrangement.spacedBy(12.dp)){InsightDocumentForm(jsonObject(analysis),!locked,manual=true){analysis=it.toString()};OutlinedTextField(notes,{if(!locked)notes=it.take(4000)},label={Text("備註（選填）")},enabled=!locked,modifier=Modifier.fillMaxWidth())}}
        if(amountValid && body==null)item{Text("請核對分析欄位；消費時間需為 HH:mm。",color=MaterialTheme.colorScheme.error)}
        state.insight.error?.let{item{MobileNotice(it)}}
        if(pending!=null && pending.kind!="manual") item{MobileNotice("另一筆分析標籤結果尚待確認，先查詢原操作後才能新增記帳。") }
        state.insight.feedback?.let{item{MobileNotice(it)}}
        item{LedgerCard{
            Text("確認這筆支出",style=MaterialTheme.typography.titleMedium)
            Text("${amount.toLongOrNull()?.let(::insightMoney)?:"請輸入金額"}・${mapOf("self" to "自己用","gift" to "送人","advance" to "代買")[purpose]}",style=MaterialTheme.typography.titleLarge)
            Text("人工確認後才會建立已入帳帳目；不需要照片。",style=MaterialTheme.typography.bodySmall,color=MobileMuted)
            if(pending!=null)Button(onClick=actions.retryInsightWrite,enabled=!state.insight.saving,modifier=Modifier.fillMaxWidth().heightIn(min=52.dp).testTag("manual-reconcile")){Text("查詢原操作結果")}
            else if(state.insight.completedLedger==null) Button(onClick={body?.let(actions.submitManual)},enabled=body!=null&&!locked&&state.signedIn&&state.reviewer,modifier=Modifier.fillMaxWidth().heightIn(min=52.dp).testTag("manual-confirm")){Text(if(state.insight.saving)"正在確認…"else"確認記帳")}
            state.insight.completedLedger?.let{id->OutlinedButton(onClick={actions.ledgerDetail(id)},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Text("查看已建立帳目")}}
        }}
    }
}

@Composable internal fun MobileInsightEditor(state:MobileUiState,actions:MobileActions,modifier:Modifier){
    val s=state.insight;val metadata=s.metadata
    var document by rememberSaveable(s.metadataId,metadata?.get("revision")?.toString()){mutableStateOf(metadata?.getAsJsonObject("document")?.toString())}
    Column(modifier.verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
        Text("分析標籤",style=MaterialTheme.typography.headlineMedium)
        Text("自動建議可修改；留空不影響記帳，已確認金額保持不變。",color=MobileMuted)
        if(metadata==null) {
            Text(s.feedback?:"正在讀取分析標籤…")
            if(s.pending!=null) Button(onClick=actions.retryInsightWrite,enabled=!s.saving,modifier=Modifier.fillMaxWidth().heightIn(min=52.dp)){Text("查詢原操作結果")}
        }
        else{
            InsightSources(metadata.getAsJsonObject("sources"))
            TextButton(onClick={s.metadataId?.let{actions.editInsight(it,s.metadataLedger,s.metadataReadOnly)}},enabled=!s.saving&&s.pending==null,modifier=Modifier.heightIn(min=48.dp)){Text("重新讀取中央版本（取代未保存編輯）")}
            if(s.metadataReadOnly)Text("這筆已作廢，分析標籤僅供查閱。",color=MobileWarning)
            document?.let{raw->InsightDocumentForm(jsonObject(raw),!s.metadataReadOnly&&!s.saving&&s.pending==null,manual=insightIsEvidenceFreeManual(state),names=insightItemNames(state)){document=it.toString()}}
            s.feedback?.let{MobileNotice(it)}
            if(s.pending!=null)Button(onClick=actions.retryInsightWrite,enabled=!s.saving,modifier=Modifier.fillMaxWidth().heightIn(min=52.dp)){Text("查詢原操作結果")}
            else if(!s.metadataReadOnly)Button(onClick={document?.let{actions.saveInsightMetadata(jsonObject(it))}},enabled=!s.saving&&state.reviewer&&state.signedIn,modifier=Modifier.fillMaxWidth().heightIn(min=52.dp).testTag("insight-metadata-save")){Text(if(s.saving)"正在保存…"else"保存分析標籤")}
        }
    }
}

@Composable internal fun InsightDocumentForm(document:JsonObject,enabled:Boolean,manual:Boolean=false,names:Map<Int,String> = emptyMap(),change:(JsonObject)->Unit){
    fun text(key:String)=document[key]?.takeUnless{it.isJsonNull}?.asString.orEmpty()
    fun edit(key:String,value:String){if(enabled)change(document.deepCopy().apply{addProperty(key,value)})}
    val fields=linkedMapOf("brand" to "品牌","branch" to "分店","address" to "收據地址","city" to "縣市","district" to "地區","transaction_time" to "消費時間（HH:mm）")
    fields.forEach{(key,label)->OutlinedTextField(text(key),{edit(key,it.take(500))},label={Text("$label（選填）")},enabled=enabled,modifier=Modifier.fillMaxWidth(),singleLine=key!="address")}
    InsightChoice("通路",document.text("channel"),linkedMapOf("unknown" to "待補","in_store" to "實體","online" to "線上"),enabled=enabled){if(enabled)edit("channel",it?:"unknown")}
    InsightChoice("日期依據",document.text("date_source"),linkedMapOf("user" to "人工確認","default_today" to "預設今天（待核對）","unknown" to "未知").apply{if(!manual)put("receipt","收據印刷日期")},enabled=enabled){if(enabled)edit("date_source",it?:"unknown")}
    document.array("items").forEachIndexed{index,value->
        val item=value.asJsonObject
        fun itemEdit(key:String,v:String?){if(enabled)change(document.deepCopy().apply{val target=getAsJsonArray("items")[index].asJsonObject;if(v==null)target.add(key,JsonNull.INSTANCE)else target.addProperty(key,v);if(key=="category")target.addProperty("meal",if(v=="food")"unknown"else"not_applicable")})}
        val position=item["item_position"].asInt
        var expanded by rememberSaveable(position) { mutableStateOf(document.array("items").size()==1) }
        HorizontalDivider(color=MobileRule)
        TextButton(onClick={expanded=!expanded},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Text("${position+1}. ${names[position]?:if(manual)"這筆支出"else"對照帳目品項 ${position+1}"}・${insightCategories[item.text("category")]}・${insightMeals[item.text("meal")]} ${if(expanded)"收起"else"修改"}")}
        if(expanded) {

        InsightChoice("生活分類",item.text("category"),insightCategories,enabled=enabled){itemEdit("category",it)}
        OutlinedTextField(item["subcategory"]?.takeUnless{it.isJsonNull}?.asString.orEmpty(),{itemEdit("subcategory",it.take(100))},label={Text("細分類（選填）")},enabled=enabled,modifier=Modifier.fillMaxWidth())
        InsightChoice("餐別",item.text("meal"),insightMeals,enabled=enabled){itemEdit("meal",it)}
        OutlinedTextField(item["meal_group"]?.takeUnless{it.isJsonNull}?.asString.orEmpty(),{itemEdit("meal_group",it.take(100))},label={Text("同餐代號（選填）")},supportingText={Text("同日同餐別、相同代號的帳目合併算一餐。")},enabled=enabled,modifier=Modifier.fillMaxWidth())
        }
    }
}

@Composable private fun InsightSources(sources:JsonObject) {
    val names=mapOf("brand" to "品牌","branch" to "分店","address" to "地址","city" to "縣市","district" to "地區","transaction_time" to "時間","channel" to "通路","date_source" to "日期","category" to "分類","subcategory" to "細分類","meal" to "餐別","meal_group" to "同餐代號")
    val labels=mapOf("user" to "人工補充","notes" to "補充說明","receipt" to "收據","rule" to "規則建議","unknown" to "未知","default_today" to "預設今天")
    var open by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick={open=!open},modifier=Modifier.heightIn(min=48.dp)){Text(if(open)"收起分析來源"else"查看分析來源")}
    if(open)Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
        sources.entrySet().filter{it.key!="items"}.forEach{(k,v)->Text("${names[k]?:k}：${labels[v.asString]?:"未知"}",style=MaterialTheme.typography.bodySmall,color=MobileMuted)}
        sources.array("items").forEach{e->val item=e.asJsonObject;Text("品項 ${item["item_position"].asInt+1}："+item.entrySet().filter{it.key!="item_position"}.joinToString("、"){(k,v)->"${names[k]?:k} ${labels[v.asString]?:"未知"}"},style=MaterialTheme.typography.bodySmall,color=MobileMuted)}
    }
}

internal fun insightItemNames(state:MobileUiState):Map<Int,String> {
    val id=state.insight.metadataId
    if(state.insight.metadataLedger){
        val entry=state.ledgerDetailsJson?.let(::jsonObject)?.get(id)?.takeIf{it.isJsonObject}?.asJsonObject
            ?:state.ledgerJson?.let(::jsonObject)?.array("entries")?.firstOrNull{it.asJsonObject.text("id")==id}?.asJsonObject
        return entry?.let(::ledgerItemNames)?.mapIndexedNotNull{position,name->name?.let{position to it}}?.toMap().orEmpty()
    }
    val batch=state.batches.firstOrNull{it.draftId==id}
    return batch?.review?.editor?.getAsJsonObject("candidate")?.array("items")?.mapIndexedNotNull{position,item->item.asJsonObject["raw_name"]?.takeUnless{it.isJsonNull}?.asString?.let{position to it}}?.toMap().orEmpty()
}

/** Source=manual alone is insufficient: legacy manual drafts may contain receipt photographs. */
internal fun insightIsEvidenceFreeManual(state:MobileUiState):Boolean {
    val id=state.insight.metadataId ?: return false
    val snapshot=if(state.insight.metadataLedger){
        val entry=state.ledgerDetailsJson?.let(::jsonObject)?.get(id)?.takeIf{it.isJsonObject}?.asJsonObject
            ?:state.ledgerJson?.let(::jsonObject)?.array("entries")?.firstOrNull{it.asJsonObject.text("id")==id}?.asJsonObject
        entry?.get("snapshot")?.takeIf{it.isJsonObject}?.asJsonObject
    }else state.batches.firstOrNull{it.draftId==id}?.review?.draft
    val source=snapshot?.get("source")?.takeIf{it.isJsonObject}?.asJsonObject
    val evidence=snapshot?.get("evidence")?.takeIf{it.isJsonArray}?.asJsonArray
    return source?.text("kind")=="manual" && evidence!=null && evidence.size()==0
}
