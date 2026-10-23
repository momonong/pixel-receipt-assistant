package com.momonong.pixelreceipt.mobile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.momonong.pixelreceipt.data.ingestion.ImageStore
import com.momonong.pixelreceipt.feature.inbox.EvidencePhoto

internal fun reviewErrors(review: MobileReview): Map<String, String> {
    val errors = linkedMapOf<String, String>()
    reviewBlockers(review).forEach { issue ->
        val code = issue.text("code")
        val index = issue["item_position"]?.asInt
        val adjustment = issue["adjustment_position"]?.asInt
        val field = when {
            index != null -> "item.$index." + when (code) { "item_name_unknown" -> "raw_name"; "item_quantity_unknown" -> "quantity"; "item_amount_unknown" -> "printed_line_total_yuan"; else -> "purpose" }
            adjustment != null -> "adjustment.$adjustment." + when (code) { "adjustment_amount_unknown" -> "amount_yuan"; "adjustment_direction_unknown" -> "direction"; "adjustment_scope_unknown" -> "scope"; "adjustment_effect_unknown" -> "amount_effect"; else -> "purpose" }
            else -> when (code) { "merchant_unknown" -> "merchant"; "receipt_date_unknown" -> "receipt_date"; "printed_total_unknown" -> "printed_total_yuan"; else -> "total" }
        }
        val message = when (code) {
            "receipt_completeness_unconfirmed" -> null // The final action explicitly covers completeness.
            "merchant_unknown" -> "請補店家名稱。"
            "receipt_date_unknown" -> "請補消費日期。"
            "printed_total_unknown" -> "請補收據實付總額。"
            "item_name_unknown" -> "請補品名。"
            "item_quantity_unknown" -> "請補購買數量。"
            "item_amount_unknown" -> "請補此品項行金額。"
            "item_quantity_allocation_mismatch" -> "分攤數量須與購買數量相同。"
            "item_amount_allocation_mismatch" -> "分攤金額須與此品項行金額相同。"
            "item_allocation_without_quantity" -> "有分攤金額的用途也需要數量。"
            "item_purpose_unconfirmed" -> "請確認用途。"
            "adjustment_amount_unknown" -> "請補折扣或加收金額。"
            "adjustment_direction_unknown" -> "請選折扣或加收。"
            "adjustment_scope_unknown" -> "請選影響範圍。"
            "adjustment_effect_unknown" -> "請確認是否已含在行金額。"
            "adjustment_allocation_unconfirmed", "adjustment_item_purpose_unconfirmed" -> "請確認受影響品項與用途分配。"
            "adjustment_allocation_mismatch" -> "分配金額須與這筆折扣或加收相同。"
            "adjustment_allocation_outside_scope" -> "只能分配給影響範圍內的用途。"
            "zero_allocation_basis" -> "請先補齊受影響品項與金額。"
            "receipt_total_mismatch" -> "明細與實付不一致，請對照原圖修正。"
            "tolerance_acknowledgement_required" -> "明細與實付相差 1 元，請對照原圖確認。"
            "no_items" -> "請新增收據上的品項。"
            "negative_purpose_burden" -> "折扣超過所分配用途的金額，請修正。"
            else -> "請核對這個欄位與相關分配。"
        }
        if (message != null) errors[field] = message
    }
    errors.putAll(prepareReview(review).errors)
    return errors
}

@Composable
internal fun MobileReviewScreen(state: MobileUiState, images: ImageStore, actions: MobileActions, modifier: Modifier, list: LazyListState) {
    val batch = state.batch
    val review = batch?.review
    if (batch == null || review == null) {
        Column(modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(if (state.busy != null) "正在讀取收據…" else "核對內容尚未載入", style = MaterialTheme.typography.headlineSmall)
            if (state.busy != null) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(state.message ?: if (!state.reviewer) "登入一次後，就能在 App 內核對與入帳。" else "請重新讀取原工作；不會重新辨識。", color = MobileMuted)
            TextButton(onClick = { if (!state.reviewer) actions.settings() else actions.dashboard(batch?.draftId) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (!state.reviewer) "前往登入" else "重新讀取") }
        }
        return
    }
    val editor = review.editor
    val candidate = editor.getAsJsonObject("candidate")
    val errors = reviewErrors(review)
    val editable = state.busy == null && state.writable && !review.locked && batch.ledgerId == null && batch.draftStatus !in setOf("confirmed", "voided")
    var headerOpen by rememberSaveable(batch.id) { mutableStateOf(false) }
    var expanded by rememberSaveable(batch.id) { mutableStateOf<String?>(null) }
    var preview by rememberSaveable(batch.id) { mutableStateOf<String?>(null) }
    fun change(block: (JsonObject) -> Unit) = actions.editReview(batch.id, block)
    LazyColumn(modifier.testTag("native-review"), state = list, contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "receipt") {
            ReviewCard {
                if (batch.ledgerId != null) StatusTag(if (batch.ledgerStatus == "voided") "已作廢" else "已完成", problem = batch.ledgerStatus == "voided")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(candidate.text("merchant").ifBlank { "店家待確認" }, style = MaterialTheme.typography.headlineSmall)
                        Text(candidate.text("receipt_date").ifBlank { "日期待確認" }, style = MaterialTheme.typography.bodyMedium, color = MobileMuted)
                        Text(moneyText(candidate["printed_total_yuan"]), style = MaterialTheme.typography.headlineMedium, color = MobileTealInk)
                        Text("收據實付 · ${candidate.array("items").size()} 個品項", style = MaterialTheme.typography.bodySmall, color = MobileMuted)
                    }
                    TextButton(onClick = { headerOpen = !headerOpen }, enabled = editable, modifier = Modifier.heightIn(min = 48.dp).testTag("review-header-edit")) { Text(if (headerOpen) "收合" else "修改") }
                }
                if (headerOpen) {
                    ReviewField("店家", candidate.text("merchant"), errors["merchant"], "review-merchant", editable) { value -> change { it.getAsJsonObject("candidate").addProperty("merchant", value) } }
                }
                // Fields are expanded only when needed; the receipt remains readable at first glance.
                if (headerOpen) {
                    ReviewField("消費日期", candidate.text("receipt_date"), errors["receipt_date"], "review-date", editable) { value -> change { it.getAsJsonObject("candidate").addProperty("receipt_date", value) } }
                    ReviewField("實付總額（元）", candidate.text("printed_total_yuan"), errors["printed_total_yuan"], "review-total", editable, true) { value -> change { it.getAsJsonObject("candidate").addProperty("printed_total_yuan", value) } }
                } else listOf("merchant", "receipt_date", "printed_total_yuan").forEach { key -> errors[key]?.let { ReviewError(it) } }
                errors["total"]?.let { ReviewError(it) }
                val calc = review.draft.getAsJsonObject("calculation")
                if (!review.dirty && calc["computed_total_yuan"]?.isJsonNull == false) {
                    Text("明細核算 ${moneyText(calc["computed_total_yuan"])}" + calc["reconciliation_difference_yuan"]?.takeUnless { it.isJsonNull }?.let { " · 差額 ${it.asString} 元" }.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MobileMuted)
                }
                if (reviewBlockers(review).any { it.text("code") == "tolerance_acknowledgement_required" }) {
                    Row(Modifier.fillMaxWidth().clickable(enabled = editable) { change { it.addProperty("tolerance_acknowledgement", if (editor.text("tolerance_acknowledgement") == "accept_within_tolerance") "none" else "accept_within_tolerance") } }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(editor.text("tolerance_acknowledgement") == "accept_within_tolerance", onCheckedChange = null, enabled = editable)
                        Text("已對照原圖，接受這 1 元差額", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (batch.images.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(batch.images) { index, image ->
                        TextButton(onClick = { preview = image.hash }, modifier = Modifier.heightIn(min = 48.dp).testTag("review-photo-$index")) { Text("原圖 ${index + 1}・放大") }
                    }
                }
                if (batch.userNotes.isNotBlank()) Text(batch.userNotes, style = MaterialTheme.typography.bodySmall, color = MobileMuted)
            }
        }
        if (review.error != null) item(key = "error") { MobileNotice(review.error) }
        if (review.retainedEditorJson != null) item(key = "retained") { Text("未採用的手機編輯已另存保留。", style = MaterialTheme.typography.bodySmall, color = MobileMuted) }
        if (review.conflict) item(key = "conflict") {
            ReviewCard {
                Text("中央版本有變更", style = MaterialTheme.typography.titleMedium)
                Text("目前手機內容保留在下方。讀取中央版本只供比較，尚不會覆寫。", style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { actions.compareReview(batch.id) }, enabled = state.busy == null, modifier = Modifier.heightIn(min = 48.dp)) { Text("讀取中央版本來比對") }
                review.comparisonJson?.let { remote ->
                    val latest = draftObject(remote).getAsJsonObject("candidate")
                    Text("中央：${latest.text("merchant")} · ${latest.text("receipt_date")} · ${moneyText(latest["printed_total_yuan"])}", style = MaterialTheme.typography.titleSmall)
                    latest.array("items").forEach { item -> Text("${item.asJsonObject.text("raw_name")} · ${item.asJsonObject.text("quantity")} 件 · ${moneyText(item.asJsonObject["printed_line_total_yuan"])}", style = MaterialTheme.typography.bodyMedium) }
                    reviewDifferences(review).forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    Text("採用中央會另存手機原稿；保留手機內容會在下一步更新中央草稿，仍需最後確認才入帳。", style = MaterialTheme.typography.bodySmall, color = MobileMuted)
                    OutlinedButton(onClick = { actions.resolveReview(batch.id, false) }, enabled = state.busy == null && review.confirmation == null, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("採用中央版本，保留手機原稿") }
                    OutlinedButton(onClick = { actions.resolveReview(batch.id, true) }, enabled = state.busy == null && review.confirmation == null, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("保留手機內容，套用到最新版本") }
                }
            }
        }
        item(key = "items-heading") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("品項與用途", style = MaterialTheme.typography.titleMedium)
                Text("未指定的預設自己用；代買、送人或分攤可逐項修改。", style = MaterialTheme.typography.bodySmall, color = MobileMuted)
            }
        }
        itemsIndexed(candidate.array("items").toList(), key = { index, _ -> "item-$index" }) { index, element ->
            val item = element.asJsonObject
            val decision = editor.array("line_decisions")[index].asJsonObject
            val open = expanded == "item-$index"
            ReviewCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${index + 1}. ${item.text("raw_name").ifBlank { "品名待確認" }}", style = MaterialTheme.typography.titleSmall)
                        Text("${item.text("quantity").ifBlank { "?" }} 件 · ${purposeSummary(decision)}", style = MaterialTheme.typography.bodyMedium, color = MobileMuted)
                        Text(moneyText(item["printed_line_total_yuan"]), style = MaterialTheme.typography.titleMedium, color = MobileTealInk)
                    }
                    TextButton(onClick = { expanded = if (open) null else "item-$index" }, modifier = Modifier.heightIn(min = 48.dp).testTag("review-item-$index")) { Text(if (open) "收合" else "修改") }
                }
                errors.filterKeys { it.startsWith("item.$index.") }.values.distinct().forEach { ReviewError(it) }
                if (open) {
                    listOf("raw_name" to "品名", "quantity" to "購買數量", "printed_line_total_yuan" to "此品項行金額（元）", "printed_unit_price_yuan" to "票面單價（元，未知可留空）").forEach { (key, label) ->
                        ReviewField(label, item.text(key), errors["item.$index.$key"], "review-item-$index-$key", editable, key != "raw_name") { value -> change { it.getAsJsonObject("candidate").array("items")[index].asJsonObject.addProperty(key, value) } }
                    }
                    val choice = if (decision.text("method") == "whole_line") decision.text("whole_purpose") else "split"
                    ReviewSelect("用途", choice, purposeLabels + ("split" to "分攤"), editable) { purpose -> change { edit ->
                        edit.array("line_decisions").set(index, if (purpose == "split") JsonObject().apply {
                            addProperty("item_position", index); addProperty("method", "by_quantity"); add("whole_purpose", JsonNull.INSTANCE); add("quantities", emptyTriple()); add("amounts_yuan", JsonNull.INSTANCE)
                        } else wholeDecision(index, purpose))
                    } }
                    if (choice == "split") {
                        ReviewSelect("分攤方式", decision.text("method"), linkedMapOf("by_quantity" to "依數量分攤", "explicit_amounts" to "指定各用途金額"), editable) { method -> change {
                            it.array("line_decisions")[index].asJsonObject.apply { addProperty("method", method); if (method == "explicit_amounts" && get("amounts_yuan").let { value -> value == null || value.isJsonNull }) add("amounts_yuan", emptyTriple()) }
                        } }
                        Text("各用途都需填寫；沒有請填 0。金額由中央按固定規則分配。", style = MaterialTheme.typography.bodySmall, color = MobileMuted)
                        TripleFields(decision, "quantities", "數量", "item.$index", errors, editable) { purpose, value -> change { setTriple(it.array("line_decisions")[index].asJsonObject, "quantities", purpose, value) } }
                        if (decision.text("method") == "explicit_amounts") TripleFields(decision, "amounts_yuan", "金額（元）", "item.$index", errors, editable) { purpose, value -> change { setTriple(it.array("line_decisions")[index].asJsonObject, "amounts_yuan", purpose, value) } }
                    }
                    if (editable) TextButton(onClick = { change { removeReviewItem(it, index) }; expanded = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text("移除此品項") }
                }
            }
        }
        item(key = "add-item") { TextButton(onClick = { change(::addReviewItem); expanded = "item-${candidate.array("items").size()}" }, enabled = editable && candidate.array("items").size() < 100, modifier = Modifier.heightIn(min = 48.dp)) { Text("＋ 新增品項") } }
        if (candidate.array("adjustments").size() > 0) item { Text("折扣與加收", style = MaterialTheme.typography.titleMedium) }
        itemsIndexed(candidate.array("adjustments").toList(), key = { index, _ -> "adjustment-$index" }) { index, element ->
            val adjustment = element.asJsonObject
            val included = adjustment.text("amount_effect") == "included_in_line_totals"
            val open = expanded == "adjustment-$index"
            ReviewCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(adjustment.text("raw_label").ifBlank { "折扣／加收待確認" }, style = MaterialTheme.typography.titleSmall)
                        Text("${if (adjustment.text("direction") == "add") "加收" else "折扣"} ${moneyText(adjustment["amount_yuan"])}", style = MaterialTheme.typography.bodyLarge)
                        Text(if (included) "已含行金額，不會再扣一次。" else "另列金額，依影響範圍分配。", style = MaterialTheme.typography.bodySmall, color = MobileMuted)
                    }
                    TextButton(onClick = { expanded = if (open) null else "adjustment-$index" }, modifier = Modifier.heightIn(min = 48.dp).testTag("review-adjustment-$index")) { Text(if (open) "收合" else "修改") }
                }
                errors.filterKeys { it.startsWith("adjustment.$index.") }.values.distinct().forEach { ReviewError(it) }
                if (open) {
                    fun changeAdjustment(block: (JsonObject) -> Unit) = change { block(it.getAsJsonObject("candidate").array("adjustments")[index].asJsonObject) }
                    ReviewField("名稱", adjustment.text("raw_label"), errors["adjustment.$index.raw_label"], "review-adjustment-$index-name", editable) { value -> changeAdjustment { it.addProperty("raw_label", value) } }
                    ReviewField("金額（元）", adjustment.text("amount_yuan"), errors["adjustment.$index.amount_yuan"], "review-adjustment-$index-amount", editable, true) { value -> changeAdjustment { it.addProperty("amount_yuan", value) } }
                    ReviewSelect("折扣或加收", adjustment.text("direction"), linkedMapOf("" to "待確認", "subtract" to "折扣", "add" to "加收"), editable) { value -> changeAdjustment { it.add("direction", value.takeIf(String::isNotBlank)?.let { text -> com.google.gson.JsonPrimitive(text) } ?: JsonNull.INSTANCE) } }
                    ReviewSelect("如何列在收據上", adjustment.text("amount_effect"), linkedMapOf("" to "待確認", "included_in_line_totals" to "已含在品項行金額", "separate_from_line_totals" to "另外加減總額"), editable) { value -> change { edit ->
                        edit.getAsJsonObject("candidate").array("adjustments")[index].asJsonObject.add("amount_effect", value.takeIf(String::isNotBlank)?.let { com.google.gson.JsonPrimitive(it) } ?: JsonNull.INSTANCE)
                        if (value == "separate_from_line_totals" && edit.array("adjustment_decisions").none { it.asJsonObject["adjustment_position"].asInt == index }) edit.array("adjustment_decisions").add(adjustmentDecision(index))
                    } }
                    ReviewSelect("影響範圍", adjustment.text("scope"), linkedMapOf("" to "待確認", "receipt" to "整張收據", "items" to "指定品項"), editable) { value -> changeAdjustment { it.add("scope", value.takeIf(String::isNotBlank)?.let { com.google.gson.JsonPrimitive(it) } ?: JsonNull.INSTANCE) } }
                    if (adjustment.text("scope") == "items") ReviewField("品項編號（逗號分隔）", if (adjustment["item_positions"].isJsonArray) adjustment.array("item_positions").joinToString(",") { (it.asInt + 1).toString() } else adjustment.text("item_positions"), errors["adjustment.$index.item_positions"], "review-adjustment-$index-items", editable) { value -> changeAdjustment { it.addProperty("item_positions", value) } }
                    if (!included) {
                        val decision = editor.array("adjustment_decisions").firstOrNull { it.asJsonObject["adjustment_position"].asInt == index }?.asJsonObject ?: adjustmentDecision(index)
                        ReviewSelect("用途分配", decision.text("method"), linkedMapOf("proportional_amount" to "依受影響金額比例", "explicit_amounts" to "指定各用途金額"), editable) { value -> change { edit ->
                            val target = adjustmentDecisionFor(edit, index); target.addProperty("method", value)
                            if (value == "explicit_amounts" && target["amounts_yuan"].let { it == null || it.isJsonNull }) target.add("amounts_yuan", emptyTriple())
                        } }
                        if (decision.text("method") == "explicit_amounts") TripleFields(decision, "amounts_yuan", "金額（元）", "adjustment.$index", errors, editable) { purpose, value -> change { setTriple(adjustmentDecisionFor(it, index), "amounts_yuan", purpose, value) } }
                    }
                    if (editable) TextButton(onClick = { change { removeReviewAdjustment(it, index) }; expanded = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text("移除此折扣／加收") }
                }
            }
        }
        item { TextButton(onClick = { change(::addReviewAdjustment); expanded = "adjustment-${candidate.array("adjustments").size()}" }, enabled = editable && candidate.array("adjustments").size() < 50, modifier = Modifier.heightIn(min = 48.dp)) { Text("＋ 新增折扣或加收") } }
        item(key = "completeness") {
            Row(Modifier.fillMaxWidth().clickable(enabled = editable) { change { it.addProperty("assembly_status", if (editor.text("assembly_status") == "incomplete") "unknown" else "incomplete") } }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(editor.text("assembly_status") == "incomplete", onCheckedChange = null, enabled = editable)
                Column { Text("目前缺照片或品項，先保留草稿", style = MaterialTheme.typography.bodyMedium); Text("補齊後取消勾選，再確認入帳。", style = MaterialTheme.typography.bodySmall, color = MobileMuted) }
            }
        }
    }
    preview?.let { hash -> NativeOriginalDialog(hash, images) { preview = null } }
}

@Composable private fun ReviewCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(color = Color.White, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content) }
}
@Composable private fun ReviewError(message: String) { Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
@Composable private fun ReviewField(label: String, value: String, error: String?, tag: String, enabled: Boolean, numeric: Boolean = false, change: (String) -> Unit) {
    OutlinedTextField(value, change, modifier = Modifier.fillMaxWidth().testTag(tag), label = { Text(label) }, enabled = enabled,
        isError = error != null, supportingText = error?.let { { Text(it) } }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text), shape = RoundedCornerShape(10.dp), textStyle = MaterialTheme.typography.bodyLarge)
}
@Composable private fun ReviewSelect(label: String, selected: String, options: Map<String, String>, enabled: Boolean, choose: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MobileMuted)
        Box { OutlinedButton(onClick = { open = true }, enabled = enabled, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("${options[selected] ?: "待確認"} ▾") }
            DropdownMenu(open, { open = false }) { options.forEach { (key, text) -> DropdownMenuItem(text = { Text(text) }, onClick = { choose(key); open = false }) } }
        }
    }
}
@Composable private fun TripleFields(decision: JsonObject, key: String, label: String, prefix: String, errors: Map<String, String>, enabled: Boolean, change: (String, String) -> Unit) {
    val triple = decision[key]?.takeIf { it.isJsonObject }?.asJsonObject
    purposeLabels.forEach { (purpose, name) -> ReviewField("$name $label", triple?.text(purpose).orEmpty(), errors["$prefix.$key.$purpose"], "review-$prefix-$key-$purpose", enabled, true) { change(purpose, it) } }
}
internal fun purposeSummary(decision: JsonObject): String = if (decision.text("method") == "whole_line") purposeLabels[decision.text("whole_purpose")] ?: "用途待確認" else if (decision.text("method") == "explicit_amounts") "按金額分攤" else "按數量分攤"
private fun emptyTriple() = JsonObject().apply { purposeLabels.keys.forEach { addProperty(it, "") } }
private fun setTriple(decision: JsonObject, key: String, purpose: String, value: String) {
    val triple = decision[key]?.takeIf { it.isJsonObject }?.asJsonObject ?: emptyTriple().also { decision.add(key, it) }
    triple.addProperty(purpose, value)
}
private fun adjustmentDecision(index: Int) = JsonObject().apply { addProperty("adjustment_position", index); addProperty("method", "proportional_amount"); add("amounts_yuan", JsonNull.INSTANCE) }
private fun adjustmentDecisionFor(editor: JsonObject, index: Int): JsonObject = editor.array("adjustment_decisions").firstOrNull { it.asJsonObject["adjustment_position"].asInt == index }?.asJsonObject ?: adjustmentDecision(index).also { editor.array("adjustment_decisions").add(it) }
internal fun addReviewItem(editor: JsonObject) {
    val candidate = editor.getAsJsonObject("candidate"); val index = candidate.array("items").size()
    if (index >= 100) return
    candidate.array("items").add(JsonObject().apply { listOf("raw_name", "quantity", "printed_unit_price_yuan", "printed_line_total_yuan", "brand", "specification", "content_quantity").forEach { add(it, JsonNull.INSTANCE) } })
    editor.array("line_decisions").add(wholeDecision(index, "self"))
}
internal fun removeReviewItem(editor: JsonObject, index: Int) {
    val candidate = editor.getAsJsonObject("candidate"); candidate.array("items").remove(index)
    editor.add("line_decisions", JsonArray().apply { editor.array("line_decisions").filter { it.asJsonObject["item_position"].asInt != index }.forEach { value -> val decision = value.asJsonObject; if (decision["item_position"].asInt > index) decision.addProperty("item_position", decision["item_position"].asInt - 1); add(decision) } })
    candidate.array("adjustments").forEach { value -> val adjustment = value.asJsonObject
        if (adjustment["item_positions"].isJsonArray) {
            adjustment.add("item_positions", JsonArray().apply { adjustment.array("item_positions").filter { it.asInt != index }.forEach { add(if (it.asInt > index) it.asInt - 1 else it.asInt) } })
            if (adjustment.text("scope") == "items" && adjustment.array("item_positions").size() == 0) adjustment.add("scope", JsonNull.INSTANCE)
        }
    }
}
internal fun addReviewAdjustment(editor: JsonObject) {
    val candidate = editor.getAsJsonObject("candidate"); val index = candidate.array("adjustments").size(); if (index >= 50) return
    candidate.array("adjustments").add(JsonObject().apply { add("raw_label", JsonNull.INSTANCE); addProperty("direction", "subtract"); add("amount_yuan", JsonNull.INSTANCE); addProperty("amount_effect", "separate_from_line_totals"); addProperty("scope", "receipt"); add("item_positions", JsonArray()) })
    editor.array("adjustment_decisions").add(adjustmentDecision(index))
}
internal fun removeReviewAdjustment(editor: JsonObject, index: Int) {
    editor.getAsJsonObject("candidate").array("adjustments").remove(index)
    editor.add("adjustment_decisions", JsonArray().apply { editor.array("adjustment_decisions").filter { it.asJsonObject["adjustment_position"].asInt != index }.forEach { value -> val decision = value.asJsonObject; if (decision["adjustment_position"].asInt > index) decision.addProperty("adjustment_position", decision["adjustment_position"].asInt - 1); add(decision) } })
}
@Composable internal fun NativeOriginalDialog(hash: String, images: ImageStore, close: () -> Unit) {
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = MobileGround, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("對照原圖", style = MaterialTheme.typography.titleLarge)
                if (images.file(hash).isFile) EvidencePhoto(hash, images, Modifier.weight(1f).fillMaxWidth(), zoomable = true)
                else Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text("此裝置未保存原圖。帳目與核對內容仍可在 App 查看。", color = MobileMuted) }
                OutlinedButton(onClick = close, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(10.dp)) { Text("返回核對") }
            }
        }
    }
}
