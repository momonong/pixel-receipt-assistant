package com.momonong.pixelreceipt.feature.inbox

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.momonong.pixelreceipt.domain.model.EvidenceAsset
import com.momonong.pixelreceipt.domain.model.ReceiptDraft
import com.momonong.pixelreceipt.domain.model.ReceiptStage
import com.momonong.pixelreceipt.domain.usecase.inputText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun InboxScreen(viewModel: InboxViewModel) {
    val drafts by viewModel.drafts.collectAsStateWithLifecycle()
    val selected by viewModel.selectedId.collectAsStateWithLifecycle()
    val evidence by viewModel.evidence.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val latest by viewModel.latestImport.collectAsStateWithLifecycle()
    val review by viewModel.review.state.collectAsStateWithLifecycle()
    val expanded = currentWindowAdaptiveInfoV2().windowSizeClass.minWidthDp >= 840
    var preview by rememberSaveable { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(20), viewModel::picked)
    val pick = {
        viewModel.pickerLaunched()
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
    BackHandler(selected != null && !expanded && preview == null && review.base == null) { viewModel.select(null) }
    Surface(Modifier.fillMaxSize()) {
        if (review.base != null) {
            ReviewScreen(viewModel.review, review, evidence.assets.takeIf { evidence.draftId == review.base?.id }.orEmpty(), viewModel.images, expanded, { preview = it }, error)
        } else Column(Modifier.fillMaxSize().safeContentPadding().padding(16.dp)) {
            Text("PixelReceipt AI", style = MaterialTheme.typography.headlineMedium)
            Text("收據草稿 · 圖片只保存在此裝置", style = MaterialTheme.typography.bodyMedium)
            progress?.let { (done, total) ->
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                Text("正在匯入 $done / $total 張，請保持 App 開啟。")
                TextButton(onClick = viewModel::cancelImport) { Text("取消本批匯入") }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp)) }
            review.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(Modifier.weight(1f).padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (expanded || selected == null) {
                    DraftList(drafts, selected, progress == null && !review.busy, viewModel::select, viewModel::createDraft,
                        { viewModel.select(null); pick() }, if (expanded) null else latest?.report, Modifier.weight(1f))
                }
                if (expanded || selected != null) {
                    EvidenceInbox(drafts.find { it.id == selected }, evidence.assets.takeIf { evidence.draftId == selected }.orEmpty(), progress == null && !review.busy, { viewModel.select(null) }, pick,
                        { selected?.let(viewModel.review::open) },
                        { preview = it }, latest?.report, Modifier.weight(if (expanded) 1.5f else 1f))
                }
            }
        }
    }
    preview?.let { hash ->
        Dialog(onDismissRequest = { preview = null }) {
            Surface(shape = MaterialTheme.shapes.large) {
                Column(Modifier.fillMaxWidth().fillMaxHeight(0.9f).padding(16.dp)) {
                    var bitmap by remember(hash) { mutableStateOf<ImageBitmap?>(null) }
                    var failed by remember(hash) { mutableStateOf(false) }
                    LaunchedEffect(hash) {
                        try {
                            bitmap = withContext(Dispatchers.IO) { viewModel.images.preview(hash)?.asImageBitmap() }
                            failed = bitmap == null
                        } catch (error: Exception) {
                            if (error is CancellationException) throw error
                            failed = true
                        }
                    }
                    Text("原始圖片預覽", style = MaterialTheme.typography.titleLarge)
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        if (failed) Text("原圖無法讀取，請確認裝置儲存空間。", color = MaterialTheme.colorScheme.error)
                        else bitmap?.let { Image(it, "匯入的原始收據圖片", Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                            ?: CircularProgressIndicator()
                    }
                    TextButton(onClick = { preview = null }) { Text("關閉") }
                }
            }
        }
    }
}

@Composable
private fun DraftList(
    drafts: List<ReceiptDraft>, selected: String?, enabled: Boolean,
    select: (String) -> Unit, create: () -> Unit, importNew: () -> Unit, report: String?, modifier: Modifier,
) {
    LazyColumn(modifier.fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("草稿列表", style = MaterialTheme.typography.titleLarge)
            Button(onClick = importNew, enabled = enabled) { Text("選取圖片建立草稿") }
            TextButton(onClick = create, enabled = enabled) { Text("建立空白草稿") }
        }
        if (drafts.isEmpty()) item { Text("尚無草稿。選取圖片，或從相簿分享圖片到 PixelReceipt AI。") }
        report?.let { item { Text("最近一批匯入結果\n$it") } }
        itemsIndexed(drafts, key = { _, draft -> draft.id }) { _, draft ->
            Card(onClick = { select(draft.id) }, enabled = enabled, modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = if (selected == draft.id) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(16.dp)) {
                    Text("草稿 ${draft.id.take(8)}", style = MaterialTheme.typography.titleMedium)
                    Text("${draft.merchant.inputText().ifBlank { "商家未知" }} · ${draft.evidenceAssetIds.size} 張圖片 · ${stageText(draft.stage)}")
                    Text("${draft.transactionDate.inputText().ifBlank { "日期未知" }} · 總額 ${draft.total.inputText().ifBlank { "未知" }}")
                }
            }
        }
    }
}

@Composable
private fun EvidenceInbox(
    draft: ReceiptDraft?, assets: List<EvidenceAsset>, enabled: Boolean, back: () -> Unit,
    pick: () -> Unit, review: () -> Unit, preview: (String) -> Unit, report: String?, modifier: Modifier,
) {
    val id = draft?.id
    LazyColumn(modifier.fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            TextButton(onClick = back) { Text("返回草稿列表") }
            Text(if (id == null) "選取草稿以查看圖片" else "草稿 ${id.take(8)}", style = MaterialTheme.typography.titleLarge)
            if (draft != null) {
                Text(stageText(draft.stage))
                if (draft.stage == ReceiptStage.Captured) Button(onClick = pick, enabled = enabled) { Text("補選圖片") }
                Button(onClick = review, enabled = enabled) { Text(if (draft.stage in setOf(ReceiptStage.Captured, ReceiptStage.NeedsReview)) "人工核對" else "查看交易明細") }
                if (draft.stage == ReceiptStage.Captured) Text("進入核對後不再追加圖片；請先補齊所有證據。")
            }
            if (id != null && assets.isEmpty()) Text("此草稿尚無圖片。可選取一張或多張圖片加入。")
        }
        itemsIndexed(assets, key = { _, asset -> asset.id }) { index, asset ->
            Card(onClick = { preview(asset.contentSha256) }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("圖片 ${index + 1} · 未分類", style = MaterialTheme.typography.titleMedium)
                    Text("${asset.widthPx} × ${asset.heightPx} · ${asset.byteSize / 1024} KiB")
                    Text("點選預覽原圖", color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        report?.let { item {
            HorizontalDivider()
            Text("最近一批匯入結果", style = MaterialTheme.typography.titleMedium)
            Text(it)
            Text("失敗圖片可重新選取；本草稿已有的相同圖片會自動跳過。")
        } }
    }
}
