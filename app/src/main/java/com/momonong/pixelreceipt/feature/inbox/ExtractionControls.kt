package com.momonong.pixelreceipt.feature.inbox

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import com.momonong.pixelreceipt.domain.model.*
import com.momonong.pixelreceipt.domain.usecase.hasReviewContent

@Composable
fun ExtractionControls(draft: ReceiptDraft, assets: List<EvidenceAsset>, enabled: Boolean,
    session: ExtractionSession, state: ExtractionState) {
    var selected by rememberSaveable(draft.id) { mutableStateOf(listOf<String>()) }
    var replace by remember { mutableStateOf(false) }
    val choices = assets.filter { (it.kind as? Fact.Known)?.value !in setOf(EvidenceAssetKind.PriceTag, EvidenceAssetKind.PromotionSign, EvidenceAssetKind.Other) }
    val ids = selected.toSet().intersect(choices.map { it.id }.toSet())
    Text("本機中文 OCR＋收據欄位解析 · 幣別 TWD")
    Text("Gemini Nano：本版本未接入，裝置能力未檢查。辨識不使用雲端。")
    state.message?.let { Text(it) }
    if (state.busy) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        TextButton(onClick = session::cancel) { Text("取消辨識") }
    }
    if (draft.stage !in setOf(ReceiptStage.Captured, ReceiptStage.NeedsReview)) return
    Text("請勾選同一筆交易的收據頁面；不選菜單、價標或促銷牌。只有 TWD 收據適用本次解析。")
    choices.forEach { asset ->
        Row {
            Checkbox(asset.id in ids, { checked -> selected = (if (checked) ids + asset.id else ids - asset.id).toList() }, enabled = enabled && !state.busy)
            Text("收據圖片 ${assets.indexOf(asset) + 1} · ${asset.widthPx} × ${asset.heightPx}")
        }
    }
    Button(onClick = {
        if (hasReviewContent(draft)) replace = true else session.start(draft, ids, false)
    }, enabled = enabled && !state.busy && ids.isNotEmpty()) { Text("辨識收據／重試") }
    if (replace) AlertDialog(onDismissRequest = { replace = false }, title = { Text("重新辨識並取代核對欄位？") },
        text = { Text("目前版本 ${draft.revision}：${draft.items.size} 個品項、${draft.adjustments.size} 筆調整。成功後會取代商家、日期、總額、品項及調整，包含你保存的修改，並重新要求核對完整性。失敗保留原草稿；執行中資料變更會阻擋結果。") },
        confirmButton = { TextButton(onClick = { replace = false; session.start(draft, ids, true) }) { Text("同意取代並辨識") } },
        dismissButton = { TextButton(onClick = { replace = false }) { Text("保留目前資料") } })
}
