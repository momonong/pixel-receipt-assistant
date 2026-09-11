package com.momonong.pixelreceipt.feature.inbox

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.momonong.pixelreceipt.domain.model.*
import com.momonong.pixelreceipt.domain.usecase.inputText

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun InboxScreen(viewModel: InboxViewModel) {
    val drafts by viewModel.drafts.collectAsStateWithLifecycle()
    val evidence by viewModel.evidence.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val latest by viewModel.latestImport.collectAsStateWithLifecycle()
    val review by viewModel.review.state.collectAsStateWithLifecycle()
    val extraction by viewModel.extraction.state.collectAsStateWithLifecycle()
    val pendingShare by viewModel.pendingShare.collectAsStateWithLifecycle()
    val expanded = currentWindowAdaptiveInfoV2().windowSizeClass.minWidthDp >= 840
    var preview by rememberSaveable { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(20), viewModel::picked)
    fun pick(target: String?) {
        viewModel.pickerLaunched(target)
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
    Surface(Modifier.fillMaxSize()) {
        val base = review.base
        if (base != null) {
            val assets = evidence.assets.takeIf { evidence.draftId == base.id }.orEmpty()
            ReviewScreen(viewModel.review, review.copy(busy = review.busy || extraction.busy,
                operation = if (extraction.busy) "正在本機辨識…" else review.operation), assets,
                viewModel.images, expanded, { preview = it }, error,
                appendPhotos = { pick(base.id) },
                importStatus = latest?.takeIf { it.draftId == base.id }?.report,
                progress = progress, cancelImport = viewModel::cancelImport,
                extraction = { ExtractionControls(base, assets, !review.dirty && !review.busy && progress == null,
                    viewModel.extraction, extraction) })
        } else Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.widthIn(max = 880.dp).fillMaxSize().padding(horizontal = 20.dp),
                contentPadding = PaddingValues(vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item {
                    Text("PixelReceipt AI", style = MaterialTheme.typography.titleMedium)
                    Text("消費紀錄", style = MaterialTheme.typography.headlineLarge)
                }
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("把這筆消費記下來", style = MaterialTheme.typography.titleLarge)
                            Text("選取收據照片，App 先建立品項與金額清單。核對後指定自用、代買或送禮，查看自己負擔多少。")
                            Button(onClick = { pick(null) }, enabled = progress == null && !review.busy,
                                modifier = Modifier.fillMaxWidth().testTag("new-transaction")) { Text("新增消費・選照片") }
                            TextButton(onClick = viewModel::createDraft, enabled = progress == null && !review.busy,
                                modifier = Modifier.fillMaxWidth()) { Text("沒有照片，直接填寫") }
                            Text("也可以在相簿或相機檢視照片時，分享至 PixelReceipt AI。", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                progress?.let { (done, total) -> item {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("正在保存照片 $done / $total；完成後進入這筆消費。")
                    TextButton(onClick = viewModel::cancelImport) { Text("取消本批匯入") }
                } }
                error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
                review.message?.let { item { Text(it) } }
                if (drafts.isEmpty()) item { Text("尚無消費紀錄。照片與草稿只保存在此裝置。") }
                else item { Text("繼續填寫或查看已記帳消費", style = MaterialTheme.typography.titleMedium) }
                items(drafts, key = { it.id }) { draft ->
                    Card(onClick = { viewModel.openTransaction(draft.id) }, enabled = progress == null && !review.busy,
                        modifier = Modifier.fillMaxWidth().testTag("transaction-${draft.id}")) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(draft.merchant.inputText().ifBlank { "未填商家的消費" }, style = MaterialTheme.typography.titleLarge)
                            Text(stageText(draft.stage), color = MaterialTheme.colorScheme.primary)
                            Text("${draft.transactionDate.inputText().ifBlank { "日期未填" }} · ${draft.items.size} 個品項 · ${draft.evidenceAssetIds.size} 張照片")
                            Text("總額 ${draft.total.inputText().ifBlank { "尚未填寫" }}", style = MaterialTheme.typography.titleMedium)
                            Text(if (draft.stage in setOf(ReceiptStage.Captured, ReceiptStage.NeedsReview)) "繼續填寫 →" else "查看明細 →")
                        }
                    }
                }
                latest?.takeIf { it.draftId == null || it.state == "interrupted" }?.let { record -> item {
                    Text("最近照片匯入結果：${record.report}")
                } }
            }
        }
    }
    preview?.let { hash -> PhotoPreviewDialog(hash, viewModel.images) { preview = null } }
    pendingShare?.let { pending ->
        AlertDialog(onDismissRequest = viewModel::cancelPendingShare,
            title = { Text("收到 ${pending.uris.size} 張新消費照片") },
            text = { Column {
                Text(if (review.dirty) "先保存目前這筆，再開始新消費；兩筆交易不會混在一起。" else "目前交易已保存。這批分享照片將開始另一筆消費。")
                review.message?.let { Text(it) }
            } },
            confirmButton = { TextButton(onClick = viewModel::acceptPendingShare,
                enabled = !review.busy && !extraction.busy && progress == null && (!review.dirty || review.editable)) {
                Text(if (review.dirty) "保存目前草稿並新增消費" else "開始新的消費")
            } },
            dismissButton = { TextButton(onClick = viewModel::cancelPendingShare) { Text("留在目前交易，取消這批分享") } })
    }
}
