package com.momonong.pixelreceipt.mobile

import android.app.DatePickerDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import java.time.LocalDate

@Composable
internal fun MobileInsightScreen(state:MobileUiState,actions:MobileActions,modifier:Modifier,list:LazyListState){
    val s=state.insight;val r=s.report
    val density=LocalDensity.current
    val windowWidth=with(density){LocalWindowInfo.current.containerSize.width.toDp()}
    val expanded=windowWidth>=600.dp && density.fontScale<=1.3f
    var filters by rememberSaveable{mutableStateOf(false)}
    var dimension by rememberSaveable{mutableStateOf("categories")}
    var coverageOpen by rememberSaveable{mutableStateOf(false)}
    if(filters)InsightFilters(s,{filters=false},actions.insightQuery)
    LazyColumn(modifier.testTag("insight-list"),state=list,contentPadding=PaddingValues(20.dp,12.dp,20.dp,24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
        item{Column(verticalArrangement=Arrangement.spacedBy(6.dp)){Text("看懂生活支出",style=MaterialTheme.typography.headlineMedium);Text("有效帳目・自己用＋送人；代買另外列出。",color=MobileMuted)}}
        item{FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){
            OutlinedButton(onClick={filters=true},modifier=Modifier.heightIn(min=48.dp).testTag("insight-filter-open")){Text("篩選")}
            TextButton(onClick=actions.refreshInsights,enabled=!s.loading,modifier=Modifier.heightIn(min=48.dp)){Text("更新")}
            TextButton(onClick={actions.prices(null)},modifier=Modifier.heightIn(min=48.dp)){Text("商品價格")}
        }}
        item{Text(insightRange(s.query),style=MaterialTheme.typography.bodyMedium,color=MobileTealInk)}
        if(!state.signedIn)item{Button(onClick=actions.settings,modifier=Modifier.heightIn(min=48.dp)){Text("登入後查看分析")}}
        if(s.loading)item{LinearProgressIndicator(Modifier.fillMaxWidth())}
        s.error?.let{item{MobileNotice(it)}}
        if(s.pending!=null)item{LedgerCard{Text("有一筆操作結果待確認",color=MobileWarning);OutlinedButton(onClick=actions.recoverInsightPending,modifier=Modifier.heightIn(min=48.dp)){Text("查看原請求與結果")}}}
        if(r!=null){
            item{LedgerCard{
                Text("個人支出",style=MaterialTheme.typography.titleMedium)
                Text(insightMoney(r.summary.getValue("personal_yuan")),style=MaterialTheme.typography.headlineMedium,color=MobileTealInk)
                Text("${r.summary.getValue("receipt_count")} 筆・代買 ${insightMoney(r.summary.getValue("advance_yuan"))}")
                r.comparisonDelta?.let{Text("較 ${s.query.compareFrom} ～ ${s.query.compareTo}：${if(it>0)"+" else ""}${insightMoney(it)}",color=MobileMuted,style=MaterialTheme.typography.bodySmall)}
                if(s.stale)Text("顯示上次資料，請更新確認最新狀態。",color=MobileWarning)
            }}
            val needs=r.coverage.filter{it.value!=0L}
            if(needs.isNotEmpty())item{Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
                TextButton(onClick={coverageOpen=!coverageOpen},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("insight-coverage-expand")) { Text("資料待補：${needs.size} 項・${if(coverageOpen)"收起 ▴"else"查看 ▾"}") }
                if(coverageOpen)LedgerCard{
                    Text("資料可慢慢補，金額已入帳",style=MaterialTheme.typography.titleSmall)
                    needs.forEach{(k,v)->Text("${coverageLabel(k)}：${if(k.endsWith("count"))"$v 筆" else insightMoney(v)}",color=MobileWarning,style=MaterialTheme.typography.bodyMedium)}
                    if(r.coverage.getValueOrDefault("undated_receipt_count")>0)TextButton(onClick={actions.insightQuery(InsightQuery(undatedOnly=true))},modifier=Modifier.heightIn(min=48.dp)){Text("查看缺日期帳目")}
                }
            }}
            item{InsightDimensionChooser(dimension){dimension=it}}
            val groups=when(dimension){"purpose"->listOf(InsightGroup("self","自己用",r.summary.getValue("self_yuan"),0,0),InsightGroup("gift","送人",r.summary.getValue("gift_yuan"),0,0),InsightGroup("advance","代買（另列）",r.summary.getValue("advance_yuan"),0,0));"discount"->listOf(InsightGroup("discount","已明列折扣",r.summary.getValue("explicit_discount_yuan"),0,0));"series"->r.series;else->r.groups[dimension].orEmpty()}
            if(dimension=="discount")item{Text("符合帳目明列折扣包含代買；內含折扣不重扣。",style=MaterialTheme.typography.bodyMedium,color=MobileMuted)}
            if(groups.isEmpty())item{Text("這個範圍尚無可顯示資料。",color=MobileMuted)}
            items(groups.chunked(if(expanded)2 else 1),key={it.first().key}){pair->
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(16.dp)){
                    pair.forEach{g->Box(Modifier.weight(1f)){InsightGroupRow(g,groups.maxOfOrNull{it.personal}?:0,dimension){
                val q=when(dimension){"categories"->s.query.copy(category=g.key.takeIf{it in insightCategories});"meals"->s.query.copy(meal=g.key.takeIf{it in insightMeals});"brands"->s.query.copy(brandKey=g.key);"merchants"->s.query.copy(merchantKey=g.key);"branches"->s.query.copy(branchKey=g.key);"cities"->s.query.copy(cityKey=g.key);"districts"->s.query.copy(districtKey=g.key);"series"->s.query.copy(dateFrom=g.from,dateTo=g.to,compareFrom=null,compareTo=null);else->null}
                if(q!=null && g.key!="unallocated")actions.insightQuery(q)
                    }}}
                    if(expanded && pair.size==1)Spacer(Modifier.weight(1f))
                }
            }
            item{Text("對應帳目",style=MaterialTheme.typography.titleLarge);Text("只列出符合目前所有條件的品項負擔。",style=MaterialTheme.typography.bodySmall,color=MobileMuted)}
            if(s.entriesLoading)item{LinearProgressIndicator(Modifier.fillMaxWidth())}
            items(s.entries,key={it.text("id")}){e->LedgerCard(Modifier.clickable{actions.ledgerDetail(e.text("id"))}){
                val candidate=e.getAsJsonObject("snapshot").getAsJsonObject("candidate")
                Text(ledgerMerchant(e),style=MaterialTheme.typography.titleMedium)
                Text("${candidate.text("receipt_date")}・符合條件 ${insightMoney(insightLong(e["matched_personal_yuan"]))}")
                Text("查看帳目 ›",color=MobileTealInk)
            }}
            if(s.nextOffset!=null)item{OutlinedButton(onClick=actions.moreInsights,enabled=!s.entriesLoading,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Text("載入更多帳目")}}
        }
    }
}
private val dimensionLabels=linkedMapOf("categories" to "生活分類","subcategories" to "細分類","meals" to "餐別","brands" to "品牌","merchants" to "店家","branches" to "分店","cities" to "縣市","districts" to "地區","series" to "消費時間","weekdays" to "週間分布","times" to "時段","purpose" to "支出歸屬","discount" to "折扣")
@Composable private fun InsightDimensionChooser(selected:String,change:(String)->Unit){
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick={open=true},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("insight-dimension-selector")){Text("分析面向：${dimensionLabels.getValue(selected)} ▾")}
        DropdownMenu(expanded=open,onDismissRequest={open=false},modifier=Modifier.heightIn(max=520.dp)){
            dimensionLabels.forEach{(key,label)->DropdownMenuItem(text={Text(label)},onClick={change(key);open=false},modifier=Modifier.heightIn(min=48.dp))}
        }
    }
}
@Composable private fun InsightGroupRow(g:InsightGroup,max:Long,dimension:String,open:()->Unit){
    val interactive=dimension in setOf("categories","meals","brands","merchants","branches","cities","districts","series") && g.key!="unallocated"
    LedgerCard(if(interactive)Modifier.clickable(onClick=open) else Modifier){
        val label=g.label?:if(dimension=="categories")insightCategories[g.key] else if(dimension=="meals")insightMeals[g.key] else null
        Text(label?:if(g.key=="unallocated")"待分配"else "未填資料",style=MaterialTheme.typography.titleSmall)
        Text(insightMoney(g.personal),style=MaterialTheme.typography.titleLarge,color=MobileTealInk)
        if(max>0 && g.personal>=0)LinearProgressIndicator(progress={(g.personal.toDouble()/max).toFloat().coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth(),color=MobileTeal,trackColor=MobileTealSoft)
        if(g.receipts>0)Text("${g.receipts} 筆・${g.items} 個品項${g.meals?.let{"・$it 餐"}.orEmpty()}",style=MaterialTheme.typography.bodySmall,color=MobileMuted)
        g.average?.let{Text("${if(dimension=="meals")"每餐"else"每筆"}平均 NT$ $it",style=MaterialTheme.typography.bodyMedium)}
        if(interactive)Text("查看這個範圍 ›",color=MobileTealInk,style=MaterialTheme.typography.labelLarge)
    }
}
internal fun insightRange(q:InsightQuery)=buildList{add(if(q.undatedOnly)"缺日期帳目"else q.dateFrom?.let{"$it ～ ${q.dateTo}"}?:"全部期間");q.category?.let{add(insightCategories.getValue(it))};q.meal?.let{add(insightMeals.getValue(it))};if(q.cityKey!=null)add("已選縣市");if(q.brandKey!=null)add("已選品牌");if(q.merchantKey!=null)add("已選店家");if(q.branchKey!=null)add("已選分店");if(q.districtKey!=null)add("已選地區");q.channel?.let{add(if(it=="online")"線上" else if(it=="in_store")"實體"else"通路待補")};q.dayType?.let{add(if(it=="weekday")"平日"else"週末")}}.joinToString("・")
private fun coverageLabel(key:String)=mapOf("uncategorized_personal_yuan" to "未分類","unknown_city_personal_yuan" to "縣市待補","unknown_meal_personal_yuan" to "餐別待補","undated_receipt_count" to "缺日期帳目","undated_personal_yuan" to "缺日期支出","estimated_date_receipt_count" to "待核對日期","pending_price_receipt_count" to "價格待核對","unallocated_personal_yuan" to "待分配")[key]?:key
private fun Map<String,Long>.getValueOrDefault(key:String)=get(key)?:0L

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun InsightFilters(s:InsightUiState,dismiss:()->Unit,apply:(InsightQuery)->Unit){
    var q by remember{mutableStateOf(s.query)}
    ModalBottomSheet(onDismissRequest=dismiss){Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        Text("篩選支出",style=MaterialTheme.typography.headlineSmall)
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){
            listOf(SpendingPeriod.MonthToDate,SpendingPeriod.PreviousMonth,SpendingPeriod.YearToDate,SpendingPeriod.All).forEach { period->
                OutlinedButton(onClick={q=q.withPeriod(period)},modifier=Modifier.heightIn(min=48.dp)){Text(period.label)}
            }
        }
        InsightDateButton("開始日期",q.dateFrom?:LocalDate.now()){d->q=q.copy(dateFrom=d,dateTo=maxOf(d,q.dateTo?:d),undatedOnly=false,compareFrom=null,compareTo=null)}
        InsightDateButton("結束日期",q.dateTo?:LocalDate.now()){d->q=q.copy(dateFrom=minOf(d,q.dateFrom?:d),dateTo=d,undatedOnly=false,compareFrom=null,compareTo=null)}
        InsightChoice("生活分類",q.category,insightCategories,true){q=q.copy(category=it)}
        InsightChoice("餐別",q.meal,insightMeals,true){q=q.copy(meal=it)}
        InsightChoice("通路",q.channel,linkedMapOf("in_store" to "實體","online" to "線上","unknown" to "待補"),true){q=q.copy(channel=it)}
        InsightChoice("消費日",q.dayType,linkedMapOf("weekday" to "平日","weekend" to "週末"),true){q=q.copy(dayType=it)}
        listOf(Triple("店家",s.report?.groups?.get("merchants"),q.merchantKey),Triple("品牌",s.report?.groups?.get("brands"),q.brandKey),Triple("分店",s.report?.groups?.get("branches"),q.branchKey),Triple("縣市",s.report?.groups?.get("cities"),q.cityKey),Triple("地區",s.report?.groups?.get("districts"),q.districtKey)).forEach{(label,groups,value)->InsightChoice(label,value,groups.orEmpty().filter{it.key!="unallocated"}.associate{it.key to (it.label?:"未填資料")},true){key->q=when(label){"店家"->q.copy(merchantKey=key);"品牌"->q.copy(brandKey=key);"分店"->q.copy(branchKey=key);"縣市"->q.copy(cityKey=key);else->q.copy(districtKey=key)}}}
        Row{Checkbox(q.undatedOnly,{q=q.copy(undatedOnly=it,dateFrom=null,dateTo=null,compareFrom=null,compareTo=null)},modifier=Modifier.sizeIn(minWidth=48.dp,minHeight=48.dp));Text("只看缺日期帳目",modifier=Modifier.padding(top=12.dp))}
        TextButton(onClick={q=InsightQuery()},modifier=Modifier.heightIn(min=48.dp)){Text("清除所有條件")}
        Button(onClick={apply(q);dismiss()},modifier=Modifier.fillMaxWidth().heightIn(min=52.dp).testTag("insight-filter-apply")){Text("套用篩選")}
    }}
}
@Composable internal fun InsightDateButton(label:String,date:LocalDate,enabled:Boolean=true,change:(LocalDate)->Unit){val context=LocalContext.current;OutlinedButton(onClick={DatePickerDialog(context,{_,y,m,d->change(LocalDate.of(y,m+1,d))},date.year,date.monthValue-1,date.dayOfMonth).show()},enabled=enabled,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Text("$label：${date.year}年${date.monthValue}月${date.dayOfMonth}日")}}
@Composable internal fun InsightChoice(label:String,value:String?,options:Map<String,String>,nullable:Boolean=false,enabled:Boolean=true,change:(String?)->Unit){
    var open by remember{mutableStateOf(false)}
    Column{Text(label,style=MaterialTheme.typography.labelLarge);Box{OutlinedButton(onClick={open=true},enabled=enabled,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("insight-choice-$label")){Text(options[value]?:if(value==null)"全部"else"已選條件")};DropdownMenu(expanded=open,onDismissRequest={open=false}){if(nullable)DropdownMenuItem(text={Text("全部")},onClick={change(null);open=false});options.forEach{(key,text)->DropdownMenuItem(text={Text(text)},onClick={change(key);open=false})}}}}
}
