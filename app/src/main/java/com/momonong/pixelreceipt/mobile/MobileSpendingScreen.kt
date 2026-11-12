package com.momonong.pixelreceipt.mobile

import android.app.DatePickerDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.gson.JsonObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun MobileSpendingScreen(state: MobileUiState, actions: MobileActions, modifier: Modifier, list: LazyListState) {
    val spending = state.spending
    val report = spending.report
    var breakdown by rememberSaveable { mutableStateOf(false) }
    var custom by rememberSaveable { mutableStateOf(false) }
    val pending = state.mutations.any { it.phase == "pending" && it.kind == "ledger_void" }
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(modifier) {
        val expanded = maxWidth >= 600.dp && fontScale <= 1.3f
        LazyColumn(Modifier.fillMaxSize().testTag("spending-list"), state = list,
            contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("看懂每一筆支出", style = MaterialTheme.typography.headlineMedium)
                    Text("從已確認帳目，看消費花在哪裡。", style = MaterialTheme.typography.bodyMedium, color = MobileMuted)
                }
            }
            if (!state.signedIn) {
                item { SpendingCard {
                    Text("登入後查看支出分析", style = MaterialTheme.typography.titleLarge)
                    Text("使用原本的帳本，期間、店家與帳目都在 App 內查看。", color = MobileMuted)
                    Button(onClick = actions.settings, modifier = Modifier.heightIn(min = 48.dp)) { Text("前往登入") }
                } }
            } else {
                item { SpendingPeriodControl(spending, { if (it == SpendingPeriod.Custom) custom = true else actions.spendingPeriod(it) }, actions.refreshSpending) }
                if (spending.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth(), color = MobileTeal, trackColor = MobileTealSoft) }
                if (spending.error != null) item { SpendingCard {
                    Text(spending.error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = actions.refreshSpending, enabled = !spending.loading, modifier = Modifier.heightIn(min = 48.dp).testTag("spending-retry")) { Text("重新讀取分析") }
                } }
                if (report == null && spending.error == null && !spending.loading) item { SpendingCard {
                    Text("準備讀取這段期間的支出", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = actions.refreshSpending, modifier = Modifier.heightIn(min = 48.dp).testTag("spending-retry")) { Text("讀取分析") }
                } }
                if (report != null) {
                    item {
                        SpendingOverview(report, expanded) { breakdown = true }
                    }
                    if (spending.stale || pending) item { Text(
                        if (pending) "作廢結果仍待確認，支出可能更新。" else "顯示上次保存的分析；更新後才能確認最新狀態。",
                        style = MaterialTheme.typography.bodyMedium, color = MobileWarning) }
                    if (report.excludedUndatedCount > 0) item { Text("另有 ${report.excludedUndatedCount} 筆缺日期，未列入本期；切換「全部」可查看。", color = MobileMuted, style = MaterialTheme.typography.bodySmall) }
                    item {
                        if (expanded) Row(Modifier.fillMaxWidth().testTag("spending-expanded"), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                            SpendingCard(Modifier.weight(1f)) { SpendingChart(report, spending.selection, actions.spendingSelect) }
                            SpendingCard(Modifier.weight(1f)) { SpendingMerchants(report, spending.selection, actions.spendingSelect) }
                        } else SpendingCard { SpendingChart(report, spending.selection, actions.spendingSelect) }
                    }
                    if (!expanded) item { SpendingCard { SpendingMerchants(report, spending.selection, actions.spendingSelect) } }
                    if (report.undatedCount > 0) item { SpendingCard {
                        Text("缺日期的帳目", style = MaterialTheme.typography.titleMedium)
                        Text("${report.undatedCount} 筆 · ${spendingMoney(report.undatedPersonalYuan)}，已包含在全部支出。", style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { actions.spendingSelect(SpendingSelection(undated = true)) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("查看缺日期帳目 ›") }
                    } }
                    item {
                        Column(Modifier.testTag("spending-details"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("對應帳目", style = MaterialTheme.typography.titleLarge)
                            Text(selectionLabel(report, spending.selection), style = MaterialTheme.typography.bodyMedium, color = MobileTealInk, modifier = Modifier.testTag("spending-selected"))
                            if (spending.selection != SpendingSelection()) TextButton(onClick = { actions.spendingSelect(SpendingSelection()) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("顯示本期全部帳目") }
                        }
                    }
                    if (spending.entriesLoading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    spending.entriesError?.let { error -> item { SpendingCard {
                        Text(error, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { actions.spendingSelect(spending.selection) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("重試讀取帳目") }
                    } } }
                    val entries = spending.entriesJson?.let(::jsonObject)?.array("entries")?.map { it.asJsonObject }.orEmpty()
                    if (entries.isEmpty() && !spending.entriesLoading && spending.entriesError == null) item {
                        Text(if (spending.entriesJson == null) "帳目明細尚未讀取，連線後更新分析。" else "這個範圍沒有已入帳帳目。", style = MaterialTheme.typography.bodyMedium, color = MobileMuted)
                    }
                    items(entries, key = { it.text("id") }) { entry -> SpendingEntry(entry) { actions.ledgerDetail(entry.text("id")) } }
                    if (spending.nextOffset != null) item {
                        OutlinedButton(onClick = actions.moreSpending, enabled = !spending.entriesLoading, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("載入更多帳目") }
                    }
                    item {
                        val timestamp = spending.fetchedAt?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("M/d HH:mm")) }
                        Text("${timestamp?.let { "更新於 $it · " }.orEmpty()}依有效已入帳帳目統計。已作廢與未入帳收據不計入。", style = MaterialTheme.typography.bodySmall, color = MobileMuted)
                    }
                }
            }
        }
    }
    if (breakdown && report != null && state.signedIn) SpendingBreakdown(report) { breakdown = false }
    if (custom && state.signedIn) SpendingRangeDialog(spending.query, { custom = false }) { from, to -> custom = false; actions.spendingRange(from, to) }
}

@Composable
private fun SpendingOverview(report: SpendingReport, expanded: Boolean, breakdown: () -> Unit) {
    Surface(color = MobileTealSoft, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        if (expanded) Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) { SpendingAmount(report) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) { SpendingComparisonText(report); SpendingBreakdownAction(breakdown) }
        } else Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SpendingAmount(report)
            if (report.comparison != null) { HorizontalDivider(color = Color(0xFFC4DDD6)); SpendingComparisonText(report) }
            SpendingBreakdownAction(breakdown)
        }
    }
}
@Composable private fun SpendingAmount(report: SpendingReport) {
    Text("本期個人支出", style = MaterialTheme.typography.titleSmall, color = MobileTealInk)
    Text(spendingMoney(report.summary.personalYuan), style = MaterialTheme.typography.headlineLarge.copy(fontFeatureSettings = "tnum"), fontWeight = FontWeight.SemiBold,
        color = MobileTealInk, modifier = Modifier.testTag("spending-personal"))
    Text("${report.summary.receiptCount} 筆已入帳 · 自己用與送人", style = MaterialTheme.typography.bodyMedium, color = MobileTealInk)
    if (report.summary.advanceYuan > 0) Text("代買 ${spendingMoney(report.summary.advanceYuan)} 另列", style = MaterialTheme.typography.bodyMedium, color = MobileMuted)
}
@Composable private fun SpendingComparisonText(report: SpendingReport) {
    report.comparison?.let { comparison ->
        val delta = comparison.deltaPersonalYuan
        Text("比前期${if (delta > 0) "多" else if (delta < 0) "少" else "相同"}${if (delta == 0L) "" else " ${spendingMoney(if (delta < 0) -delta else delta)}"}", style = MaterialTheme.typography.bodyLarge, color = MobileTealInk)
        Text("前期 ${spendingDateLabel(comparison.from, comparison.to)} · ${spendingMoney(comparison.personalYuan)}", style = MaterialTheme.typography.bodySmall, color = MobileMuted)
    }
}
@Composable private fun SpendingBreakdownAction(open: () -> Unit) {
    TextButton(onClick = open, modifier = Modifier.heightIn(min = 48.dp).testTag("spending-breakdown"), contentPadding = PaddingValues(0.dp)) { Text("金額怎麼算 ›") }
}

@Composable
private fun SpendingPeriodControl(state: SpendingUiState, change: (SpendingPeriod) -> Unit, refresh: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Box {
                OutlinedButton(onClick = { open = true }, modifier = Modifier.heightIn(min = 48.dp).testTag("spending-period")) { Text("${state.period.label} ▾") }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    SpendingPeriod.entries.forEach { period -> DropdownMenuItem(text = { Text(period.label) }, onClick = { open = false; change(period) }, modifier = Modifier.heightIn(min = 48.dp).testTag("spending-period-${period.name}")) }
                }
            }
            TextButton(onClick = refresh, enabled = !state.loading, modifier = Modifier.heightIn(min = 48.dp).testTag("spending-refresh")) { Text("更新分析") }
        }
        Text(spendingDateLabel(state.query.dateFrom, state.query.dateTo), style = MaterialTheme.typography.bodySmall, color = MobileMuted)
    }
}

@Composable
private fun SpendingMerchants(report: SpendingReport, selection: SpendingSelection, select: (SpendingSelection) -> Unit) {
    var shown by rememberSaveable(report.query.summaryPath()) { mutableIntStateOf(8) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("花在哪些店", style = MaterialTheme.typography.titleMedium)
        Text("本期個人支出 · 點店家查看帳目", style = MaterialTheme.typography.bodySmall, color = MobileMuted)
        if (report.merchants.isEmpty()) Text("這段期間還沒有已入帳店家。", color = MobileMuted)
        report.merchants.take(shown).forEach { shop ->
            Column(Modifier.fillMaxWidth().clickable { select(SpendingSelection(merchantKey = shop.key)) }.padding(vertical = 8.dp)
                .heightIn(min = 48.dp).testTag("spending-merchant-${shop.key}"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(shop.name ?: "未填店家", style = MaterialTheme.typography.titleSmall, color = if (selection.merchantKey == shop.key) MobileTeal else MobileInk)
                Text("${spendingMoney(shop.personalYuan)} · ${shop.receiptCount} 筆 ›", style = MaterialTheme.typography.bodyMedium, color = MobileMuted)
                val peak = report.merchants.maxOf { it.personalYuan }.coerceAtLeast(1L)
                LinearProgressIndicator(progress = { (shop.personalYuan.toDouble() / peak).toFloat() }, modifier = Modifier.fillMaxWidth().height(5.dp), color = MobileTeal, trackColor = MobileTealSoft, gapSize = 0.dp, drawStopIndicator = {})
            }
        }
        if (shown < report.merchants.size) TextButton(onClick = { shown += 8 }, modifier = Modifier.heightIn(min = 48.dp)) { Text("再顯示店家（$shown / ${report.merchants.size}）") }
    }
}

@Composable
private fun SpendingEntry(entry: JsonObject, open: () -> Unit) {
    val snapshot = entry.getAsJsonObject("snapshot")
    val candidate = snapshot.getAsJsonObject("candidate")
    val calculation = snapshot.getAsJsonObject("calculation")
    SpendingCard(Modifier.clickable(onClick = open).testTag("spending-entry-${entry.text("id")}")) {
        Text(candidate.text("merchant").ifBlank { "未填店家" }, style = MaterialTheme.typography.titleSmall)
        val date = candidate.text("receipt_date").takeIf { it.isNotBlank() }?.let(LocalDate::parse)
        Text(if (date == null) "日期待補" else spendingDateLabel(date, date), style = MaterialTheme.typography.bodySmall, color = MobileMuted)
        Text("個人支出 ${spendingMoney(strictLong(calculation["personal_yuan"]))}", style = MaterialTheme.typography.titleMedium, color = MobileTealInk)
        val advance = strictLong(calculation["advance_yuan"])
        if (advance > 0) Text("代買 ${spendingMoney(advance)} 另列", style = MaterialTheme.typography.bodySmall, color = MobileMuted)
        Text("查看品項與用途 ›", style = MaterialTheme.typography.labelLarge, color = MobileTealInk)
    }
}

private fun selectionLabel(report: SpendingReport, selection: SpendingSelection): String = when {
    selection.undated -> "缺日期的帳目"
    selection.merchantKey != null -> report.merchants.firstOrNull { it.key == selection.merchantKey }?.let { it.name ?: "未填店家" } ?: "所選店家"
    selection.from != null -> spendingDateLabel(selection.from, selection.to)
    else -> "本期全部帳目"
}

@Composable
private fun SpendingCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(color = Color.White, shape = RoundedCornerShape(20.dp), modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
private fun SpendingBreakdown(report: SpendingReport, dismiss: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text("金額怎麼算") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val s = report.summary
            listOf("自己用" to s.selfYuan, "送人" to s.giftYuan, "個人支出" to s.personalYuan, "代買（另列）" to s.advanceYuan, "收據實付" to s.printedYuan, "明列折扣" to s.discountYuan).forEach { (label, amount) ->
                Column { Text(label, style = MaterialTheme.typography.bodyMedium, color = MobileMuted); Text(spendingMoney(amount), style = MaterialTheme.typography.titleMedium) }
            }
            Text("個人支出包含自己用與送人，代買另列。折扣已反映在帳目金額，不會再扣一次；明列折扣只依收據證據統計。", style = MaterialTheme.typography.bodyMedium)
        }
    }, confirmButton = { TextButton(onClick = dismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("了解") } })
}

@Composable
private fun SpendingRangeDialog(query: SpendingQuery, dismiss: () -> Unit, apply: (LocalDate, LocalDate) -> Unit) {
    var from by rememberSaveable { mutableStateOf((query.dateFrom ?: LocalDate.now().withDayOfMonth(1)).toString()) }
    var to by rememberSaveable { mutableStateOf((query.dateTo ?: LocalDate.now()).toString()) }
    val fromDate = LocalDate.parse(from); val toDate = LocalDate.parse(to)
    var picker by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    AlertDialog(onDismissRequest = dismiss, title = { Text("自訂日期") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { picker = "from" }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("spending-date-from")) { Text("從 ${spendingDateLabel(fromDate, fromDate)}") }
            OutlinedButton(onClick = { picker = "to" }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("spending-date-to")) { Text("至 ${spendingDateLabel(toDate, toDate)}") }
            if (fromDate > toDate) Text("結束日期需在開始日期之後。", color = MaterialTheme.colorScheme.error)
        }
    }, dismissButton = { TextButton(onClick = dismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") } },
        confirmButton = { TextButton(onClick = { apply(fromDate, toDate) }, enabled = fromDate <= toDate, modifier = Modifier.heightIn(min = 48.dp).testTag("spending-date-apply")) { Text("套用期間") } })
    DisposableEffect(picker, context) {
        val dialog = picker?.let { which ->
            val initial = if (which == "from") fromDate else toDate
            DatePickerDialog(context, { _, year, month, day ->
                val date = LocalDate.of(year, month + 1, day).toString()
                if (which == "from") from = date else to = date
                picker = null
            }, initial.year, initial.monthValue - 1, initial.dayOfMonth).apply {
                setTitle(if (which == "from") "開始日期" else "結束日期")
                setButton(DatePickerDialog.BUTTON_POSITIVE, "確定", this)
                setButton(DatePickerDialog.BUTTON_NEGATIVE, "取消", this)
                setOnDismissListener { picker = null }
                show()
            }
        }
        onDispose { dialog?.dismiss() }
    }
}
