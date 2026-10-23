package com.momonong.pixelreceipt.mobile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.momonong.pixelreceipt.data.ingestion.ImageStore
import com.momonong.pixelreceipt.feature.inbox.EvidencePhoto

@Composable
internal fun MobileDetail(state: MobileUiState, images: ImageStore, actions: MobileActions, modifier: Modifier, list: LazyListState) {
    val batch = state.batch
    if (batch == null) {
        Box(modifier.padding(20.dp)) { Text(if (state.ready) "找不到這筆收件，請返回收件匣。" else "正在讀取收件…") }
        return
    }
    var expanded by rememberSaveable(batch.id) { mutableStateOf<String?>(null) }
    var preview by rememberSaveable(batch.id) { mutableStateOf<String?>(null) }
    val itemKey = "batch:${batch.id}"
    LazyColumn(modifier.testTag("mobile-detail"), state = list, contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item(key = "$itemKey:header") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (batch.editable) "準備這筆收件" else progressTitle(batch), style = MaterialTheme.typography.headlineSmall)
                Text(if (batch.editable) "${batch.images.size} 張原圖與補充說明，會一起送交辨識。" else resultExplanation(batch), color = MobileMuted, style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (state.busy != null) item(key = "$itemKey:busy") {
            Surface(color = Color.White, shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(state.busy, style = MaterialTheme.typography.titleSmall)
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = MobileTeal, trackColor = MobileTealSoft)
                    if (!state.busy.contains("保存原圖")) TextButton(onClick = actions.stop, modifier = Modifier.heightIn(min = 48.dp).testTag("mobile-stop")) { Text("停止手機同步，保留資料") }
                }
            }
        }
        listOfNotNull(batch.message, state.message).distinct().forEach { message -> item(key = "$itemKey:notice:$message") { MobileNotice(message) } }
        state.edits[batch.id]?.let { edit -> item(key = "$itemKey:saving") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                MobileNotice(edit.error ?: "正在保存目前編輯，完成前不會送出。")
                if (edit.error != null) TextButton(onClick = { actions.retrySave(batch.id) }, enabled = state.busy == null,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("mobile-retry-save")) { Text("重試儲存目前編輯") }
            }
        } }
        if (!state.writable) item(key = "$itemKey:storage") { MobileNotice("本機暫時無法保存，現有收件仍可檢視。請重試儲存後再新增、修改或送出。") }
        if (!batch.importComplete) item(key = "$itemKey:import-review") { ImportReview(batch, state, actions) }
        item(key = "$itemKey:photos") {
            Surface(color = Color.White, shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("這批原圖", style = MaterialTheme.typography.titleSmall)
                        StatusTag(mobileStatus(batch), problem = !batch.importComplete || batch.status in setOf("failed", "unknown", "unavailable"))
                    }
                    if (batch.images.isNotEmpty()) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            itemsIndexed(batch.images, key = { _, image -> image.hash }) { index, image ->
                                Column(Modifier.width(76.dp).clickable { preview = image.hash }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    ReceiptThumb(image, images, Modifier.width(76.dp).height(102.dp))
                                    Text("${index + 1} · ${imageRoles[image.role].orEmpty()}", style = MaterialTheme.typography.bodySmall, color = MobileMuted)
                                }
                            }
                        }
                    } else Text("尚無完整照片，請先補齊原圖。", color = MobileMuted, style = MaterialTheme.typography.bodyMedium)
                    if (!batch.editable) {
                        HorizontalDivider(color = MobileRule)
                        IntakeMilestones(batch)
                    } else Text("點照片可放大對照；逐張區可調整成員與順序。", style = MaterialTheme.typography.bodySmall, color = MobileMuted)
                }
            }
        }
        item(key = "$itemKey:notes") {
            Surface(color = Color.White, shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (batch.editable) {
                        Text("送出前補充", style = MaterialTheme.typography.titleMedium)
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("這批資料的用途", style = MaterialTheme.typography.labelLarge)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(batch.purpose == "purchase", { actions.edit(batch.id) { it.copy(purpose = "purchase") } }, label = { Text("消費") }, enabled = state.busy == null && state.writable,
                                    shape = RoundedCornerShape(10.dp), modifier = Modifier.heightIn(min = 48.dp))
                                FilterChip(batch.purpose == "reference", { actions.edit(batch.id) { it.copy(purpose = "reference") } }, label = { Text("參考資料") }, enabled = state.busy == null && state.writable,
                                    shape = RoundedCornerShape(10.dp), modifier = Modifier.heightIn(min = 48.dp))
                            }
                        }
                        MobileNotes("batch:${batch.id}", batch.userNotes, "整批說明", "例如：牙膏幫朋友買，第二張是價標。", 4000, state.busy == null && state.writable,
                            Modifier.testTag("mobile-batch-notes")) { text -> actions.edit(batch.id) { it.copy(userNotes = text) } }
                    } else {
                        Text("送出的說明", style = MaterialTheme.typography.titleMedium)
                        Text(batch.userNotes.ifBlank { "沒有補充說明" }, color = if (batch.userNotes.isBlank()) MobileMuted else MobileInk, style = MaterialTheme.typography.bodyLarge)
                    }
                    HorizontalDivider(color = MobileRule)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(if (batch.editable) "本次辨識來源" else "已鎖定的辨識來源", style = MaterialTheme.typography.bodySmall, color = MobileMuted)
                            Text(if (batch.engine == "broker") "API Quota Broker" else "RTX 4090", style = MaterialTheme.typography.titleSmall)
                        }
                        TextButton(onClick = actions.settings, enabled = state.busy == null && state.writable, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (batch.editable) "更改" else "設定") }
                    }
                }
            }
        }
        item(key = "$itemKey:photo-heading") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("逐張說明", style = MaterialTheme.typography.titleMedium)
                Text(if (batch.editable) "調整照片角色，讓收據與參考資料各有位置。" else "保留送出時的照片角色與備註。", style = MaterialTheme.typography.bodyMedium, color = MobileMuted)
            }
        }
        itemsIndexed(batch.images, key = { _, image -> "$itemKey:photo:${image.hash}" }) { index, image ->
            val open = expanded == image.hash
            Surface(color = Color.White, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
                        ReceiptThumb(image, images, Modifier.width(76.dp).height(104.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("第 ${index + 1} 張 · ${imageRoles[image.role].orEmpty()}", style = MaterialTheme.typography.titleSmall)
                            if (!open || batch.editable) Text(image.notes.ifBlank { "未填逐圖說明" }, modifier = Modifier.fillMaxWidth().testTag("mobile-photo-description-$index"), style = MaterialTheme.typography.bodyMedium,
                                color = if (image.notes.isBlank()) MobileMuted else MobileInk, maxLines = if (open) 5 else 2)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(onClick = { expanded = if (open) null else image.hash }, contentPadding = PaddingValues(horizontal = 8.dp), modifier = Modifier.heightIn(min = 48.dp).testTag("mobile-photo-edit-$index")) {
                                    Text(if (open) "收合" else if (batch.editable) "編輯說明" else "查看說明")
                                }
                                TextButton(onClick = { preview = image.hash }, contentPadding = PaddingValues(horizontal = 8.dp), modifier = Modifier.heightIn(min = 48.dp)) { Text("放大") }
                            }
                        }
                    }
                    if (open && !batch.editable) {
                        HorizontalDivider(color = MobileRule)
                        Text(image.notes.ifBlank { "未填逐圖說明" }, modifier = Modifier.fillMaxWidth().testTag("mobile-photo-description-$index"),
                            style = MaterialTheme.typography.bodyMedium, color = if (image.notes.isBlank()) MobileMuted else MobileInk)
                    }
                    if (open && batch.editable) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { actions.edit(batch.id) { old -> old.copy(images = old.images.toMutableList().apply { add(index - 1, removeAt(index)) }) } },
                                enabled = state.busy == null && state.writable && index > 0, modifier = Modifier.heightIn(min = 48.dp).testTag("mobile-photo-up-$index")) { Text("往前") }
                            TextButton(onClick = { actions.edit(batch.id) { old -> old.copy(images = old.images.toMutableList().apply { add(index + 1, removeAt(index)) }) } },
                                enabled = state.busy == null && state.writable && index < batch.images.lastIndex, modifier = Modifier.heightIn(min = 48.dp).testTag("mobile-photo-down-$index")) { Text("往後") }
                            TextButton(onClick = { actions.edit(batch.id) { old -> old.copy(images = old.images.filterNot { it.hash == image.hash }) } },
                                enabled = state.busy == null && state.writable, modifier = Modifier.heightIn(min = 48.dp).testTag("mobile-photo-remove-$index")) { Text("移出這批") }
                        }
                        HorizontalDivider(color = MobileRule)
                        RolePicker(image.role, state.busy == null && state.writable) { role ->
                            actions.edit(batch.id) { old -> old.copy(images = old.images.map { if (it.hash == image.hash) it.copy(role = role) else it }) }
                        }
                        MobileNotes("photo:${batch.id}:${image.hash}", image.notes, "這張照片的說明", "例如：牙膏價標，含會員折扣。", 1000, state.busy == null && state.writable,
                            Modifier.testTag("mobile-photo-notes-$index")) { text ->
                            actions.edit(batch.id) { old -> old.copy(images = old.images.map { if (it.hash == image.hash) it.copy(notes = text) else it }) }
                        }
                    }
                }
            }
        }
        item(key = "$itemKey:footer") {
            Text(if (batch.editable) "不同交易請分別分享；原圖不會被改寫。" else "在 App 核對品項、金額與用途，最後確認入帳。", style = MaterialTheme.typography.bodySmall, color = MobileMuted)
            if (batch.intakeId != null && batch.status == "succeeded") TextButton(onClick = { actions.sync(batch.id) }, enabled = state.signedIn && state.busy == null && state.writable, modifier = Modifier.heightIn(min = 48.dp)) { Text("重新查詢收件狀態") }
        }
    }
    preview?.let { hash ->
        Dialog(onDismissRequest = { preview = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(color = MobileGround, modifier = Modifier.fillMaxSize()) {
                Column(Modifier.safeDrawingPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("對照原圖", style = MaterialTheme.typography.titleLarge)
                    EvidencePhoto(hash, images, Modifier.weight(1f).fillMaxWidth(), zoomable = true)
                    OutlinedButton(onClick = { preview = null }, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("返回收件") }
                }
            }
        }
    }
}


@Composable
private fun ImportReview(batch: MobileBatch, state: MobileUiState, actions: MobileActions) {
    val review = batch.importReview
    Surface(color = Color.White, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("核對這批照片與順序", style = MaterialTheme.typography.titleSmall)
            Text(review?.expectedCount?.let { "原選 $it 張，目前保存 ${batch.images.size} 張。" }
                ?: "原批次張數未知，目前保存 ${batch.images.size} 張。", style = MaterialTheme.typography.bodyMedium)
            val unknown = review?.originalSlots?.count { it == null }
            Text(if (unknown == null || review.originalSlots.isEmpty()) "原選取來源未完整記錄，請對照相簿確認。"
                else "原選取順序：" + review.originalSlots.map { hash -> hash?.let { batch.images.indexOfFirst { image -> image.hash == it }.takeIf { it >= 0 }?.plus(1)?.toString() ?: "已移出" } ?: "未讀取" }.joinToString(" → "),
                style = MaterialTheme.typography.bodySmall, color = MobileMuted)
            if (!review?.selectedHashes.isNullOrEmpty()) {
                Text("這次選取順序：" + review.selectedHashes.joinToString(" → ") { hash ->
                    batch.images.indexOfFirst { it.hash == hash }.takeIf { it >= 0 }?.plus(1)?.toString() ?: "已移出"
                }, style = MaterialTheme.typography.bodySmall, color = MobileMuted)
            }
            if (review != null) Text("先前已保存 ${review.previousHashes.count { hash -> batch.images.any { it.hash == hash } }} 張；這次新增 ${batch.images.count { it.hash !in review.previousHashes }} 張。",
                style = MaterialTheme.typography.bodySmall, color = MobileMuted)
            Text("下方照片由第 1 張起為最終順序。可逐張放大、往前／往後或移出；移出不刪原圖。請確認屬於同一筆交易，缺少照片可繼續補齊。",
                style = MaterialTheme.typography.bodyMedium, color = MobileMuted)
            OutlinedButton(onClick = { actions.confirmImport(batch.id) }, enabled = state.busy == null && state.writable && batch.images.isNotEmpty() && batch.id !in state.edits,
                shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("mobile-confirm-import")) { Text("確認目前 ${batch.images.size} 張與順序完整") }
        }
    }
}

@Composable
private fun IntakeMilestones(batch: MobileBatch) {
    val recognized = batch.status == "succeeded"
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        MilestoneRow("原圖上傳", "中央已確認 ${batch.uploadedHashes.size} / ${batch.images.size} 張", batch.uploadedHashes.size == batch.images.size && batch.images.isNotEmpty())
        MilestoneRow("中央辨識", if (recognized) "辨識完成" else statusLabel(batch.status), recognized)
        MilestoneRow(if (batch.purpose == "reference") "查看參考成果" else "帳本核對", if (batch.purpose == "reference") "不建立消費帳目" else if (batch.ledgerId != null) "已完成" else "由你確認金額與用途", batch.ledgerId != null)
    }
}

@Composable
private fun MilestoneRow(title: String, detail: String, done: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Text(if (done) "✓" else "·", style = MaterialTheme.typography.titleSmall, color = if (done) MobileTeal else MobileMuted, modifier = Modifier.width(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MobileMuted)
        }
    }
}

private fun progressTitle(batch: MobileBatch): String = when {
    batch.ledgerStatus == "voided" || batch.draftStatus == "voided" -> "這筆帳目已作廢"
    batch.ledgerId != null || batch.draftStatus == "confirmed" -> "這筆消費已完成"
    else -> when (batch.status) {
    "succeeded" -> if (batch.purpose == "reference") "參考成果已備妥" else "接下來，核對這筆消費"
    "unknown" -> "先確認原工作的結果"
    "failed", "unavailable" -> "這筆收件需要留意"
    else -> if (batch.intakeId != null) "中央正在處理" else "繼續同步這筆收件"
}

}

private fun resultExplanation(batch: MobileBatch): String = when {
    batch.ledgerStatus == "voided" || batch.draftStatus == "voided" -> "中央已作廢，原入帳內容保留供查閱。"
    batch.ledgerId != null || batch.draftStatus == "confirmed" -> "中央已確認入帳，可在 App 查看帳目明細。"
    else -> when (batch.status) {
    "succeeded" -> if (batch.purpose == "reference") "參考候選已保存。這批資料不會建立消費帳目。" else "辨識完成，直接在 App 核對品項、金額與用途，再確認入帳。"
    "failed", "unavailable" -> "資料已保留。可更新原工作狀態；不會自動重新辨識或切換來源。"
    "unknown" -> "中央執行結果尚不確定。只查詢原工作，不另建交易。"
    else -> if (batch.intakeId != null) "中央會持續處理，不需一直開著手機。回來按更新進度即可。" else "原請求內容已鎖定；繼續同步會沿用同一筆請求。"
}

}

@Composable
private fun RolePicker(role: String, enabled: Boolean, choose: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("照片角色", style = MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(onClick = { open = true }, enabled = enabled, shape = RoundedCornerShape(10.dp), modifier = Modifier.heightIn(min = 48.dp)) { Text("${imageRoles[role]} ▾") }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                imageRoles.forEach { (value, label) -> DropdownMenuItem(text = { Text(label) }, onClick = { choose(value); open = false }) }
            }
        }
    }
}

@Composable
internal fun MobileNotes(id: String, persisted: String, label: String, placeholder: String, limit: Int, enabled: Boolean, modifier: Modifier = Modifier, save: (String) -> Unit) {
    val text = persisted
    key(id) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            OutlinedTextField(text, save, modifier.fillMaxWidth(), placeholder = { Text(placeholder, color = MobileMuted) },
                supportingText = { Text("${text.length} / $limit 字") }, isError = text.length > limit,
                enabled = enabled, minLines = if (limit == 4000) 3 else 2, shape = RoundedCornerShape(10.dp), textStyle = MaterialTheme.typography.bodyLarge,
                colors = OutlinedTextFieldDefaults.colors(disabledTextColor = MobileInk, disabledBorderColor = MobileRule,
                    disabledPlaceholderColor = MobileMuted, disabledSupportingTextColor = MobileMuted))
        }
    }
}
