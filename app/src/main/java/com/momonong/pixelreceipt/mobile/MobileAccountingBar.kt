package com.momonong.pixelreceipt.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
internal fun MobileNavigation(state: MobileUiState, actions: MobileActions) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        listOf(Triple("收件匣", MobilePage.Inbox) { actions.select(null) }, Triple("帳本", MobilePage.Ledger, actions.ledger), Triple("使用說明", MobilePage.Help, actions.help)).forEach { (label, page, action) ->
            TextButton(onClick = action, modifier = Modifier.weight(1f).heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 8.dp), colors = ButtonDefaults.textButtonColors(contentColor = if (state.page == page) MobileTeal else MobileMuted)) { Text(label) }
        }
    }
}
@Composable
internal fun NativePrimaryBar(state: MobileUiState, actions: MobileActions, compact: Boolean) {
    val batch = state.batch; val review = batch?.review
    val blockers = review?.let(::reviewBlockers).orEmpty().filter { it.text("code") != "receipt_completeness_unconfirmed" }
    val invalid = review?.let(::prepareReview)?.errors?.isNotEmpty() == true
    val formal = batch?.ledgerId != null || batch?.draftStatus in setOf("confirmed", "voided")
    val waiting = review?.dirty == true || state.savingReview || batch?.id in state.pendingReviewEdits
    val label: String
    val caption: String
    val enabled: Boolean
    val action: () -> Unit
    when (state.page) {
        MobilePage.Review -> {
            caption = if (formal) "中央已確認此帳目，可查看正式內容。" else if (review?.confirmation != null) "結果尚待中央確認；沿用同一筆確認查詢。" else "確認目前品項、金額、用途及收據完整後入帳。"
            label = when {
                state.busy != null -> state.busy
                batch?.ledgerId != null -> if (batch.ledgerStatus == "voided") "查看作廢紀錄" else "查看已完成帳目"
                formal -> "讀取正式帳目"
                !state.reviewer || !state.signedIn -> "登入後核對與入帳"
                review == null -> "重新讀取核對內容"
                review.conflict -> "讀取中央版本來比對"
                review.confirmation != null -> "查詢入帳結果"
                invalid -> "請修正標示的欄位"
                review.error != null || review.saveAttempt != null -> "重試更新金額"
                waiting -> "正在更新金額…"
                review.editor.text("assembly_status") == "incomplete" -> "補齊後即可入帳"
                blockers.isNotEmpty() -> "請先核對標示的欄位"
                else -> "確認入帳 ${moneyText(review.draft.getAsJsonObject("calculation")["printed_total_yuan"])}"
            }
            enabled = state.busy == null && state.writable && (formal || !state.reviewer || !state.signedIn || review == null || review.conflict || review.confirmation != null || (!invalid && (review.error != null || review.saveAttempt != null || (!waiting && blockers.isEmpty() && review.editor.text("assembly_status") != "incomplete"))))
            action = {
                when {
                    batch?.ledgerId != null -> actions.ledgerDetail(batch.ledgerId)
                    formal -> actions.dashboard(batch?.draftId)
                    !state.reviewer || !state.signedIn -> actions.settings()
                    batch == null || review == null -> actions.dashboard(batch?.draftId)
                    review.conflict -> actions.compareReview(batch.id)
                    review.confirmation != null -> actions.confirmReview(batch.id)
                    review.error != null || review.saveAttempt != null -> actions.saveReview(batch.id)
                    else -> actions.confirmReview(batch.id)
                }
            }
        }
        MobilePage.Ledger -> {
            caption = "已完成與已作廢的紀錄都保留在中央帳本。"
            label = state.busy ?: if (state.signedIn) "更新帳本" else "登入後讀取帳本"
            enabled = state.busy == null
            action = { if (state.signedIn) actions.refreshLedger(false) else actions.settings() }
        }
        MobilePage.LedgerDetail -> {
            caption = "顯示中央已確認的帳目內容。"
            label = state.busy ?: "回到帳本"
            enabled = state.busy == null
            action = actions.ledger
        }
        else -> {
            caption = "隨時可從畫面上方開啟說明。"
            label = "返回原畫面"; enabled = true; action = actions.back
        }
    }
    Surface(color = Color.White) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            HorizontalDivider(color = MobileRule)
            Column(Modifier.widthIn(max = 840.dp).fillMaxWidth().align(Alignment.CenterHorizontally).padding(horizontal = 20.dp, vertical = if (compact) 4.dp else 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!compact) Text(caption, style = MaterialTheme.typography.bodySmall, color = MobileMuted)
                Button(onClick = action, enabled = enabled, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("mobile-primary"), contentPadding = PaddingValues(14.dp)) { Text(label, style = MaterialTheme.typography.bodyLarge) }
            }
            if (state.page in setOf(MobilePage.Ledger, MobilePage.Help)) MobileNavigation(state, actions)
        }
    }
}
