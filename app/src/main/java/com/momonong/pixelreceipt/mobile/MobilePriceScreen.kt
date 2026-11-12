package com.momonong.pixelreceipt.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
internal fun MobilePriceScreen(state:PriceUiState,controller:MobilePriceController,onLedger:(String)->Unit,onBack:()->Unit) {
    var create by rememberSaveable{mutableStateOf(false)}
    var query by rememberSaveable{mutableStateOf(state.query)}
    var targetLine by remember(state.ledgerId){mutableStateOf<Int?>(null)}
    var confirm by remember{mutableStateOf<PriceProduct?>(null)}
    val review=state.review
    val form=remember(review?.ledgerId,review?.revision){review?.let{PriceReviewForm(it)}}
    var edits by remember(review?.ledgerId,review?.revision){mutableIntStateOf(0)}
    var formError by remember(review?.ledgerId,review?.revision){mutableStateOf<String?>(null)}
    val explicitInputs=remember(review?.ledgerId,review?.revision){mutableStateMapOf<String,String>()}
    val editable=!state.writing && !state.pending && !state.voided && review!=null
    LazyColumn(Modifier.fillMaxSize().testTag("price-list"),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        item { Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            TextButton(onClick=onBack,modifier=Modifier.heightIn(min=48.dp)){Text("返回")}
            Text(if(state.ledgerId==null)"商品價格" else "商品與價格核對",style=MaterialTheme.typography.headlineMedium)
            Text("相同商品、規格與購買單位，才能比較。由你逐列確認身份。",color=MobileMuted)
            Text("歷史價格，非目前售價；實付未知不參與最低價比較。",color=MobileMuted)
        } }
        if(state.loading || state.writing)item { Column(verticalArrangement=Arrangement.spacedBy(8.dp)){LinearProgressIndicator(Modifier.fillMaxWidth());Text(if(state.writing)"正在保存，請稍候…" else "正在讀取商品資料…")} }
        state.error?.let{error->item{PriceCard{Text(error,color=MaterialTheme.colorScheme.error);TextButton(onClick=controller::refresh,enabled=!state.writing,modifier=Modifier.heightIn(min=48.dp)){Text(if(state.pending)"查詢原操作結果" else "重新讀取")}}}}
        state.message?.let{message->item{PriceCard{Text(message,color=MobileTealInk)}}}
        if(state.pending)item{PriceCard {
            Text("操作結果待確認",style=MaterialTheme.typography.titleMedium)
            Text("原請求已保存。先查詢結果；重試會先查詢，只有未查到成功紀錄時才用同一份請求重送。")
            OutlinedButton(onClick=controller::retryPending,enabled=!state.writing && !state.loading,modifier=Modifier.heightIn(min=48.dp).testTag("price-retry-original")){Text("查詢並使用原請求重試")}
        }}
        if(state.ledgerId!=null){
            if(state.voided)item{Text("帳目已作廢，保留核對內容供查閱；不能修改，也不參與價格比較。",color=MobileWarning)}
            if(review==null && !state.loading)item{Text("尚未讀取價格核對內容。請重新讀取，確認目前版本。")}
            if(review!=null && form!=null){
                item{Column(verticalArrangement=Arrangement.spacedBy(6.dp)){Text("逐列對應商品",style=MaterialTheme.typography.titleLarge);Text("數量 1 必須對應商品的 1 個購買單位。品名相似不代表規格相同。");if(edits>0)Text("有尚未儲存的修改",color=MobileWarning)}}
                items(review.lines,key={"line-${it.position}"}){line->PriceCard {
                    Text("${line.position+1}. ${line.rawName?:"未填品名"}",style=MaterialTheme.typography.titleMedium)
                    PriceLineAmounts(line)
                    val mapped=form.mappings[line.position]
                    val product=(state.products+listOfNotNull(line.product,state.selectedProduct)).firstOrNull{it.id==mapped}
                    Text(if(mapped==null)"尚未確認商品身份" else "對應：${product?.identity?:mapped}")
                    OutlinedButton(onClick={targetLine=line.position;controller.showCatalog()},enabled=editable,modifier=Modifier.heightIn(min=48.dp).testTag("price-map-${line.position}")){Text("選擇相同商品與規格")}
                    if(mapped!=null)TextButton(onClick={form.mappings.remove(line.position);edits++},enabled=editable,modifier=Modifier.heightIn(min=48.dp)){Text("解除商品對應")}
                    if(targetLine==line.position)Text("請在下方商品目錄選擇，再確認身份。",color=MobileTealInk)
                }}
                item{Text("額外加減項分配",style=MaterialTheme.typography.titleLarge)}
                if(state.adjustments.isEmpty())item{Text("沒有明列加減項。")}
                items(state.adjustments,key={"adjust-${it.position}"}){a->PriceCard {
                    Text("${a.label} · ${if(a.direction=="subtract")"減" else "加"} NT$ ${a.amount}",style=MaterialTheme.typography.titleMedium)
                    Text("適用第 ${a.positions.joinToString("、"){(it+1).toString()}} 列")
                    if(a.included)Text("已包含在票面行金額，不再加減。",color=MobileMuted)
                    else if(a.positions.size==1)Text("只適用單列，由中央直接分配。",color=MobileMuted)
                    else {
                        val decision=form.decisions[a.position];val method=decision?.text("method")
                        Text(when(method){"proportional_line_amount"->"已選按票面行金額比例分配";"explicit_amounts"->"已選逐列指定金額";else->"尚未分配；受影響列實付保持未知"},color=if(method==null)MobileWarning else MobileTealInk)
                        OutlinedButton(onClick={form.proportional(a.position);edits++},enabled=editable,modifier=Modifier.heightIn(min=48.dp)){Text("按票面行金額比例分配")}
                        OutlinedButton(onClick={
                            a.positions.forEach{p->val key="${a.position}:$p";if(!explicitInputs.containsKey(key)){explicitInputs[key]=decision?.get("amounts_by_item")?.takeIf{it.isJsonArray}?.asJsonArray?.firstOrNull{it.asJsonObject["item_position"].asInt==p}?.asJsonObject?.get("amount_yuan")?.asString?:""}}
                            form.explicit(a.position,a.positions.associateWith{"0"});edits++
                        },enabled=editable,modifier=Modifier.heightIn(min=48.dp)){Text("逐列指定金額")}
                        if(method=="explicit_amounts")a.positions.forEach{p->
                            val key="${a.position}:$p"
                            val initial=decision.get("amounts_by_item")?.takeIf{it.isJsonArray}?.asJsonArray?.firstOrNull{it.asJsonObject["item_position"].asInt==p}?.asJsonObject?.get("amount_yuan")?.asString.orEmpty()
                            OutlinedTextField(value=explicitInputs[key]?:initial,onValueChange={explicitInputs[key]=it;edits++},label={Text("第 ${p+1} 列分配金額（整數元）")},enabled=editable,singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.fillMaxWidth())
                        }
                        if(method!=null)TextButton(onClick={form.decisions.remove(a.position);edits++},enabled=editable,modifier=Modifier.heightIn(min=48.dp)){Text("改為尚未分配")}
                    }
                }}
                item{Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
                    formError?.let{Text(it,color=MaterialTheme.colorScheme.error)}
                    Text("分配總和須與每個加減項相同，不得超出適用列或讓實付為負。中央以整數重新核對；不改變帳本金額。",color=MobileMuted)
                    Button(onClick={
                        try{
                            form.decisions.values.filter{it.text("method")=="explicit_amounts"}.map{it["adjustment_position"].asInt}.forEach{position->
                                val a=state.adjustments.first{it.position==position};val old=form.decisions[position]!!
                                form.explicit(position,a.positions.associateWith{p->explicitInputs["$position:$p"]?:old.array("amounts_by_item").first{it.asJsonObject["item_position"].asInt==p}.asJsonObject["amount_yuan"].asString})
                            }
                            formError=null;controller.saveReview(review.ledgerId,form.body())
                        }catch(_:Exception){formError="逐列金額請填 0 或正整數元，並確認每一適用列都有金額。"}
                    },enabled=editable,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("price-save-review")){Text("儲存商品與價格核對")}
                }}
            }
        }
        if(state.selectedProduct!=null){
            item{PriceCard {
                Text(state.selectedProduct.name,style=MaterialTheme.typography.titleLarge);Text(state.selectedProduct.identity)
                TextButton(onClick=controller::showCatalog,enabled=!state.writing,modifier=Modifier.heightIn(min=48.dp)){Text("返回商品目錄")}
            }}
            val page=state.prices
            if(page==null && !state.loading)item{OutlinedButton(onClick=controller::refresh,modifier=Modifier.heightIn(min=48.dp)){Text("讀取歷史價格")}}
            if(page!=null){
                item{PriceCard {
                    Text("歷史已知實付最低單價",style=MaterialTheme.typography.titleMedium)
                    val lowest=page.lowest
                    if(lowest==null)Text("沒有已核對且實付已知的價格。")
                    else {Text("NT$ ${lowest.line.paidUnit!!.display()} / ${page.product.purchaseUnit}",style=MaterialTheme.typography.headlineSmall,color=MobileTealInk);Text("${lowest.date?:"日期未填"} · ${lowest.merchant?:"商店未填"}")}
                    Text("僅代表已記錄的歷史觀測，非目前售價。",color=MobileMuted)
                }}
                if(page.total==0)item{Text("此商品沒有正式、未作廢帳目的觀測紀錄。")}
                items(page.observations,key={"${it.ledgerId}-${it.line.position}"}){observation->PriceCard {
                    Text("${observation.date?:"日期未填"} · ${observation.merchant?:"商店未填"}",style=MaterialTheme.typography.titleMedium)
                    Text(observation.line.rawName?:"原始品名未填");PriceLineAmounts(observation.line)
                    if(observation.line.allocationMethods.isNotEmpty())Text("分配：${observation.line.allocationMethods.distinct().joinToString("、"){priceMethod(it)}}",color=MobileMuted)
                    TextButton(onClick={onLedger(observation.ledgerId)},modifier=Modifier.heightIn(min=48.dp).testTag("price-source-${observation.ledgerId}-${observation.line.position}")){Text("查看來源帳目")}
                }}
                if(page.next!=null)item{OutlinedButton(onClick=controller::loadMore,enabled=!state.loading,modifier=Modifier.heightIn(min=48.dp)){Text("載入更多價格紀錄")}}
                item{Text("已顯示 ${page.observations.size} / ${page.total} 筆；最低價由中央依完整資料比較。",color=MobileMuted)}
            }
        }else{
            item{Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text(if(targetLine!=null)"為第 ${targetLine!!+1} 列選擇商品" else "商品目錄",style=MaterialTheme.typography.titleLarge)
                OutlinedTextField(value=query,onValueChange={query=it.take(500)},label={Text("搜尋名稱、品牌或規格")},singleLine=true,modifier=Modifier.fillMaxWidth())
                OutlinedButton(onClick={controller.search(query)},enabled=!state.loading && !state.pending && !state.writing,modifier=Modifier.heightIn(min=48.dp)){Text("搜尋商品")}
                Button(onClick={create=!create},enabled=!state.pending && !state.writing && !state.voided,modifier=Modifier.heightIn(min=48.dp).testTag("price-new-product")){Text(if(create)"收起新商品" else "建立新商品")}
            }}
            if(create)item{PriceCreateForm(state.writing || state.pending){name,brand,spec,unit->controller.createProduct(name,brand,spec,unit)}}
            if(state.products.isEmpty() && !state.loading && state.error==null)item{Text("沒有符合的商品。可建立具有確切規格與購買單位的新商品。")}
            items(state.products,key={it.id}){p->PriceCard {
                Text(p.name,style=MaterialTheme.typography.titleMedium);Text(p.identity)
                OutlinedButton(onClick={if(targetLine!=null && form!=null)confirm=p else controller.selectProduct(p.id)},enabled=!state.pending && !state.writing,modifier=Modifier.heightIn(min=48.dp).testTag("price-product-${p.id}")){Text(if(targetLine!=null)"選擇並確認規格" else "比較歷史價格")}
            }}
            if(state.catalogNext!=null)item{OutlinedButton(onClick=controller::loadMore,enabled=!state.loading && !state.pending,modifier=Modifier.heightIn(min=48.dp)){Text("載入更多商品")}}
        }
    }
    confirm?.let{product->AlertDialog(onDismissRequest={confirm=null},title={Text("確認相同商品與單位")},text={Text("第 ${(targetLine?:0)+1} 列：${review?.lines?.getOrNull(targetLine?:-1)?.rawName?:"未填品名"}\n\n${product.identity}\n\n我確認這列數量 1 對應商品的 1 個購買單位，品項、品牌與規格一致。")},confirmButton={TextButton(onClick={targetLine?.let{form?.mappings?.set(it,product.id);edits++};targetLine=null;confirm=null},enabled=editable,modifier=Modifier.heightIn(min=48.dp)){Text("確認對應")}},dismissButton={TextButton(onClick={confirm=null},modifier=Modifier.heightIn(min=48.dp)){Text("取消")}})}
}
@Composable private fun PriceCard(content:@Composable ColumnScope.()->Unit){Card(Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface)){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp),content=content)}}
@Composable private fun PriceLineAmounts(line:PriceLine){
    Text("數量 ${line.quantity} · 票面行合計 NT$ ${line.printedTotal}")
    Text("票面單價 NT$ ${line.printedUnit.display()}")
    Text(if(line.paidUnit==null)"實付單價未知，不參與實付最低價比較" else "實付單價 NT$ ${line.paidUnit.display()}",color=if(line.paidUnit==null)MobileWarning else MobileTealInk)
    if(!line.eligible && line.paidUnit!=null)Text(if(line.reasons.contains("ledger_voided"))"作廢帳目不參與價格比較。" else "商品身份尚未確認，不參與價格比較。",color=MobileWarning)
    if(line.reasons.contains("shared_adjustment_unallocated"))Text("有共用加減項尚未分配。",color=MobileMuted)
}
private fun priceMethod(value:String)=when(value){"proportional_line_amount"->"票面行金額比例";"explicit_amounts"->"逐列指定";"single_line_scope"->"單列直接分配";else->"待確認"}
@Composable private fun PriceCreateForm(locked:Boolean,onSave:(String,String?,String,String)->Unit){
    var name by rememberSaveable{mutableStateOf("")};var brand by rememberSaveable{mutableStateOf("")};var spec by rememberSaveable{mutableStateOf("")};var unit by rememberSaveable{mutableStateOf("")}
    PriceCard {
        Text("每一規格建立獨立商品",style=MaterialTheme.typography.titleMedium)
        OutlinedTextField(name,{name=it.take(500)},label={Text("商品名稱（必填）")},enabled=!locked,modifier=Modifier.fillMaxWidth())
        OutlinedTextField(brand,{brand=it.take(500)},label={Text("品牌（可留空）")},enabled=!locked,modifier=Modifier.fillMaxWidth())
        OutlinedTextField(spec,{spec=it.take(500)},label={Text("確切規格，例如無糖 500mL（必填）")},enabled=!locked,modifier=Modifier.fillMaxWidth())
        OutlinedTextField(unit,{unit=it.take(500)},label={Text("一個購買單位，例如瓶／6入包（必填）")},enabled=!locked,modifier=Modifier.fillMaxWidth())
        Button(onClick={onSave(name,brand.takeIf{it.isNotBlank()},spec,unit)},enabled=!locked && name.isNotBlank() && spec.isNotBlank() && unit.isNotBlank(),modifier=Modifier.heightIn(min=48.dp).testTag("price-create-confirm")){Text("確認建立商品")}
    }
}
