package com.momonong.pixelreceipt.mobile

import androidx.compose.foundation.clickable
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
                if (state.accountingStale) Text(state.accountingError ?: "帳本與統計尚未同步；顯示上次紀錄。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                else if (overview != null) Text("已作廢帳目不計入有效支出。", style = MaterialTheme.typography.bodySmall, color = MobileMuted)
            }
        }
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
                        Text(candidate.text("merchant"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        ReceiptActionMenu(null, entry, state, actions)
                    }
                    Text("${candidate.text("receipt_date")} · ${moneyText(snapshot.getAsJsonObject("calculation")["printed_total_yuan"])}", style = MaterialTheme.typography.bodyLarge)
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
    val document = snapshot["review_document"]?.takeIf { it.isJsonObject }?.asJsonObject
    LazyColumn(modifier.testTag("native-ledger-detail"), state = list, contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            LedgerCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { StatusTag(state.mutations.lastOrNull { it.targetId == entry.text("id") && it.phase == "pending" }?.let { "${it.resultLabel}結果待確認" } ?: if (entry.text("status") == "voided") "已作廢" else "已完成", problem = entry.text("status") == "voided") }
                    ReceiptActionMenu(null, entry, state, actions)
                }
                Text(candidate.text("merchant"), style = MaterialTheme.typography.headlineSmall)
                Text(candidate.text("receipt_date"), style = MaterialTheme.typography.bodyMedium, color = MobileMuted)
                Text(moneyText(calculation["printed_total_yuan"]), style = MaterialTheme.typography.headlineMedium, color = MobileTealInk)
                Text(if (entry.text("status") == "voided") "這筆已作廢，保留原入帳內容供查閱。" else "中央已確認入帳。", style = MaterialTheme.typography.bodyMedium)
                Text("個人支出 ${moneyText(calculation["personal_yuan"])} · 代買 ${moneyText(calculation["advance_yuan"])}", style = MaterialTheme.typography.bodyMedium, color = MobileMuted)
                Text("自己用 ${moneyText(calculation["self_yuan"])} · 送人 ${moneyText(calculation["gift_yuan"])}", style = MaterialTheme.typography.bodySmall, color = MobileMuted)
            }
        }
        state.message?.let { item { MobileNotice(it) } }
        if (state.accountingStale) item { MobileNotice(state.accountingError ?: "帳本或統計尚未同步；顯示上次紀錄。") }
        state.mutations.lastOrNull { it.targetId == entry.text("id") }?.let { mutation ->
            if (mutation.phase == "pending") item { MobileNotice("作廢結果尚未確認；請由更多操作查詢原作廢結果。原內容與請求已保存。") }
            else mutation.error?.let { message -> item { MobileNotice(message) } }
        }
        items(candidate.array("items").mapIndexed { index, value -> index to value.asJsonObject }, key = { it.first }) { (index, item) ->
            val decision = document?.array("line_decisions")?.firstOrNull { it.asJsonObject["item_position"].asInt == index }?.asJsonObject
            LedgerCard {
                Text("${index + 1}. ${item.text("raw_name")}", style = MaterialTheme.typography.titleSmall)
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
                if (references.size() == 0) Text("這筆沒有原圖索引。", style = MaterialTheme.typography.bodyMedium, color = MobileMuted)
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
    preview?.let { NativeOriginalDialog(it, images) { preview = null } }
}
@Composable private fun LedgerCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(color = Color.White, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content) }
}

@Composable
internal fun MobileHelp(modifier: Modifier, list: LazyListState) {
    val entries = listOf(
        "分享一筆交易" to "在相簿選取 1–20 張收據、價標或促銷照片，分享到 PixelReceipt。不同交易分批分享，也能在收件匣選照片。原圖會保存在此裝置。",
        "先補整批說明" to "例如：牙膏幫朋友買、第二張是價標。整批與逐圖說明會一起送交辨識；送出前可調整照片角色與順序。",
        "等待辨識" to "點送出後，中央會繼續處理，不必一直開著 App。回來更新進度即可；等待或失敗時只查詢原工作，不會再建立辨識。",
        "核對後一次入帳" to "辨識完成後，直接在 App 看店家、日期、實付、品項與折扣。點「修改」才展開欄位；編輯自動保存草稿、更新金額。最後按「確認入帳」代表你確認畫面內容、用途及收據完整。",
        "自己用、送人、代買與分攤" to "未指定的品項預設自己用，既有代買或分攤會保留。送人計入個人支出，代買獨立列出；分攤可按數量或指定金額，各用途沒有分配也要填 0。",
        "折扣、加收與差額" to "已含在品項行金額的折扣不會再扣一次。另列折扣可指定整張收據或受影響品項，再按比例或金額分配。所有金額由中央固定規則計算；有差額時先對照原圖。",
        "缺照片或缺品項" to "品項缺漏可直接新增品項。缺照片時先保留草稿，再將完整照片另分享一批；舊草稿保留，勿將新舊兩批重複確認入帳。已送出的原批次不能追加原圖。",
        "斷線與版本衝突" to "編輯、原圖與確認請求均會保存。入帳逾時後按「查詢入帳結果」，沿同一筆確認查核，勿另建重複交易。其他裝置有變更時先比較版本，再選擇採用內容。",
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
