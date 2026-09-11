package com.momonong.pixelreceipt.feature.inbox

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.momonong.pixelreceipt.domain.model.*
import com.momonong.pixelreceipt.domain.usecase.hasReviewContent

@Composable
fun ExtractionControls(draft: ReceiptDraft, assets: List<EvidenceAsset>, enabled: Boolean,
    session: ExtractionSession, state: ExtractionState) {
    var selected by rememberSaveable(draft.id) { mutableStateOf(listOf<String>()) }
    var replace by remember { mutableStateOf(false) }
    val choices = assets.filter { (it.kind as? Fact.Known)?.value !in setOf(EvidenceAssetKind.PriceTag, EvidenceAssetKind.PromotionSign, EvidenceAssetKind.Other) }
    val single = assets.size == 1 && choices.size == 1
    val ids = if (single) setOf(choices.single().id) else selected.toSet().intersect(choices.map { it.id }.toSet())
    Text("先把收據整理成品項與金額，再核對及指定歸屬。")
    Text("照片只在此裝置辨識 · 適用 TWD 收據", style = MaterialTheme.typography.bodySmall)
    state.message?.takeIf { state.draftId == null || state.draftId == draft.id }?.let { Text(it) }
    if (state.busy) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        TextButton(onClick = session::cancel) { Text("取消辨識") }
    }
    if (draft.stage !in setOf(ReceiptStage.Captured, ReceiptStage.NeedsReview)) return
    Text(if (single) "使用這張收據照片建立清單。" else "只勾選這筆消費的收據頁面；先對照照片，不選價標或促銷牌。")
    if (!single) choices.forEach { asset ->
        Row {
            Checkbox(asset.id in ids, { checked -> selected = (if (checked) ids + asset.id else ids - asset.id).toList() }, enabled = enabled && !state.busy, modifier = Modifier.testTag("ocr-photo-${assets.indexOf(asset)}"))
            Text("收據圖片 ${assets.indexOf(asset) + 1} · ${asset.widthPx} × ${asset.heightPx}")
        }
    }
    Button(onClick = {
        if (hasReviewContent(draft)) replace = true else session.start(draft, ids, false)
    }, enabled = enabled && !state.busy && ids.isNotEmpty() && draft.personalExpenses.isEmpty() && draft.expenseAdjustments.isEmpty(),
        modifier = Modifier.testTag("recognize-receipt")) { Text(if (hasReviewContent(draft)) "重新辨識並建立清單" else "辨識並建立清單") }
    if (draft.personalExpenses.isNotEmpty() || draft.expenseAdjustments.isNotEmpty()) Text("已記錄品項歸屬，請直接修正清單，避免重新辨識覆蓋決定。")
    if (replace) AlertDialog(onDismissRequest = { replace = false }, title = { Text("重新辨識並取代核對欄位？") },
        text = { Text("目前有 ${draft.items.size} 個品項、${draft.adjustments.size} 筆調整。成功後會取代商家、日期、總額、品項及調整，包含你保存的修改，並重新要求核對完整性。失敗保留原草稿；執行中資料變更會阻擋結果。") },
        confirmButton = { TextButton(onClick = { replace = false; session.start(draft, ids, true) }) { Text("同意取代並辨識") } },
        dismissButton = { TextButton(onClick = { replace = false }) { Text("保留目前資料") } })
}
