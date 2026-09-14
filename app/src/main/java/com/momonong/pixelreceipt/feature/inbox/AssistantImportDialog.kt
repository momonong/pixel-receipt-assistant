package com.momonong.pixelreceipt.feature.inbox

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.momonong.pixelreceipt.data.ingestion.AssistantReceiptText

@Composable
fun AssistantImportDialog(text: String, busy: Boolean, error: String?, dirty: Boolean,
    onChange: (String) -> Unit, onImport: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    val preview = remember(text) { runCatching { AssistantReceiptText.parse(text) }.getOrNull() }
    Dialog(onDismissRequest = { if (!busy) onClose() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = 640.dp).fillMaxWidth().fillMaxHeight(0.92f).padding(12.dp),
            shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(20.dp).imePadding()) {
                Text("匯入 Gemini 整理的收據", style = MaterialTheme.typography.titleLarge)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("先把整理指令交給 Gemini，再提供照片或口述消費。把完整回覆複製回來，就能建立品項清單。")
                    Text("你交給 Gemini 的資料由 Gemini 處理；這裡只接收回覆並保存在本機，不會自動傳送照片或帳本。")
                    OutlinedButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("PixelReceipt 整理指令", AssistantReceiptText.prompt))
                        copied = true
                    }, enabled = !busy, modifier = Modifier.testTag("copy-gemini-prompt")) {
                        Text(if (copied) "已複製整理指令" else "複製給 Gemini 的整理指令")
                    }
                    OutlinedTextField(value = text, onValueChange = onChange, enabled = !busy,
                        label = { Text("貼上 Gemini 的完整收據回覆") }, minLines = 5, maxLines = 10,
                        modifier = Modifier.fillMaxWidth().testTag("assistant-text"))
                    Text("請使用文字內容，不是聊天分享網址。App 無法驗證回覆由哪個模型產生；品項、金額與用途仍須核對。",
                        style = MaterialTheme.typography.bodySmall)
                    preview?.let { receipt ->
                        Text("${receipt.merchant ?: "商家未知"} · ${receipt.date ?: "日期未知"}")
                        Text("${receipt.items.size} 個品項 · 總額 ${receipt.totalMinor?.toString() ?: "未知"} TWD",
                            modifier = Modifier.testTag("assistant-preview"))
                        receipt.items.forEach { Text("${it.name ?: "品名未知"} · 數量 ${it.quantity?.toString() ?: "未知"} · ${it.lineTotalMinor?.toString() ?: "金額未知"}") }
                        if (receipt.adjustments.isNotEmpty()) Text("另有 ${receipt.adjustments.size} 筆加減項，匯入後請核對適用範圍。")
                    }
                    if (dirty) Text("建立清單前會先保存目前正在編輯的交易。")
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("assistant-error")) }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Button(onClick = onImport, enabled = !busy && text.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().testTag("import-assistant")) {
                    Text(if (dirty) "保存目前交易並建立待核對清單" else "建立待核對清單")
                }
                TextButton(onClick = onClose, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("取消匯入") }
            }
        }
    }
}
