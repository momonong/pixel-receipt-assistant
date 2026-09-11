package com.momonong.pixelreceipt.feature.inbox

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.momonong.pixelreceipt.domain.model.*
import com.momonong.pixelreceipt.domain.rules.PersonalExpenseCalculator
import com.momonong.pixelreceipt.domain.rules.PersonalExpenseSummary
import com.momonong.pixelreceipt.domain.usecase.*

internal fun ExpensePurpose.label(): String = when (this) {
    ExpensePurpose.Self -> "自用"
    ExpensePurpose.Advance -> "代買"
    ExpensePurpose.Gift -> "送禮"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LineExpenseControls(line: ReviewLineInput, draft: ReceiptDraft?, editable: Boolean,
    details: Boolean, changed: (ReviewLineInput) -> Unit) {
    val expense = line.expense
    val key = draft?.let { PersonalExpenseCalculator.lineBasis(it, line.id) }
    val current = key != null && expense?.basisKey == key
    Text("品項歸屬：" + when {
        expense == null -> if (editable) "待分類" else "未記錄"
        !current -> "待重新確認"
        expense.method == ExpenseSplitMethod.WholeLine -> expense.wholePurpose?.label() ?: "待分類"
        else -> "部分件數（請查看下方支出檢查）"
    }, style = MaterialTheme.typography.labelLarge)
    if (editable) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ExpensePurpose.entries.forEach { purpose ->
                FilterChip(current && expense.method == ExpenseSplitMethod.WholeLine && expense.wholePurpose == purpose,
                    { changed(line.copy(expense = ExpenseInput(ExpenseSplitMethod.WholeLine, purpose,
                        basisKey = key, confirmedAtEpochMillis = System.currentTimeMillis()))) },
                    { Text("整列${purpose.label()}") }, enabled = draft != null,
                    modifier = Modifier.testTag("purpose-${line.id}-${purpose.name}"))
            }
        }
    }
    if (details) {
        Text("自用、送禮由自己負擔；代買預期收回。這項選擇不代表辨識內容已核對正確。", style = MaterialTheme.typography.bodySmall)
        if (editable) TextButton(onClick = { changed(line.copy(expense = ExpenseInput())) }, modifier = Modifier.testTag("split-${line.id}")) { Text("同一列只有部分件數屬於自己") }
        if (expense != null && expense.method != ExpenseSplitMethod.WholeLine) {
            Text("各用途件數合計須等於購買數量；未分完會保持待分類。")
            ExpensePurpose.entries.forEach { purpose ->
                ExpenseNumber("${purpose.label()}件數", expense.quantities[purpose].orEmpty(), editable,
                    "expense-qty-${purpose.name}") { value -> changed(line.copy(expense = expense.copy(
                    quantities = expense.quantities + (purpose to value), basisKey = null, confirmedAtEpochMillis = null))) }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(ExpenseSplitMethod.ByQuantity to "按件數比例", ExpenseSplitMethod.ExplicitAmounts to "自行指定金額").forEach { (method, label) ->
                    FilterChip(expense.method == method, { changed(line.copy(expense = expense.copy(method = method, basisKey = null, confirmedAtEpochMillis = null))) },
                        { Text(label) }, enabled = editable)
                }
            }
            if (expense.method == ExpenseSplitMethod.ByQuantity) Text("僅在各件應平均負擔此列金額時使用。買一送一或各件價格不同時，請自行指定。餘數依小數餘額大小分配，同額依自用、代買、送禮順序。", style = MaterialTheme.typography.bodySmall)
            else {
                Text("填另列折扣前的行金額分配；三項合計必須等於行合計，無負擔者填 0。")
                ExpensePurpose.entries.forEach { purpose ->
                    ExpenseNumber("${purpose.label()}金額", expense.amounts[purpose].orEmpty(), editable,
                        "expense-amount-${purpose.name}") { value -> changed(line.copy(expense = expense.copy(
                        amounts = expense.amounts + (purpose to value), basisKey = null, confirmedAtEpochMillis = null))) }
                }
            }
            if (editable) OutlinedButton(onClick = { changed(line.copy(expense = expense.copy(basisKey = key, confirmedAtEpochMillis = System.currentTimeMillis()))) },
                enabled = draft != null, modifier = Modifier.testTag("accept-split-${line.id}")) { Text("確認此列分配方式") }
        }
        if (editable && expense != null) TextButton(onClick = { changed(line.copy(expense = null)) }) { Text("改回未決／待分類") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AdjustmentExpenseControls(adjustment: ReviewAdjustmentInput, draft: ReceiptDraft?, editable: Boolean,
    changed: (ReviewAdjustmentInput) -> Unit) {
    val expense = adjustment.expense
    val key = draft?.let(PersonalExpenseCalculator::adjustmentBasis)
    Text("這筆折扣／費用由誰負擔？", style = MaterialTheme.typography.titleSmall)
    Text(if (expense == null) "尚未分配" else if (expense.basisKey != key) "品項、金額或歸屬有變更，請重新確認分配。" else "已選分配方式；結果與待處理原因見個人支出。")
    if (editable) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(expense?.method == ExpenseAdjustmentMethod.ProportionalAmount,
            { changed(adjustment.copy(expense = AdjustmentExpenseInput())) }, { Text("按適用品項金額比例") })
        FilterChip(expense?.method == ExpenseAdjustmentMethod.ExplicitAmounts,
            { changed(adjustment.copy(expense = AdjustmentExpenseInput(ExpenseAdjustmentMethod.ExplicitAmounts))) }, { Text("自行指定") })
    }
    if (expense?.method == ExpenseAdjustmentMethod.ProportionalAmount) {
        Text("以適用品項的原行合計、按用途金額比例分配；多筆加減項各自使用同一基準，不連乘。只適用部分件數時須自行指定。餘數先分給餘額最大者，同額依自用、代買、送禮順序。", style = MaterialTheme.typography.bodySmall)
    }
    if (expense?.method == ExpenseAdjustmentMethod.ExplicitAmounts) ExpensePurpose.entries.forEach { purpose ->
        ExpenseNumber("${purpose.label()}加減金額", expense.amounts[purpose].orEmpty(), editable,
            "adjustment-expense-${purpose.name}") { value -> changed(adjustment.copy(expense = expense.copy(
            amounts = expense.amounts + (purpose to value), basisKey = null, confirmedAtEpochMillis = null))) }
    }
    if (editable && expense != null) OutlinedButton(onClick = { changed(adjustment.copy(expense = expense.copy(basisKey = key, confirmedAtEpochMillis = System.currentTimeMillis()))) },
        enabled = draft != null) { Text("確認這筆加減項分配") }
}

@Composable
private fun ExpenseNumber(label: String, value: String, editable: Boolean, tag: String, changed: (String) -> Unit) {
    OutlinedTextField(value, { if (it.length <= 30) changed(it) }, modifier = Modifier.fillMaxWidth().testTag(tag),
        readOnly = !editable, singleLine = true, label = { Text(label) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
}

internal fun expenseSummaryText(summary: PersonalExpenseSummary): String = if (!summary.ready) {
    "個人支出：待分類／待分配，總額尚未確定\n" + summary.pending.joinToString("\n")
} else {
    "個人支出 ${summary.personalMinor} ${summary.currency}（自用 ${summary.selfMinor} ＋ 送禮 ${summary.giftMinor}）\n" +
        "代墊／預期收回 ${summary.advanceMinor} ${summary.currency}\n" +
        "整筆付款 ${summary.receiptMinor} · 已分配 ${summary.accountedMinor}\n" +
        "未解差額（已分配 − 付款）${summary.differenceMinor}；差額不計入任何人的負擔。"
}

@Composable
internal fun ExpenseSummary(draft: ReceiptDraft, editable: Boolean) {
    Text("個人支出與代墊", style = MaterialTheme.typography.titleLarge)
    if (!editable && draft.personalExpenses.isEmpty()) Text("品項歸屬未記錄；舊交易不會自動分類。", Modifier.testTag("expense-summary"))
    else Text(expenseSummaryText(PersonalExpenseCalculator.calculate(draft)), Modifier.testTag("expense-summary"))
}
