package com.momonong.pixelreceipt.mobile

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.google.gson.JsonObject

/** Shared native action surface; each invocation keeps its original navigation destination. */
@Composable
internal fun ReceiptActionMenu(batch: MobileBatch?, entry: JsonObject?, state: MobileUiState, actions: MobileActions) {
    if (batch?.deleted == true) return
    val ledgerId = entry?.text("id") ?: batch?.ledgerId
    val voided = entry?.text("status") == "voided" || batch?.ledgerStatus == "voided" || batch?.draftStatus == "voided"
    val target = ledgerId ?: batch?.id ?: return
    val localBatchId = batch?.id ?: state.batches.firstOrNull { it.ledgerId == ledgerId || it.draftId == entry?.text("draft_id") }?.id
    val pending = state.mutations.lastOrNull { it.phase == "pending" && ((localBatchId != null && it.batchId == localBatchId) || (it.targetId == ledgerId && it.kind == "ledger_void")) }
    if (voided && pending == null) return
    val candidate = entry?.getAsJsonObject("snapshot")?.getAsJsonObject("candidate") ?: batch?.review?.editor?.getAsJsonObject("candidate")
    val title = candidate?.text("merchant")?.takeIf { it.isNotBlank() } ?: batch?.let(::batchTitle) ?: "這筆帳目"
    val amount = candidate?.get("printed_total_yuan")?.let(::moneyText) ?: "金額待確認"
    val actionLabel = if (ledgerId != null) "作廢帳目" else "刪除收據"
    val blocker = when {
        pending != null -> null
        !state.writable -> "本機尚無法保存操作，請先重試本機儲存。"
        ledgerId != null && (!state.signedIn || !state.reviewer) -> "請先到設定登入，才能作廢中央帳目。"
        batch?.id in state.edits || batch?.id in state.pendingReviewEdits || state.savingReview -> "目前編輯尚未保存，請先完成或重試儲存。"
        batch != null && (!batch.importComplete || batch.importReview?.requiresConfirmation == true) -> "照片匯入或核對尚未完成；請先完成照片核對，再刪除收據。"
        ledgerId == null && (batch?.review?.saveAttempt != null || batch?.review?.confirmation != null) -> "保存或入帳結果尚待確認，請先查詢原結果。"
        ledgerId == null && batch?.frozen != null && batch.intakeId == null -> "原收件可能已送出，中央結果尚未確認；請先更新原收件進度，取得中央 ID 後再刪除。"
        ledgerId == null && batch?.intakeId != null && (!state.signedIn || !state.reviewer) -> "請先到設定登入，才能刪除中央收據。"
        else -> null
    }
    var menu by rememberSaveable(target) { mutableStateOf(false) }
    var confirm by rememberSaveable(target) { mutableStateOf(false) }
    var reason by rememberSaveable(target) { mutableStateOf("未註明") }
    Box {
        IconButton(onClick = { menu = true }, enabled = state.busy == null, modifier = Modifier.size(48.dp).testTag("receipt-more-$target").semantics { contentDescription = "$title，更多操作" }) {
            Canvas(Modifier.size(24.dp)) {
                listOf(.25f, .5f, .75f).forEach { y -> drawCircle(MobileMuted, 1.8.dp.toPx(), Offset(size.width / 2, size.height * y)) }
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(if (pending != null) "查詢${if (pending.kind == "ledger_void") "作廢" else "刪除"}結果" else actionLabel) },
                onClick = {
                    menu = false
                    if (pending != null) actions.retryReceiptMutation(pending)
                    else { reason = "未註明"; confirm = true }
                }, modifier = Modifier.heightIn(min = 48.dp).testTag("receipt-action-$target"))
        }
    }
    if (confirm) {
        val cancelFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) { cancelFocus.requestFocus() }
        AlertDialog(onDismissRequest = { confirm = false }, containerColor = MaterialTheme.colorScheme.surface, modifier = Modifier.testTag("receipt-action-dialog"), title = { Text(if (blocker == null) "$actionLabel？" else "暫時無法$actionLabel") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("$title · $amount", style = MaterialTheme.typography.titleSmall)
                    Text(blocker ?: if (ledgerId != null) "這筆將不再計入有效支出。原入帳內容與作廢原因保留，可在帳本查看已作廢紀錄。" else "刪除後會從收件匣移除，沒有復原按鈕。尚未入帳，不影響帳本支出；原圖與歷史資料保留。")
                    if (blocker == null && ledgerId != null) {
                        Text("作廢原因", style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf("未註明", "重複入帳", "資料有誤").forEach { value -> FilterChip(selected = reason == value, onClick = { reason = value }, label = { Text(value) }, modifier = Modifier.heightIn(min = 48.dp)) }
                        }
                    }
                }
            }, dismissButton = { TextButton(onClick = { confirm = false }, modifier = Modifier.heightIn(min = 48.dp).focusRequester(cancelFocus).testTag("receipt-action-cancel")) { Text("取消") } },
            confirmButton = {
                if (blocker == null) TextButton(onClick = {
                    confirm = false
                    if (ledgerId != null) actions.voidEntry(ledgerId, reason) else actions.deleteBatch(batch!!.id, reason)
                }, enabled = state.busy == null && state.writable, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error), modifier = Modifier.heightIn(min = 48.dp).testTag("receipt-action-confirm")) { Text(actionLabel) }
            })
    }
}

internal fun MobileActions.retryReceiptMutation(value: ReceiptMutation) {
    if (value.kind == "ledger_void") voidEntry(value.targetId, "未註明") else value.batchId?.let { deleteBatch(it, "未註明") }
}
internal val ReceiptMutation.resultLabel: String get() = if (kind == "ledger_void") "作廢" else "刪除"
