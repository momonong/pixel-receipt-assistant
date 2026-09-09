package com.momonong.pixelreceipt.feature.inbox

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.momonong.pixelreceipt.data.ingestion.ImageStore
import com.momonong.pixelreceipt.domain.model.*
import com.momonong.pixelreceipt.domain.rules.ReceiptReconciler
import com.momonong.pixelreceipt.domain.rules.ReceiptReconciliationResult
import com.momonong.pixelreceipt.domain.usecase.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ReviewScreen(session: ReviewSession, state: ReviewState, assets: List<EvidenceAsset>, images: ImageStore,
    expanded: Boolean, preview: (String) -> Unit, notice: String? = null) {
    val base = state.base ?: return
    val input = state.input
    var leave by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var photoId by rememberSaveable(base.id) { mutableStateOf<String?>(null) }
    val photo = assets.find { it.id == photoId } ?: assets.firstOrNull()
    val evaluation = remember(base, input) { session.evaluation() }
    val result = evaluation.draft?.let { ReceiptReconciler().reconcile(it) }
    val canConfirm = result is ReceiptReconciliationResult.Balanced || result is ReceiptReconciliationResult.WithinTolerance
    val back = { if (state.dirty) leave = true else session.close() }
    BackHandler { if (!state.busy) back() }
    Column(Modifier.fillMaxSize().safeContentPadding().padding(16.dp)) {
        Text("人工核對", style = MaterialTheme.typography.headlineMedium)
        Text("${stageText(base.stage)} · 版本 ${base.revision}")
        notice?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        TextButton(onClick = back, enabled = !state.busy) { Text("返回圖片收件匣") }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (expanded) {
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Text("證據圖片", style = MaterialTheme.typography.titleLarge)
                    PhotoChoices(assets, photo?.id) { photoId = it }
                    photo?.let { EvidencePhoto(it, images, Modifier.weight(1f)) }
                        ?: Text("尚無證據圖片")
                    photo?.let { TextButton(onClick = { preview(it.contentSha256) }) { Text("開啟原圖預覽") } }
                }
            }
            LazyColumn(Modifier.weight(1f).fillMaxHeight().testTag("review-form"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    if (!expanded) {
                        PhotoChoices(assets, photo?.id) { photoId = it }
                        photo?.let {
                            EvidencePhoto(it, images, Modifier.fillMaxWidth().height(220.dp))
                            TextButton(onClick = { preview(it.contentSha256) }) { Text("放大預覽圖片") }
                        } ?: Text("尚無證據圖片")
                    }
                    Text("未保存輸入會隨畫面狀態復原；請按保存後再離開。強制停止或清除 App 前，務必先保存。")
                    Text("金額使用幣別最小單位整數（TWD 為元）；行金額不再乘數量。只有收據另列的加減項才新增調整，避免重複扣折扣。")
                    Text("沒有另列調整不代表已知零折扣；未知原價與折扣保持未知。")
                    base.extraction?.let { record ->
                        Text("辨識來源：${record.provenance.extractorName} / ${record.provenance.extractorVersion} / ${record.provenance.promptVersion}")
                        Text("區域座標以 EXIF 轉正後的 OCR 圖片為準；原圖未修改。")
                        record.warnings.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
                        var showText by remember(base.id) { mutableStateOf(false) }
                        TextButton(onClick = { showText = !showText }) { Text(if (showText) "收起辨識原文" else "查看辨識原文與區域依據") }
                        if (showText) record.regions.forEach { region ->
                            Text("圖片 ${assets.indexOfFirst { it.id == region.assetId } + 1} (${region.leftPx},${region.topPx})–(${region.rightPx},${region.bottomPx})：${region.rawText}")
                        }
                    }
                    state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                    if (state.conflict) {
                        Text("本地輸入暫時唯讀。請比較下方最新版後，選擇保留本地畫面或放棄輸入並重新載入。")
                        state.latest?.let { latest ->
                            Text("最新版 ${latest.revision} · ${stageText(latest.stage)}\n${latest.merchant.inputText()} · ${latest.transactionDate.inputText()} · 總額 ${latest.total.inputText()}")
                            latest.items.forEach { Text("${it.rawName.inputText()} × ${it.quantity.inputText()}：${it.printedTotal.inputText()}") }
                            latest.adjustments.forEach { Text("調整 ${it.direction} ${it.amount.inputText()}；${it.scope.inputText()}") }
                        }
                        TextButton(onClick = { reload = true }, enabled = !state.busy) { Text("放棄本地輸入，重新載入最新版") }
                    }
                    Field("商家", input.merchant, base.merchant, state.editable) { session.edit(input.copy(merchant = it)) }
                    Field("交易日期 YYYY-MM-DD（空白為未知）", input.date, base.transactionDate, state.editable) { session.edit(input.copy(date = it)) }
                    Text("日期未知會保留 Unknown；依既有確認規則，不單獨阻擋記帳。")
                    Field("收據總額", input.total, base.total, state.editable) { session.edit(input.copy(total = it)) }
                    EvidenceChoices(assets, input.evidenceIds, ReviewInput.from(base).evidenceIds, state.editable) { session.edit(input.copy(evidenceIds = it)) }
                    Row {
                        Checkbox(input.complete, { session.edit(input.copy(complete = it)) }, enabled = state.editable)
                        Text("我已核對收據完整，所有品項及另列調整均已輸入。", Modifier.padding(top = 12.dp))
                    }
                }
                itemsIndexed(input.lines, key = { _, line -> line.id }) { index, line ->
                    val original = base.items.find { it.id == line.id }
                    fun update(next: ReviewLineInput) = session.edit(input.copy(lines = input.lines.map { if (it.id == next.id) next else it }))
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("品項 ${index + 1}", style = MaterialTheme.typography.titleMedium)
                            Text("識別碼 ${line.id}", style = MaterialTheme.typography.labelSmall)
                            Field("品項名稱", line.name, original?.rawName, state.editable) { update(line.copy(name = it)) }
                            Field("數量", line.quantity, original?.quantity, state.editable) { update(line.copy(quantity = it)) }
                            Field("收據行金額（該行合計）", line.amount, original?.printedTotal, state.editable) { update(line.copy(amount = it)) }
                            Text("原價：${factDescription(original?.referenceOriginalTotal)}；不以實付額代填。")
                            EvidenceChoices(assets, line.evidenceIds, ReviewInput.from(base).lines.find { it.id == line.id }?.evidenceIds.orEmpty(), state.editable) { update(line.copy(evidenceIds = it)) }
                            TextButton(onClick = { session.edit(input.copy(lines = input.lines.filterNot { it.id == line.id })) }, enabled = state.editable) { Text("刪除此品項") }
                        }
                    }
                }
                item { Button(onClick = { session.edit(input.copy(lines = input.lines + ReviewLineInput())) }, enabled = state.editable && input.lines.size < 100) { Text("新增品項") } }
                itemsIndexed(input.adjustments, key = { _, adjustment -> adjustment.id }) { index, adjustment ->
                    val original = base.adjustments.find { it.id == adjustment.id }
                    fun update(next: ReviewAdjustmentInput) = session.edit(input.copy(adjustments = input.adjustments.map { if (it.id == next.id) next else it }))
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("另列調整 ${index + 1} · ${adjustment.id}")
                            Text("類型：${adjustment.kind}（新調整為人工其他項，不推論促銷）")
                            Row {
                                FilterChip(adjustment.direction == AdjustmentDirection.Add, { update(adjustment.copy(direction = AdjustmentDirection.Add)) }, { Text("加上 +") }, enabled = state.editable)
                                Spacer(Modifier.width(8.dp))
                                FilterChip(adjustment.direction == AdjustmentDirection.Subtract, { update(adjustment.copy(direction = AdjustmentDirection.Subtract)) }, { Text("扣除 −") }, enabled = state.editable)
                            }
                            Field("調整金額（非負）", adjustment.amount, original?.amount, state.editable) { update(adjustment.copy(amount = it)) }
                            Text("範圍：${factDescription(original?.scope)}")
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("" to "未知", "order" to "整筆", "lines" to "指定品項").forEach { (value, label) ->
                                    FilterChip(adjustment.scope == value, { update(adjustment.copy(scope = value)) }, { Text(label) }, enabled = state.editable)
                                }
                            }
                            if (adjustment.scope == "lines") input.lines.forEachIndexed { lineIndex, line ->
                                Row {
                                    Checkbox(line.id in adjustment.portions, { checked -> update(adjustment.copy(portions = if (checked) adjustment.portions + (line.id to "") else adjustment.portions - line.id)) }, enabled = state.editable)
                                    Text("品項 ${lineIndex + 1}：${line.name}", Modifier.padding(top = 12.dp))
                                }
                                if (line.id in adjustment.portions) Field("適用數量", adjustment.portions.getValue(line.id), null, state.editable) { update(adjustment.copy(portions = adjustment.portions + (line.id to it))) }
                            }
                            EvidenceChoices(assets, adjustment.evidenceIds, ReviewInput.from(base).adjustments.find { it.id == adjustment.id }?.evidenceIds.orEmpty(), state.editable) { update(adjustment.copy(evidenceIds = it)) }
                            TextButton(onClick = { session.edit(input.copy(adjustments = input.adjustments.filterNot { it.id == adjustment.id })) }, enabled = state.editable) { Text("刪除此調整") }
                        }
                    }
                }
                item {
                    Button(onClick = { session.edit(input.copy(adjustments = input.adjustments + ReviewAdjustmentInput())) }, enabled = state.editable && input.adjustments.size < 50) { Text("新增另列人工調整") }
                    HorizontalDivider(Modifier.padding(vertical = 12.dp))
                    Text("本機對帳", style = MaterialTheme.typography.titleLarge)
                    evaluation.errors.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
                    result?.let { Text(reconciliationText(it)) }
                    evaluation.draft?.let { Text(receiptAmountPreview(it)) }
                    Text("差額 = 品項行金額合計 + 加項 − 減項 − 收據總額；不自動補差額。")
                    if (base.stage == ReceiptStage.NeedsReview) {
                        Button(onClick = session::save, enabled = state.editable && state.dirty) { Text("保存修改") }
                        if (state.dirty) Text("有未保存修改；保存後才能確認。")
                        Button(onClick = { confirm = true }, enabled = state.editable && !state.dirty && canConfirm) { Text("確認記帳") }
                    } else Text("此狀態唯讀；本階段不提供重開或確認後修改。")
                }
            }
        }
    }
    if (leave) AlertDialog(onDismissRequest = { leave = false }, title = { Text("尚有未保存修改") },
        text = { Text("繼續編輯並保存，或明確放棄此次輸入。") },
        confirmButton = { TextButton(onClick = { leave = false; session.close() }) { Text("放棄修改並返回") } },
        dismissButton = { TextButton(onClick = { leave = false }) { Text("繼續編輯") } })
    if (reload) AlertDialog(onDismissRequest = { reload = false }, title = { Text("放棄本地輸入？") },
        text = { Text("將重新讀取最新版；目前未保存輸入會被捨棄。") },
        confirmButton = { TextButton(onClick = { reload = false; session.reload() }) { Text("放棄並重新載入") } },
        dismissButton = { TextButton(onClick = { reload = false }) { Text("保留本地畫面") } })
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("確認此筆交易") },
        text = { Text("${result?.let(::reconciliationText)}\n確認後唯讀，無法修改。") },
        confirmButton = { TextButton(onClick = { confirm = false; session.confirm() }) { Text("確認記帳") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("繼續核對") } })
}

@Composable
private fun Field(label: String, value: String, original: Fact<*>?, enabled: Boolean, changed: (String) -> Unit) {
    OutlinedTextField(value, { if (it.length <= 500) changed(it) }, Modifier.fillMaxWidth(), enabled = enabled,
        label = { Text(label) }, singleLine = true, supportingText = { Text("已保存：${factDescription(original)}") })
}

private fun factDescription(fact: Fact<*>?): String = when (fact) {
    is Fact.Known -> "${fact.inputText()} · ${when (fact.provenance) { is FactProvenance.UserConfirmed -> "使用者"; is FactProvenance.Extracted -> "擷取"; is FactProvenance.Derived -> "規則推導" }} · ${fact.provenance.evidence.size} 個證據引用"
    is Fact.Conflicting -> "有衝突：${fact.candidates.joinToString { it.inputText() }}；輸入可明確指定值"
    is Fact.NotApplicable -> "不適用：${fact.reason}"
    is Fact.Unknown -> "未知（${fact.reason}）"
    null -> "未知"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EvidenceChoices(assets: List<EvidenceAsset>, selected: Set<String>, retained: Set<String>, enabled: Boolean, changed: (Set<String>) -> Unit) {
    if (assets.isEmpty()) return
    Text("追加核對依據（既有關聯保留）", style = MaterialTheme.typography.labelMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        assets.forEachIndexed { index, asset ->
            FilterChip(asset.id in selected, { changed(if (asset.id in selected) selected - asset.id else selected + asset.id) },
                { Text("圖片 ${index + 1}") }, enabled = enabled && asset.id !in retained)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PhotoChoices(assets: List<EvidenceAsset>, selected: String?, choose: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        assets.forEachIndexed { index, asset -> FilterChip(asset.id == selected, { choose(asset.id) }, { Text("圖片 ${index + 1}") }) }
    }
}

@Composable
private fun EvidencePhoto(asset: EvidenceAsset, images: ImageStore, modifier: Modifier) {
    var bitmap by remember(asset.contentSha256) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(asset.contentSha256) { mutableStateOf(false) }
    LaunchedEffect(asset.contentSha256) {
        try { bitmap = withContext(Dispatchers.IO) { images.preview(asset.contentSha256)?.asImageBitmap() }; failed = bitmap == null }
        catch (error: Exception) { if (error is CancellationException) throw error; failed = true }
    }
    Box(modifier) {
        bitmap?.let { Image(it, "收據證據圖片", Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
            ?: Text(if (failed) "圖片無法讀取；請檢查裝置儲存空間。" else "載入圖片…")
    }
}
