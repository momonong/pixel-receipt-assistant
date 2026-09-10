package com.momonong.pixelreceipt.feature.inbox

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.momonong.pixelreceipt.data.ingestion.ImageStore
import com.momonong.pixelreceipt.domain.model.*
import com.momonong.pixelreceipt.domain.rules.ReceiptReconciler
import com.momonong.pixelreceipt.domain.rules.ReceiptReconciliationResult
import com.momonong.pixelreceipt.domain.usecase.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReviewScreen(session: ReviewSession, state: ReviewState, assets: List<EvidenceAsset>, images: ImageStore,
    expanded: Boolean, preview: (String) -> Unit, notice: String? = null,
    appendPhotos: () -> Unit = {}, importStatus: String? = null, progress: Pair<Int, Int>? = null,
    cancelImport: () -> Unit = {}, extraction: @Composable () -> Unit = {}) {
    val base = state.base ?: return
    val input = state.input
    val initial = remember(base) { ReviewInput.from(base) }
    var leave by remember { mutableStateOf(false) }
    var append by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var deleteLine by rememberSaveable(base.id) { mutableStateOf<String?>(null) }
    var openLine by rememberSaveable(base.id) { mutableStateOf<String?>(null) }
    var photoId by rememberSaveable(base.id) { mutableStateOf<String?>(null) }
    var showPhoto by rememberSaveable(base.id) { mutableStateOf(true) }
    var showAdjustments by rememberSaveable(base.id) { mutableStateOf(input.adjustments.isNotEmpty()) }
    val photo = assets.find { it.id == photoId } ?: assets.firstOrNull()
    val evaluation = remember(base, input) { session.evaluation() }
    val result = evaluation.draft?.let { ReceiptReconciler().reconcile(it) }
    val canConfirm = result is ReceiptReconciliationResult.Balanced || result is ReceiptReconciliationResult.WithinTolerance
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val shortWindow = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() < 500.dp }
    val writableStage = base.stage == ReceiptStage.NeedsReview
    val back = { if (state.dirty) leave = true else session.close() }
    fun checkDetails() {
        focus.clearFocus()
        scope.launch { list.animateScrollToItem(input.lines.size + 3) }
    }
    fun addLine() {
        val line = ReviewLineInput()
        session.edit(input.copy(lines = input.lines + line))
        openLine = line.id
        scope.launch { list.animateScrollToItem(input.lines.size + 2) }
    }
    BackHandler { if (!state.busy) back() }
    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = back, enabled = !state.busy) { Text("返回") }
            Text(if (writableStage) "填寫這筆消費" else "消費明細", style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f))
        }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        progress?.let { (done, total) ->
            Text("正在保存照片 $done / $total 張…")
            TextButton(onClick = cancelImport) { Text("取消本批匯入") }
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            if (expanded) Column(Modifier.weight(1f).fillMaxHeight()) {
                Text("這筆消費的照片 · ${assets.size} 張", style = MaterialTheme.typography.titleMedium)
                Text("附件供整筆消費核對；切換照片不會指定品項來源。", style = MaterialTheme.typography.bodySmall)
                PhotoChoices(assets, photo?.id) { photoId = it }
                photo?.let {
                    EvidencePhoto(it.contentSha256, images, Modifier.weight(1f).fillMaxWidth(), zoomable = true)
                    TextButton(onClick = { preview(it.contentSha256) }) { Text("全螢幕看照片") }
                } ?: Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { Text(if (writableStage) "可以先填寫，稍後再補照片。" else "這筆消費沒有照片附件。") }
                if (writableStage) OutlinedButton(onClick = { if (state.dirty) append = true else appendPhotos() },
                    enabled = state.editable, modifier = Modifier.fillMaxWidth()) { Text("補照片到這筆消費") }
            }
            Column(Modifier.weight(if (expanded) 1.2f else 1f).fillMaxHeight()) {
                if (!expanded && !keyboardOpen) {
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { showPhoto = !showPhoto }) { Text(if (showPhoto && !keyboardOpen) "收起照片（${assets.size}）" else "對照照片（${assets.size}）") }
                        photo?.let { TextButton(onClick = { focus.clearFocus(); preview(it.contentSha256) }, modifier = Modifier.testTag("preview-photo")) { Text("放大照片") } }
                        if (writableStage) TextButton(onClick = { if (state.dirty) append = true else appendPhotos() }, enabled = state.editable) { Text("補照片") }
                    }
                    if (showPhoto && !shortWindow) {
                        if (assets.size > 1) PhotoChoices(assets, photo?.id) { photoId = it }
                        photo?.let { EvidencePhoto(it.contentSha256, images, Modifier.fillMaxWidth().height(140.dp)) }
                        if (assets.isEmpty()) Text(if (writableStage) "照片可稍後補；先手動填寫這筆消費。" else "這筆消費沒有照片附件。", style = MaterialTheme.typography.bodySmall)
                    }
                }
                LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("review-form"), state = list,
                    contentPadding = PaddingValues(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item(key = "details") {
                        importStatus?.let { Text(it.lineSequence().first(), style = MaterialTheme.typography.bodySmall) }
                        Text("1  消費資料", style = MaterialTheme.typography.titleLarge)
                        if (writableStage) Text("手動填寫；空白表示尚未知道，不會當成 0。", style = MaterialTheme.typography.bodySmall)
                        Field("商家", input.merchant, state.editable, required = true, tag = "merchant") { session.edit(input.copy(merchant = it)) }
                        Field(if (writableStage) "消費日期（可稍後補）" else "消費日期", input.date, state.editable, kind = "date", hint = if (writableStage) "YYYY-MM-DD；不填會保留未知日期" else if (input.date.isBlank()) "這筆消費未記錄日期。" else null, tag = "date") { session.edit(input.copy(date = it)) }
                        Advanced("消費資料的照片來源") {
                            Text("以下選擇只關聯商家、日期與總額，不會替每個品項指定照片。")
                            EvidenceChoices(assets, input.evidenceIds, initial.evidenceIds, state.editable) { session.edit(input.copy(evidenceIds = it)) }
                            Text("商家：${factDescription(base.merchant)}\n日期：${factDescription(base.transactionDate)}\n總額：${factDescription(base.total)}")
                        }
                    }
                    item(key = "items-heading") {
                        Text("2  消費品項 · ${input.lines.size} 項", style = MaterialTheme.typography.titleLarge)
                        if (input.lines.isEmpty()) Text("按「新增品項」開始輸入餐點或商品。照片已屬於這筆消費，無須逐項重選。")
                    }
                    itemsIndexed(input.lines, key = { _, line -> line.id }) { index, line ->
                        val original = base.items.find { it.id == line.id }
                        fun update(next: ReviewLineInput) = session.edit(input.copy(lines = input.lines.map { if (it.id == next.id) next else it }))
                        Card(Modifier.fillMaxWidth().testTag("line-${line.id}")) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("${index + 1}. ${line.name.ifBlank { "未填品項名稱" }}", style = MaterialTheme.typography.titleMedium)
                                Text("數量 ${line.quantity.ifBlank { "未填" }} · 行合計 ${line.amount.ifBlank { "未填" }}", style = MaterialTheme.typography.bodyLarge)
                                TextButton(onClick = { openLine = if (openLine == line.id) null else line.id }) {
                                    Text(if (openLine == line.id) "收起品項" else if (writableStage) "修改品項 ${index + 1}" else "查看品項 ${index + 1}")
                                }
                                if (openLine == line.id) {
                                    Field("品項名稱", line.name, state.editable, required = true, multiline = true, tag = "line-name") { update(line.copy(name = it)) }
                                    Field("數量", line.quantity, state.editable, "quantity", required = true, hint = "購買的件數，例如 2", tag = "line-quantity") { update(line.copy(quantity = it)) }
                                    Field("行合計", line.amount, state.editable, "amount", required = true,
                                        hint = "這一列全部數量的金額，${(base.total as? Fact.Known)?.value?.currencyCode ?: "TWD"} 最小單位；不再乘以數量", tag = "line-amount") { update(line.copy(amount = it)) }
                                    Text("單價未記錄；請直接填明細上的行合計，不會由數量推算單價。", style = MaterialTheme.typography.bodySmall)
                                    photo?.let { TextButton(onClick = { focus.clearFocus(); preview(it.contentSha256) }) { Text("對照照片後繼續此品項") } }
                                    Advanced("此品項的照片關聯與來源") {
                                        Text("僅在能確認照片支撐此品項時選取。上方預覽的照片不會自動成為此品項的佐證。")
                                        EvidenceChoices(assets, line.evidenceIds, initial.lines.find { it.id == line.id }?.evidenceIds.orEmpty(), state.editable) { update(line.copy(evidenceIds = it)) }
                                        Text("品名：${factDescription(original?.rawName)}\n數量：${factDescription(original?.quantity)}\n行合計：${factDescription(original?.printedTotal)}\n原價：${factDescription(original?.referenceOriginalTotal)}")
                                        base.evidenceLinks.filter { it.target == EvidenceLinkTarget.ReceiptLine(line.id) }.forEach { link ->
                                            Text("照片 ${assets.indexOfFirst { it.id == link.evidence.assetId } + 1}：${linkStatusText(link.status)}")
                                        }
                                    }
                                    if (writableStage) TextButton(onClick = { deleteLine = line.id }, enabled = state.editable) { Text("刪除此品項") }
                                    OutlinedButton(onClick = { focus.clearFocus(); openLine = null }, modifier = Modifier.fillMaxWidth()) { Text(if (writableStage) "完成此品項，回到清單" else "收起品項明細") }
                                    if (writableStage) Text("完成品項後仍需保存草稿。", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                    item(key = "add-line") {
                        if (writableStage) Button(onClick = ::addLine, enabled = state.editable && input.lines.size < 100,
                            modifier = Modifier.fillMaxWidth().testTag("add-line")) { Text("新增品項") }
                    }
                    item(key = "check") {
                        HorizontalDivider()
                        Text(if (writableStage) "3  核對金額與完成記帳" else "3  記帳結果", style = MaterialTheme.typography.titleLarge)
                        Field("交易總額", input.total, state.editable, "amount", required = true,
                            hint = "明細上的整筆消費總額（${(base.total as? Fact.Known)?.value?.currencyCode ?: "TWD"} 最小單位）", tag = "total") { session.edit(input.copy(total = it)) }
                        if (writableStage) evaluation.draft?.let { Text(receiptAmountPreview(it), modifier = Modifier.testTag("amount-preview")) }
                        if (writableStage) Text("差額不為 0 時，請核對品項行合計、交易總額，以及是否有另列折扣或費用。", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { showAdjustments = !showAdjustments }) { Text(if (showAdjustments) "收起另列折扣／費用" else "另列折扣／費用（${input.adjustments.size} 筆）") }
                        if (showAdjustments) {
                            Text("只有明細另外列出的加減金額才填在這裡；已含在品項金額內的折扣不要再扣一次。")
                            input.adjustments.forEachIndexed { index, adjustment ->
                                AdjustmentEditor(index, adjustment, input, base, assets, state.editable, session)
                            }
                            if (writableStage) OutlinedButton(onClick = { session.edit(input.copy(adjustments = input.adjustments + ReviewAdjustmentInput())) },
                                enabled = state.editable && input.adjustments.size < 50) { Text("新增折扣／費用") }
                        }
                        if (writableStage) Row(Modifier.fillMaxWidth().testTag("complete-check").toggleable(input.complete, enabled = state.editable,
                            role = Role.Checkbox, onValueChange = { session.edit(input.copy(complete = it)) }), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(input.complete, null, enabled = state.editable)
                            Text("我已對照明細，所有品項與另列折扣／費用都已填完整。", Modifier.weight(1f).padding(vertical = 12.dp))
                        }
                        else Text(if (input.complete) "完整性：已完成核對" else "完整性：尚未確認")
                        if (writableStage) Text("保存草稿：保留目前進度，之後可修改。\n確認記帳：完成這筆消費，之後唯讀。", style = MaterialTheme.typography.bodyMedium)
                        evaluation.errors.forEach { Text(reviewLocations(it, input), color = MaterialTheme.colorScheme.error) }
                        result?.let { Text(reviewResultText(it, input), modifier = Modifier.testTag("reconciliation")) }
                        if (writableStage && !canConfirm) Text("可以先保存草稿；請在上方對應區塊補齊資料，再確認記帳。")
                        if (writableStage) Button(onClick = { focus.clearFocus(); confirm = true }, enabled = state.editable && !state.dirty && canConfirm,
                            modifier = Modifier.fillMaxWidth().testTag("confirm-transaction")) { Text("確認記帳") }
                    }
                    item(key = "notices") {
                        notice?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        state.message?.let { Text(reviewLocations(it, input)) }
                        if (state.conflict) {
                            Text("這筆消費已在其他地方更新；你的輸入仍保留，暫停編輯以免覆蓋。")
                            state.latest?.let { latest ->
                                Text("已保存的最新版：${latest.merchant.inputText()} · ${latest.total.inputText()} · ${stageText(latest.stage)}")
                                latest.items.forEach { Text("${it.rawName.inputText()} · 數量 ${it.quantity.inputText()} · 行合計 ${it.printedTotal.inputText()}") }
                            }
                            TextButton(onClick = { reload = true }, enabled = !state.busy) { Text("比較後放棄本地修改，載入最新版") }
                        }
                        Advanced("照片附件與保存結果") {
                            Text("${assets.size} 張照片附在這筆消費；原圖不會被修改。只看照片不會確認任何品項來源。")
                            importStatus?.let { Text(it) }
                        }
                        if (writableStage) Advanced("其他工具：本機收據辨識") {
                            Text("可以繼續手動填寫。本工具是現有的本機辨識功能，結果仍須人工核對。")
                            if (state.dirty) Text("請先保存草稿才能使用辨識；重新辨識可能取代已填資料。")
                            extraction()
                        }
                        base.extraction?.let { record -> Advanced("查看辨識原文與來源") {
                            Text("${record.provenance.extractorName} / ${record.provenance.extractorVersion} / ${record.provenance.promptVersion}")
                            record.warnings.forEach { Text(it) }
                            record.regions.forEach { region -> Text("照片 ${assets.indexOfFirst { it.id == region.assetId } + 1}：${region.rawText}") }
                        } }
                    }
                }
            }
        }
        Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = 8.dp, horizontal = 4.dp)) {
                Text(when {
                    state.busy -> state.operation ?: "正在處理，請稍候…"
                    state.conflict -> "保存未完成：請查看版本衝突說明"
                    !writableStage -> stageText(base.stage)
                    state.dirty && state.message != null -> "保存未完成 · 輸入仍保留，請查看待完成項目"
                    state.dirty -> "尚有未保存修改"
                    canConfirm -> "草稿已保存 · 可確認記帳"
                    else -> "草稿已保存 · 尚待完成"
                }, style = MaterialTheme.typography.labelLarge, modifier = Modifier.testTag("save-status"))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (writableStage && state.dirty) Button(onClick = { focus.clearFocus(); session.save() }, enabled = state.editable,
                        modifier = Modifier.testTag("save-draft")) { Text("保存草稿") }
                    if (writableStage && !state.dirty && canConfirm) Button(onClick = { confirm = true }, enabled = state.editable) { Text("確認記帳") }
                    else if (writableStage) TextButton(onClick = ::checkDetails, enabled = !state.busy) { Text("查看待完成項目") }
                    if (!writableStage) Button(onClick = { session.close() }, enabled = !state.busy) { Text("完成，返回消費紀錄") }
                }
            }
        }
    }
    if (leave) AlertDialog(onDismissRequest = { leave = false }, title = { Text("先保存這次填寫？") },
        text = { Column {
            Text("草稿可以不完整。保存成功後才會離開；尚未保存的內容不保證在強制停止 App 後保留。")
            if (state.conflict) Text("目前有版本衝突；請先返回畫面比較最新版，或明確放棄本次修改。")
            TextButton(onClick = { leave = false; session.close() }) { Text("放棄本次修改並離開") }
        } }, confirmButton = { TextButton(onClick = { leave = false; session.save { session.close() } }, enabled = state.editable) { Text("保存並離開") } },
        dismissButton = { TextButton(onClick = { leave = false }) { Text("繼續填寫") } })
    if (append) AlertDialog(onDismissRequest = { append = false }, title = { Text("先保存，再補照片") },
        text = { Text("目前品項會留在同一筆消費。加入新照片後，需要再次核對是否完整。") },
        confirmButton = { TextButton(onClick = { append = false; session.save { appendPhotos() } }) { Text("保存並選照片") } },
        dismissButton = { TextButton(onClick = { append = false }) { Text("繼續填寫") } })
    deleteLine?.let { id -> AlertDialog(onDismissRequest = { deleteLine = null }, title = { Text("刪除此品項？") },
        text = { Text("${input.lines.find { it.id == id }?.name.orEmpty()}\n只刪除此列，照片仍保留在這筆消費。若有折扣引用此品項，需先修改該筆折扣。") },
        confirmButton = { TextButton(onClick = { session.edit(input.copy(lines = input.lines.filterNot { it.id == id })); deleteLine = null; openLine = null }) { Text("刪除品項") } },
        dismissButton = { TextButton(onClick = { deleteLine = null }) { Text("保留品項") } }) }
    if (reload) AlertDialog(onDismissRequest = { reload = false }, title = { Text("放棄本地輸入？") },
        text = { Text("將重新讀取最新版；目前未保存輸入會被捨棄。") },
        confirmButton = { TextButton(onClick = { reload = false; session.reload() }) { Text("放棄並重新載入") } },
        dismissButton = { TextButton(onClick = { reload = false }) { Text("保留本地畫面") } })
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("完成這筆消費記帳？") },
        text = { Text("${input.merchant} · 總額 ${input.total}\n${result?.let { reviewResultText(it, input) }}\n確認後唯讀，無法修改；照片與明細一併保留。", modifier = Modifier.testTag("confirmation-summary")) },
        confirmButton = { TextButton(onClick = { confirm = false; session.confirm() }, modifier = Modifier.testTag("accept-confirmation")) { Text("確認完成記帳") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("繼續核對") } })
}

@Composable
internal fun Advanced(title: String, content: @Composable () -> Unit) {
    var open by rememberSaveable(title) { mutableStateOf(false) }
    TextButton(onClick = { open = !open }) { Text(if (open) "收起：$title" else "$title ＋") }
    if (open) Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
}

@Composable
private fun Field(label: String, value: String, enabled: Boolean, kind: String = "text", required: Boolean = false,
    hint: String? = null, tag: String = label, multiline: Boolean = false, changed: (String) -> Unit) {
    val problem = inputProblem(value, kind, required)
    OutlinedTextField(value, { if (it.length <= 500) changed(it) }, Modifier.fillMaxWidth().testTag(tag), readOnly = !enabled,
        label = { Text(label) }, singleLine = !multiline, maxLines = if (multiline) 5 else 1,
        isError = problem != null && value.isNotBlank(),
        keyboardOptions = KeyboardOptions(keyboardType = if (kind in setOf("amount", "quantity")) KeyboardType.Number else KeyboardType.Text),
        supportingText = { if (problem != null || hint != null) Text(problem ?: hint.orEmpty()) })
}

private fun factDescription(fact: Fact<*>?): String = when (fact) {
    is Fact.Known -> "${fact.inputText()}（${when (fact.provenance) { is FactProvenance.UserConfirmed -> "人工填寫"; is FactProvenance.Extracted -> "辨識候選"; is FactProvenance.Derived -> "規則計算" }}；${fact.provenance.evidence.size} 個照片引用）"
    is Fact.Conflicting -> "來源有衝突：${fact.candidates.joinToString { it.inputText() }}；請核對後填寫"
    is Fact.NotApplicable -> "不適用：${fact.reason}"
    is Fact.Unknown, null -> "未知"
}

private fun linkStatusText(status: EvidenceLinkStatus): String = when (status) {
    EvidenceLinkStatus.Candidate -> "待核對的來源關聯"
    EvidenceLinkStatus.Confirmed -> "已確認的來源關聯"
    EvidenceLinkStatus.Rejected -> "已排除的來源關聯"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EvidenceChoices(assets: List<EvidenceAsset>, selected: Set<String>, retained: Set<String>, enabled: Boolean, changed: (Set<String>) -> Unit) {
    if (assets.isEmpty()) { Text("尚無照片，可先手動填寫。") ; return }
    Text("選取表示你已核對此來源；已保存的關聯會保留。", style = MaterialTheme.typography.bodySmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        assets.forEachIndexed { index, asset ->
            FilterChip(asset.id in selected, { changed(if (asset.id in selected) selected - asset.id else selected + asset.id) },
                { Text("照片 ${index + 1}${if (asset.id in retained) " · 已關聯" else ""}") }, enabled = enabled && asset.id !in retained)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AdjustmentEditor(index: Int, adjustment: ReviewAdjustmentInput, input: ReviewInput, base: ReceiptDraft,
    assets: List<EvidenceAsset>, enabled: Boolean, session: ReviewSession) {
    fun update(next: ReviewAdjustmentInput) = session.edit(input.copy(adjustments = input.adjustments.map { if (it.id == next.id) next else it }))
    Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("加減項 ${index + 1}", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(adjustment.direction == AdjustmentDirection.Add, { update(adjustment.copy(direction = AdjustmentDirection.Add)) }, { Text("加上費用 ＋") }, enabled = enabled)
                FilterChip(adjustment.direction == AdjustmentDirection.Subtract, { update(adjustment.copy(direction = AdjustmentDirection.Subtract)) }, { Text("扣除折扣 −") }, enabled = enabled)
            }
            Field("加減金額", adjustment.amount, enabled, "amount", required = true, hint = "填正整數或 0；方向由上方決定") { update(adjustment.copy(amount = it)) }
            Text("這筆金額適用於哪裡？", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("" to "尚未確定", "order" to "整筆消費", "lines" to "指定品項").forEach { (value, label) ->
                    FilterChip(adjustment.scope == value, { update(adjustment.copy(scope = value)) }, { Text(label) }, enabled = enabled)
                }
            }
            if (adjustment.scope.isEmpty()) Text("請核對適用範圍；尚未確定仍可保存草稿，但不能確認記帳。")
            if (adjustment.scope == "lines") input.lines.forEachIndexed { lineIndex, line ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(line.id in adjustment.portions, { checked -> update(adjustment.copy(portions = if (checked) adjustment.portions + (line.id to "") else adjustment.portions - line.id)) }, enabled = enabled)
                    Text("${lineIndex + 1}. ${line.name.ifBlank { "未填品名" }}", Modifier.weight(1f))
                }
                if (line.id in adjustment.portions) Field("適用數量", adjustment.portions.getValue(line.id), enabled, "quantity", required = true) { update(adjustment.copy(portions = adjustment.portions + (line.id to it))) }
            }
            Advanced("加減項 ${index + 1} 的照片來源") {
                EvidenceChoices(assets, adjustment.evidenceIds, ReviewInput.from(base).adjustments.find { it.id == adjustment.id }?.evidenceIds.orEmpty(), enabled) { update(adjustment.copy(evidenceIds = it)) }
            }
            if (enabled) TextButton(onClick = { session.edit(input.copy(adjustments = input.adjustments.filterNot { it.id == adjustment.id })) }) { Text("刪除此加減項") }
        }
    }
}
