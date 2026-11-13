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
    var dimension by rememberSaveable(s.query.category){mutableStateOf(if(s.query.category!=null)"merchants"else"meals")}
    var allGroups by rememberSaveable(dimension,s.query){mutableStateOf(false)}
    var coverageOpen by rememberSaveable{mutableStateOf(false)}
    if(filters)InsightFilters(s,{filters=false},actions.insightQuery)
    LazyColumn(modifier.testTag("insight-list"),state=list,contentPadding=PaddingValues(20.dp,12.dp,20.dp,24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
        item{Column(verticalArrangement=Arrangement.spacedBy(6.dp)){Text(if(s.query.category=="food")"餐飲都花在哪"else"看懂生活支出",style=MaterialTheme.typography.headlineSmall);Text("有效帳目・自己用＋送人；代買另外列出。",color=MobileMuted,style=MaterialTheme.typography.bodyMedium)}}
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
                Text(if(s.query.category=="food")"餐飲個人支出"else"個人支出",style=MaterialTheme.typography.titleMedium)
                Text(insightMoney(r.summary.getValue("personal_yuan")),style=MaterialTheme.typography.headlineMedium,color=MobileTealInk)
                Text("${r.summary.getValue("receipt_count")} 筆・代買 ${insightMoney(r.summary.getValue("advance_yuan"))}")
                if(s.query.category=="food" && s.query.merchantKey==null){
                    val top=r.groups["merchants"].orEmpty().filter{it.personal>0 && it.key!="unallocated" && !it.label.isNullOrBlank()}.maxByOrNull{it.personal}
                    top?.let{g->TextButton(onClick={insightDrillQuery(s.query,"merchants",g)?.let(actions.insightQuery)},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("insight-dining-highest")){
                        val share=if(r.groups["merchants"].orEmpty().none{it.personal<0})"占目前餐飲 ${insightPercent(g.personal,r.summary.getValue("personal_yuan"))}"else"含待分配調整"
                        Text("已知店家花最多：${g.label}\n${insightMoney(g.personal)}・$share",maxLines=4,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }}
                }
                r.comparisonDelta?.let{Text("較 ${s.query.compareFrom} ～ ${s.query.compareTo}：${if(it>0)"+" else ""}${insightMoney(it)}",color=MobileMuted,style=MaterialTheme.typography.bodySmall)}
                if(s.stale)Text("顯示上次資料，請更新確認最新狀態。",color=MobileWarning)
            }}
            // Each panel needs at least 340dp; narrow foldables and large text remain a single column.
            if(windowWidth>=760.dp && density.fontScale<=1.3f)item{
                Row(Modifier.fillMaxWidth().testTag("insight-charts-pair"),horizontalArrangement=Arrangement.spacedBy(16.dp)){
                    Box(Modifier.weight(1f)){InsightCompositionCard(r,actions.insightQuery)}
                    Box(Modifier.weight(1f)){InsightTrendCard(r,actions.insightQuery)}
                }
            }else{
                item{InsightCompositionCard(r,actions.insightQuery)}
                item{InsightTrendCard(r,actions.insightQuery)}
            }
            val needs=r.coverage.filter{it.value!=0L}
            if(needs.isNotEmpty())item{Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
                TextButton(onClick={coverageOpen=!coverageOpen},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("insight-coverage-expand")) { Text("資料待補：${needs.size} 項・${if(coverageOpen)"收起 ▴"else"查看 ▾"}") }
                if(coverageOpen)LedgerCard{
                    Text("資料可慢慢補，金額已入帳",style=MaterialTheme.typography.titleSmall)
                    needs.forEach{(k,v)->Text("${coverageLabel(k)}：${if(k.endsWith("count"))"$v 筆" else insightMoney(v)}",color=MobileWarning,style=MaterialTheme.typography.bodyMedium)}
                    if(r.coverage.getValueOrDefault("undated_receipt_count")>0)TextButton(onClick={actions.insightQuery(InsightQuery(undatedOnly=true))},modifier=Modifier.heightIn(min=48.dp)){Text("查看缺日期帳目")}
                }
            }}
            item{Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
                Text("比較支出",style=MaterialTheme.typography.titleLarge)
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    listOf("meals" to "餐別","merchants" to "店家","cities" to "縣市").forEach{(key,label)->
                        FilterChip(selected=dimension==key,onClick={dimension=key},label={Text(label)},modifier=Modifier.heightIn(min=48.dp).testTag("insight-quick-$key"))
                    }
                }
                InsightDimensionChooser(dimension){dimension=it}
            }}
            val nonDining=if(dimension=="meals")r.groups["meals"]?.firstOrNull{it.key=="not_applicable"}else null
            val groups=when(dimension){"purpose"->listOf(InsightGroup("self","自己用",r.summary.getValue("self_yuan"),0,0),InsightGroup("gift","送人",r.summary.getValue("gift_yuan"),0,0),InsightGroup("advance","代買（另列）",r.summary.getValue("advance_yuan"),0,0));"discount"->listOf(InsightGroup("discount","已明列折扣",r.summary.getValue("explicit_discount_yuan"),0,0));"series"->r.series;else->r.groups[dimension].orEmpty().filterNot{dimension=="meals" && it.key=="not_applicable"}.sortedByDescending{it.personal}}
            if(dimension=="discount")item{Text("符合帳目明列折扣包含代買；內含折扣不重扣。",style=MaterialTheme.typography.bodyMedium,color=MobileMuted)}
            if(groups.isEmpty())item{Text("這個範圍尚無可顯示資料。",color=MobileMuted)}
            if(dimension=="meals")item{Column{
                Text("只比較餐飲支出，餐別待補仍保留。",style=MaterialTheme.typography.bodySmall,color=MobileMuted)
                nonDining?.let{g->TextButton(onClick={insightDrillQuery(s.query,"meals",g)?.let(actions.insightQuery)},modifier=Modifier.heightIn(min=48.dp).testTag("insight-meals-non-dining")){
                    Text("非餐飲 ${insightMoney(g.personal)} 另列 ›")
                }}
            }}
            val low=minOf(0L,groups.minOfOrNull{it.personal}?:0L)
            val high=maxOf(0L,groups.maxOfOrNull{it.personal}?:0L)
            val visible=if(allGroups)groups else groups.take(5)
            items(visible.chunked(if(expanded)2 else 1),key={"$dimension-${it.first().key}"}){pair->
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(16.dp)){
                    pair.forEach{g->Box(Modifier.weight(1f)){LedgerCard{InsightRankingRow(g,dimension,low,high,s.query,actions.insightQuery,
                        shareTotal=if(s.query.category=="food" && dimension in setOf("merchants","brands","branches") && groups.none{it.personal<0})r.summary.getValue("personal_yuan")else null)}}}
                    // Keep the same physical bar scale when the final row has one group.
                    if(expanded && pair.size==1)Spacer(Modifier.weight(1f))
                }
            }
            if(groups.size>5)item{TextButton(onClick={allGroups=!allGroups},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){
                Text(if(allGroups)"收起，顯示前 5 項" else "查看全部 ${groups.size} 項")
            }}
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
