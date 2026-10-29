package com.momonong.pixelreceipt.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun DuplicateShareDialog(reminder: DuplicateShareReminder, state: MobileUiState, actions: MobileActions) {
    val cancelFocus = remember { FocusRequester() }
    val list = rememberLazyListState()
    val maximumHeight = (with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() } - 48.dp).coerceAtLeast(160.dp)
    Dialog(onDismissRequest = { actions.cancelDuplicate(reminder) }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val inputMode = LocalInputModeManager.current.inputMode
        LaunchedEffect(reminder, inputMode) {
            list.scrollToItem(0)
            if (inputMode == InputMode.Keyboard) cancelFocus.requestFocus()
        }
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.padding(horizontal = 16.dp).widthIn(max = 560.dp).fillMaxWidth().heightIn(max = maximumHeight).testTag("duplicate-share-dialog")) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LazyColumn(Modifier.weight(1f, fill = false).fillMaxWidth().testTag("duplicate-share-candidates"), state = list, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("可能已分享過", style = MaterialTheme.typography.headlineSmall)
                            Text("新照片與說明已保留。你可以查看舊收件，或另建一筆。", style = MaterialTheme.typography.bodyMedium)
                            Text("只比對這台裝置已保存的收據原圖；相同原圖也可能是不同交易。", style = MaterialTheme.typography.bodySmall, color = MobileMuted)
                        }
                    }
                    items(reminder.candidates, key = { it.id }) { candidate ->
                        OutlinedButton(onClick = { actions.openDuplicate(reminder, candidate.id) }, enabled = state.busy == null,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("duplicate-candidate-${candidate.id}"),
                            shape = RoundedCornerShape(14.dp), contentPadding = PaddingValues(14.dp)) {
                            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(batchTitle(candidate), style = MaterialTheme.typography.titleSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                Text("收件 ${SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.TAIWAN).format(Date(candidate.createdAt))}", style = MaterialTheme.typography.bodySmall)
                                Text(when {
                                    candidate.deleted -> "已刪除 · 僅供查看"
                                    candidate.ledgerStatus == "voided" || candidate.draftStatus == "voided" -> "已作廢 · 查看歷史帳目"
                                    candidate.ledgerId != null -> "已完成 · 查看帳目"
                                    candidate.frozen != null && candidate.intakeId == null -> "結果待確認 · 查看原收件"
                                    else -> "${batchStatus(candidate)} · 查看舊收件"
                                }, style = MaterialTheme.typography.bodyMedium)
                                val differences = duplicateShareDifferences(reminder.incoming, candidate)
                                if (differences.isNotEmpty()) Text(differences.joinToString("、"), style = MaterialTheme.typography.bodySmall, color = MobileMuted)
                            }
                        }
                    }
                }
                OutlinedButton(onClick = { actions.cancelDuplicate(reminder) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).focusRequester(cancelFocus).testTag("duplicate-share-cancel")) { Text("取消，返回新收件") }
                Button(onClick = { actions.sendSeparateDuplicate(reminder) }, enabled = state.busy == null && state.writable,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("duplicate-share-separate")) { Text("仍另建一筆並送出") }
            }
        }
    }
}
