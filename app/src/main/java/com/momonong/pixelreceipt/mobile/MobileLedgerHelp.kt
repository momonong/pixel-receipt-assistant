package com.momonong.pixelreceipt.mobile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.momonong.pixelreceipt.data.ingestion.ImageStore

@Composable
internal fun MobileLedger(state: MobileUiState, actions: MobileActions, modifier: Modifier, list: LazyListState) {
    val response = state.ledgerJson?.let(::jsonObject)
    val allEntries = response?.array("entries")?.map { it.asJsonObject }.orEmpty()
    var showVoided by rememberSaveable { mutableStateOf(false) }
    val entries = allEntries.filter { showVoided || it.text("status") != "voided" }
    val overview = state.overviewJson?.let(::overviewObject)
    LazyColumn(modifier.testTag("native-ledger"), state = list, contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("帳本", style = MaterialTheme.typography.headlineMedium)
                Text(if (response == null) "已確認的消費會顯示在這裡。" else "歷史紀錄已載入 ${allEntries.size} / ${response["total_count"].asInt} 筆（含作廢）", style = MaterialTheme.typography.bodyMedium, color = MobileMuted)
                state.ledgerUpdatedAt?.let { Text("上次已確認的中央紀錄；可更新查看最新狀態。", style = MaterialTheme.typography.bodySmall, color = MobileMuted) }
            }
        }
        item {
            LedgerCard {
                Text("有效帳目 · 中央統計", style = MaterialTheme.typography.titleSmall)
                if (overview == null) Text("尚未取得中央統計，請更新帳本。", color = MobileMuted)
                else {
                    Text("個人支出 ${moneyText(overview["personal_yuan"])}", style = MaterialTheme.typography.headlineSmall, color = MobileTealInk)
                    Text("${overview["receipt_count"].asLong} 筆有效帳目 · 實付 ${moneyText(overview["printed_total_yuan"])}", style = MaterialTheme.typography.bodyMedium, color = MobileMuted)
                }
                val missing = overview?.get("missing_item_name_count")?.takeUnless { it.isJsonNull }?.asInt
                if (missing != null && missing > 0) {
                    val receipts = overview?.get("receipts_missing_item_names")?.takeUnless { it.isJsonNull }?.asInt
                    Text("${receipts?.let { "$it 筆帳目 · " }.orEmpty()}$missing 個品項待補品名", style = MaterialTheme.typography.bodyMedium, color = MobileWarning)
                    Text("金額已入帳，可進入帳目補品名。", style = MaterialTheme.typography.bodySmall, color = MobileMuted)
                } else if (missing == null) {
                    val loadedMissing = allEntries.filter { it.text("status") != "voided" }.sumOf { ledgerItemNames(it).count { name -> name == null } }
                    if (loadedMissing > 0) Text("已載入帳目有 $loadedMissing 個品項待補品名", style = MaterialTheme.typography.bodyMedium, color = MobileWarning)
                }
                if (state.accountingStale) Text(state.accountingError ?: "帳本與統計尚未同步；顯示上次紀錄。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                else if (overview != null) Text("已作廢帳目不計入有效支出。", style = MaterialTheme.typography.bodySmall, color = MobileMuted)
            }
        }
        item { OutlinedButton(onClick = actions.manual, enabled = state.signedIn && state.reviewer, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("ledger-manual")) { Text("手動記帳・沒有收據") } }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(checked = showVoided, onCheckedChange = { showVoided = it }, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).testTag("ledger-show-voided"))
                Text("查看已作廢", style = MaterialTheme.typography.bodyLarge)
            }
        }
        state.message?.let { item { MobileNotice(it) } }
        if (state.busy != null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (entries.isEmpty() && state.busy == null) item { Text(if (state.signedIn) "目前沒有已確認帳目。" else "請登入後讀取中央帳本。", style = MaterialTheme.typography.bodyLarge) }
        items(entries, key = { it.text("id") }) { entry ->
            val snapshot = entry.getAsJsonObject("snapshot"); val candidate = snapshot.getAsJsonObject("candidate")
            Surface(color = Color.White, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().clickable { actions.ledgerDetail(entry.text("id")) }.testTag("ledger-${entry.text("id")}")) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusTag(state.mutations.lastOrNull { it.targetId == entry.text("id") && it.phase == "pending" }?.let { "${it.resultLabel}結果待確認" } ?: if (entry.text("status") == "voided") "已作廢" else "已完成", problem = entry.text("status") == "voided")
                    Row(Modifier.fillMaxWidth()) {
                        Text(ledgerMerchant(entry), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        ReceiptActionMenu(null, entry, state, actions)
                    }
                    Text("${candidate.text("receipt_date")} · ${moneyText(snapshot.getAsJsonObject("calculation")["printed_total_yuan"])}", style = MaterialTheme.typography.bodyLarge)
                    val missingNames = ledgerItemNames(entry).count { it == null }
                    if (entry.text("status") != "voided" && missingNames > 0) StatusTag("$missingNames 個品項待補品名", problem = true)
                    Text("查看品項與用途 ›", style = MaterialTheme.typography.labelLarge, color = MobileTealInk)
                }
            }
        }
        if (response != null && allEntries.size < response["total_count"].asInt) item {
            OutlinedButton(onClick = { actions.refreshLedger(true) }, enabled = state.busy == null && state.signedIn, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("載入更多帳目") }
        }
    }
}

@Composable
internal fun MobileLedgerDetail(state: MobileUiState, images: ImageStore, actions: MobileActions, modifier: Modifier, list: LazyListState) {
    val entry = state.ledgerDetailsJson?.let(::jsonObject)?.get(state.selectedLedgerId)?.takeIf { it.isJsonObject }?.asJsonObject
        ?: state.ledgerJson?.let(::jsonObject)?.array("entries")?.firstOrNull { it.asJsonObject.text("id") == state.selectedLedgerId }?.asJsonObject
    var namesOpen by rememberSaveable(state.selectedLedgerId) { mutableStateOf(false) }
    var preview by rememberSaveable(state.selectedLedgerId) { mutableStateOf<String?>(null) }
    if (entry == null) {
        Column(modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("正在讀取帳目", style = MaterialTheme.typography.headlineSmall)
            state.message?.let { Text(it, color = MobileMuted) }
            if (state.busy != null) LinearProgressIndicator(Modifier.fillMaxWidth())
            else TextButton(onClick = { state.selectedLedgerId?.let(actions.ledgerDetail) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("重新讀取") }
        }
        return
    }
    val snapshot = entry.getAsJsonObject("snapshot"); val candidate = snapshot.getAsJsonObject("candidate"); val calculation = snapshot.getAsJsonObject("calculation")
    val names = ledgerItemNames(entry)
    val namesPending = state.mutations.lastOrNull { it.targetId == entry.text("id") && it.phase == "pending" }
    val namesEditable = entry.text("status") != "voided" && namesPending == null && state.signedIn && state.reviewer && state.writable && state.busy == null
    val document = snapshot["review_document"]?.takeIf { it.isJsonObject }?.asJsonObject
    LazyColumn(modifier.testTag("native-ledger-detail"), state = list, contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            LedgerCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { StatusTag(state.mutations.lastOrNull { it.targetId == entry.text("id") && it.phase == "pending" }?.let { "${it.resultLabel}結果待確認" } ?: if (entry.text("status") == "voided") "已作廢" else "已完成", problem = entry.text("status") == "voided") }
                    ReceiptActionMenu(null, entry, state, actions)
                }
                Text(ledgerMerchant(entry), style = MaterialTheme.typography.headlineSmall)
                Text(candidate.text("receipt_date"), style = MaterialTheme.typography.bodyMedium, color = MobileMuted)
                Text(moneyText(calculation["printed_total_yuan"]), style = MaterialTheme.typography.headlineMedium, color = MobileTealInk)
                Text(if (entry.text("status") == "voided") "這筆已作廢，保留原入帳內容供查閱。" else "中央已確認入帳。", style = MaterialTheme.typography.bodyMedium)
                Text("個人支出 ${moneyText(calculation["personal_yuan"])} · 代買 ${moneyText(calculation["advance_yuan"])}", style = MaterialTheme.typography.bodyMedium, color = MobileMuted)
                Text("自己用 ${moneyText(calculation["self_yuan"])} · 送人 ${moneyText(calculation["gift_yuan"])}", style = MaterialTheme.typography.bodySmall, color = MobileMuted)
                if (namesPending?.kind == "ledger_names") {
                    OutlinedButton(onClick = { actions.retryReceiptMutation(namesPending) }, enabled = state.busy == null && state.writable && state.reviewer && state.signedIn, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("ledger-item-names-query")) { Text("查詢補品名結果") }
                } else if (entry.text("status") != "voided") {
                    OutlinedButton(onClick = { namesOpen = true }, enabled = namesEditable, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("ledger-item-names-edit")) { Text(if (names.any { it == null }) "補品名" else "修改品名") }
                    if (names.any { it == null }) Text("${names.count { it == null }} 個品項待補品名；金額與用途已入帳。", style = MaterialTheme.typography.bodySmall, color = MobileWarning)
                }
            }
        }
        item { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { actions.editInsight(entry.text("id"), true, entry.text("status") == "voided") }, modifier = Modifier.heightIn(min = 48.dp).testTag("ledger-insight-edit")) { Text("分析標籤") }
            OutlinedButton(onClick = { actions.prices(entry.text("id")) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("商品價格核對") }
        } }
        state.message?.let { item { MobileNotice(it) } }
        if (state.accountingStale) item { MobileNotice(state.accountingError ?: "帳本或統計尚未同步；顯示上次紀錄。") }
        state.mutations.lastOrNull { it.targetId == entry.text("id") }?.let { mutation ->
            if (mutation.phase == "pending") item { MobileNotice("${mutation.resultLabel}結果尚未確認；請查詢原${mutation.resultLabel}結果。原內容與請求已保存。") }
            else mutation.error?.let { message -> item { MobileNotice(message) } }
        }
        items(candidate.array("items").mapIndexed { index, value -> index to value.asJsonObject }, key = { it.first }) { (index, item) ->
            val decision = document?.array("line_decisions")?.firstOrNull { it.asJsonObject["item_position"].asInt == index }?.asJsonObject
            LedgerCard {
                Text("${index + 1}. ${names[index] ?: "待補品名"}", style = MaterialTheme.typography.titleSmall)
                Text("${item.text("quantity")} 件 · ${moneyText(item["printed_line_total_yuan"])} · ${decision?.let(::purposeSummary) ?: "用途未記錄"}", style = MaterialTheme.typography.bodyLarge)
                if (decision?.text("method") != "whole_line") {
                    val shares = calculation.array("line_shares").firstOrNull { it.asJsonObject["item_position"].asInt == index }?.asJsonObject
                    purposeLabels.forEach { (purpose, label) ->
                        val quantity = decision?.get("quantities")?.takeIf { it.isJsonObject }?.asJsonObject?.text(purpose).orEmpty()
                        Text("$label ${quantity.ifBlank { "?" }} 件 · ${moneyText(shares?.get("${purpose}_yuan"))}", style = MaterialTheme.typography.bodyMedium, color = MobileMuted)
                    }
                }
                item.text("specification").takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MobileMuted) }
            }
        }
        items(candidate.array("adjustments").mapIndexed { index, value -> index to value.asJsonObject }) { (index, adjustment) ->
            LedgerCard {
                Text(adjustment.text("raw_label").ifBlank { "折扣／加收 ${index + 1}" }, style = MaterialTheme.typography.titleSmall)
                Text("${if (adjustment.text("direction") == "add") "加收" else "折扣"} ${moneyText(adjustment["amount_yuan"])}", style = MaterialTheme.typography.bodyMedium)
                Text(if (adjustment.text("amount_effect") == "included_in_line_totals") "已含行金額，未重複扣除。" else "另列金額已由中央依核對用途分配。", style = MaterialTheme.typography.bodySmall, color = MobileMuted)
            }
        }
        item {
            LedgerCard {
                Text("原圖", style = MaterialTheme.typography.titleMedium)
                val references = snapshot.array("evidence")
                if (references.size() == 0) Text(if (snapshot.getAsJsonObject("source")?.text("kind") == "manual") "手動記帳來源，未提供收據照片。" else "這筆沒有原圖索引。", style = MaterialTheme.typography.bodyMedium, color = MobileMuted)
                references.forEachIndexed { index, value ->
                    val hash = value.asJsonObject.text("sha256")
                    if (hash.matches(Regex("[0-9a-f]{64}"))) {
                        TextButton(onClick = { preview = hash }, modifier = Modifier.heightIn(min = 48.dp)) { Text("原圖 ${index + 1} · ${if (images.file(hash).isFile) "放大對照" else "此裝置未保存"}") }
                    }
                }
            }
        }
        if (entry.text("status") == "voided") item {
            entry.array("events").lastOrNull()?.asJsonObject?.text("reason")?.takeIf { it.isNotBlank() }?.let { Text("作廢原因：$it", style = MaterialTheme.typography.bodyMedium, color = MobileMuted) }
        }
    }
    if (namesOpen) LedgerItemNamesDialog(entry, namesEditable, { namesOpen = false }) { updated ->
        namesOpen = false
        actions.updateItemNames(entry.text("id"), updated)
    }
    preview?.let { NativeOriginalDialog(it, images) { preview = null } }
}
@Composable
private fun LedgerItemNamesDialog(entry: com.google.gson.JsonObject, enabled: Boolean, dismiss: () -> Unit, save: (Map<Int, String?>) -> Unit) {
    val originals = ledgerItemNames(entry)
    var values by rememberSaveable(entry.text("id"), entry.text("revision")) { mutableStateOf(originals.map { it.orEmpty() }) }
    val tooLong = values.any { it.trim().length > 500 }
    AlertDialog(onDismissRequest = dismiss, title = { Text(if (originals.any { it == null }) "補品名" else "修改品名") }, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).testTag("ledger-item-names-dialog"),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("只更新顯示品名，原入帳金額與用途保留。可留空，稍後再補。", style = MaterialTheme.typography.bodyMedium)
                values.forEachIndexed { index, value ->
                    OutlinedTextField(value = value, onValueChange = { next -> values = values.toMutableList().apply { set(index, next) } },
                        label = { Text("品項 ${index + 1} 品名（可留空）") }, placeholder = { Text("稍後再補也可以") }, singleLine = true, enabled = enabled,
                        isError = value.trim().length > 500, supportingText = if (value.trim().length > 500) ({ Text("品名最多 500 個字。") }) else null,
                        modifier = Modifier.fillMaxWidth().testTag("ledger-item-name-$index"))
                }
                if (!enabled) Text("中央狀態已更新或目前無法保存，請返回檢查。", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = dismiss, modifier = Modifier.heightIn(min = 48.dp).testTag("ledger-item-names-cancel")) { Text("取消") } },
        confirmButton = { TextButton(onClick = { save(values.mapIndexed { index, value -> index to value.trim().takeIf { it.isNotBlank() } }.toMap()) }, enabled = enabled && !tooLong && values.isNotEmpty(), modifier = Modifier.heightIn(min = 48.dp).testTag("ledger-item-names-save")) { Text("保存品名") } })
}

@Composable internal fun LedgerCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(color = Color.White, shape = RoundedCornerShape(18.dp), modifier = modifier.fillMaxWidth()) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content) }
}

@Composable
internal fun MobileHelp(modifier: Modifier, list: LazyListState) {
    val entries = listOf(
        "分享一筆交易" to "在相簿選取 1–20 張收據、價標或促銷照片，分享到 pixelreceipt。不同交易分批分享，也能在收件匣選照片。原圖會保存在此裝置。",
        "先補整批說明" to "例如：牙膏幫朋友買、第二張是價標。整批與逐圖說明會一起送交辨識；送出前可調整照片角色與順序。",
        "收據篩選" to "收據頁預設一般收據，顯示正常新收件、待核對與已完成。刪除、作廢、缺照片或等待過久的收據仍保留，可從篩選選全部或單一狀態；需要處理可直接點數量。每種狀態有獨立顏色與文字。刪除或作廢結果尚未確認時不會因隱藏而假裝完成。",
        "看懂支出分析" to "點底部分析，先看本期個人支出；本月、上月、今年、全部或自訂日曆可切換範圍。金額怎麼算可看自己用、送人、代買、實付及明列折扣。送人計入個人支出，代買另列，折扣不再扣一次。統計涵蓋整個期間的有效已入帳帳目，沒有包含草稿或作廢。",
        "從圖表找到帳目" to "點趨勢圖、前一筆／下一筆或店家，再往下看對應帳目；點帳目可開原生明細，返回保留目前選擇。顯示本期全部帳目可清除篩選。未填日期不計入日期期間，在全部可另行查看；離線顯示上次保存的分析與提示，登入更新後確認最新狀態。一般手機直向，折疊展開可並排查看。",
        "生活分類與餐別" to "分析可交叉選生活分類、餐別、品牌分店、縣市地區、通路與平日週末。缺資料仍保留支出；進核對或帳目點分析標籤可事後補。餐別不由上傳時間猜測，地區不由店名猜測。同日同餐別且同餐代號可合併為一餐。",
        "沒有收據也能記帳" to "帳本點手動記帳，填整數金額，日期預設今天、歸屬預設自己用。商家品名與分類可留空，最後確認金額與歸屬再按確認記帳。未知結果請查原操作；不要另建相同支出。手動來源不會假裝收據照片。",
        "商品歷史價格" to "分析可查看商品價格，帳目可人工核對商品與規格、單位及折扣分配。同名不同規格不自動合併，未確認實付不參與最低價排名；歷史最低價不是目前售價。",
        "等待辨識" to "點送出後，中央會繼續處理，不必一直開著 App。回來更新進度即可；等待或失敗時只查詢原工作，不會再建立辨識。",
        "核對後一次入帳" to "辨識完成後，直接在 App 看店家、日期、實付、品項與折扣。點「修改」才展開欄位；編輯自動保存草稿、更新金額。最後按「確認入帳」代表你確認畫面內容、用途及收據完整。",
        "敘述與品項一起核對" to "辨識完品項會再對照整批與逐圖說明，帶入自己用、送人或代買的建議。若與已保存用途不同，可選套用建議或保留目前用途；無法對應時會提醒你核對，不會當作已成功分類。送人計入個人支出，代買另列。分攤可按數量或指定金額，沒有分配的用途填 0。",
        "日期直接點選" to "新收據沒有辨識到日期時，先填今天並保留原始資料。點日期可開日曆，也能選今天或昨天；已有日期不會被今天覆蓋。",
        "折扣、加收與差額" to "已含在品項行金額的折扣不會再扣一次。另列折扣可指定整張收據或受影響品項，再按比例或金額分配。所有金額由中央固定規則計算；有差額時先對照原圖。",
        "缺照片或缺品項" to "品項缺漏可直接新增品項。缺照片時先保留草稿，再將完整照片另分享一批；舊草稿保留，勿將新舊兩批重複確認入帳。已送出的原批次不能追加原圖。",
        "斷線與版本衝突" to "編輯、原圖與確認請求均會保存。入帳逾時後按「查詢入帳結果」，沿同一筆確認查核，勿另建重複交易。其他裝置有變更時先比較版本，再選擇採用內容。",
        "品名可稍後再補" to "金額與用途確認後，品名可留空入帳。帳本會標示待補品名；進入帳目點「補品名」或「修改品名」即可保存顯示名稱，原入帳金額、用途與收據內容保留。補品名斷線時先查詢原結果，未確認前不能作廢；已作廢帳目不再修改品名。",
        "查看已完成帳目" to "中央確認後，詳情、收件匣與帳本會顯示已完成。已作廢另有標記。離線只顯示上次已確認紀錄；更新才能取得最新狀態。歷史或其他裝置帳目可能沒有本機原圖，此版本會明確標示。",
        "刪除未入帳收據" to "從收件匣或詳情的更多操作選刪除收據，再確認店家與金額。這是邏輯刪除：從收件匣移除、不影響帳本支出，原圖與備份保留，沒有復原按鈕。匯入、保存或入帳未確認時，先處理原結果；中央 ID 未明的鎖定收件須先更新原進度。",
        "作廢已入帳帳目" to "已完成帳目使用作廢帳目，原因預設未註明。作廢後不再計入有效支出；原帳目與原因保留，帳本勾選查看已作廢即可讀取歷史。操作後留在原分頁。",
        "刪除／作廢斷線" to "未知結果會保留原目標、原因與請求。重啟後選查詢結果，先讀原 ID；需要重試時沿用相同請求。中央版本衝突會保留資料並更新狀態，重新檢查後才能另行確認。統計尚未同步時會明確標示，請更新帳本。",
        "可能重複的分享" to "首次送出前，若這台裝置已有相同整組收據原圖，會讓你選擇查看舊收件或明確另送。取消不送出，新照片與所有說明保留。只比對這台裝置中、目前標為收據的整組相同照片。重新拍攝、裁切、壓縮、增減頁面或更改照片角色，都可能無法提示；其他裝置的收件也不在比對範圍。同圖可以屬於不同交易，不會自動合併。送出後若斷線，繼續同步會接續原收件。",
        "登入與原圖保留" to "舊裝置登入可能只有收件權限，在設定重新登入一次即可開啟原生核對。密碼不會保存。登出保留草稿與原圖；請勿清除 App 資料。中央帳本是正式紀錄；本機保留原圖與待同步草稿。",
    )
    LazyColumn(modifier.testTag("native-help"), state = list, contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("使用說明", style = MaterialTheme.typography.headlineMedium) }
        item { Text("分享 → 辨識 → App 內核對 → 確認入帳", style = MaterialTheme.typography.bodyLarge, color = MobileTealInk) }
        items(entries) { (title, body) -> LedgerCard { Text(title, style = MaterialTheme.typography.titleMedium); Text(body, style = MaterialTheme.typography.bodyLarge, color = MobileMuted) } }
    }
}

internal fun ledgerMerchant(entry:com.google.gson.JsonObject):String {
    val snapshot=entry.getAsJsonObject("snapshot");val merchant=snapshot.getAsJsonObject("candidate").text("merchant")
    return if(snapshot.getAsJsonObject("source")?.text("kind")=="manual" && merchant=="手動記帳")"未填商家・手動記帳"else merchant.ifBlank{"未填商家"}
}
