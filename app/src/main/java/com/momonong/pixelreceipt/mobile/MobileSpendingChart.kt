package com.momonong.pixelreceipt.mobile

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

internal fun spendingMoney(value: Long): String = "NT$ ${String.format(Locale.TAIWAN, "%,d", value)}"
internal fun spendingDateLabel(from: LocalDate?, to: LocalDate?): String = when {
    from == null -> "全部日期"
    from == to -> "${from.year}年${from.monthValue}月${from.dayOfMonth}日"
    from.year == to!!.year -> "${from.year}年 ${from.monthValue}/${from.dayOfMonth}–${to.monthValue}/${to.dayOfMonth}"
    else -> "${from.year}/${from.monthValue}/${from.dayOfMonth}–${to.year}/${to.monthValue}/${to.dayOfMonth}"
}

/** Drawing ratios never author ledger amounts. Missing calendar buckets occupy their true date positions. */
@Composable
internal fun SpendingChart(report: SpendingReport, selection: SpendingSelection, select: (SpendingSelection) -> Unit) {
    val points = report.series
    val active = points.indexOfFirst { it.from == selection.from && it.to == selection.to }
    val selected = points.getOrNull(active)
    val start = report.query.dateFrom ?: points.firstOrNull()?.from ?: LocalDate.now()
    val end = report.query.dateTo ?: points.lastOrNull()?.to ?: start
    val span = (end.toEpochDay() - start.toEpochDay() + 1).coerceAtLeast(1)
    val peak = points.maxOfOrNull { it.personalYuan } ?: 0L
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("支出趨勢", style = MaterialTheme.typography.titleMedium)
        Text(when (report.bucket) { "month" -> "每月個人支出"; "year" -> "每年個人支出"; else -> "每日個人支出" }, style = MaterialTheme.typography.bodySmall, color = MobileMuted)
        if (points.isEmpty()) {
            Text("這段期間還沒有含日期的已入帳消費。", style = MaterialTheme.typography.bodyLarge, color = MobileMuted)
        } else {
            Row(Modifier.fillMaxWidth().height(150.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                    Text(String.format(Locale.TAIWAN, "%,d", peak), style = MaterialTheme.typography.labelSmall, color = MobileMuted)
                    Text("0", style = MaterialTheme.typography.labelSmall, color = MobileMuted)
                }
                Canvas(Modifier.weight(1f).fillMaxHeight().testTag("spending-chart")
                    .semantics { contentDescription = "個人支出趨勢，${points.size} 個有帳目的日期區間，最高 ${spendingMoney(peak)}。下方可逐筆查看。" }
                    .pointerInput(points, start, end) {
                        detectTapGestures { tap ->
                            val day = start.toEpochDay().toDouble() + (tap.x / size.width).coerceIn(0f, 1f) * span
                            points.minByOrNull { abs((it.from.toEpochDay() + it.to.toEpochDay()) / 2.0 - day) }
                                ?.let { select(SpendingSelection(it.from, it.to)) }
                        }
                    }) {
                    val bottom = size.height - 2.dp.toPx()
                    repeat(4) { grid ->
                        val y = bottom * grid / 3f
                        drawLine(MobileRule, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                    }
                    points.forEachIndexed { index, point ->
                        val left = ((point.from.toEpochDay() - start.toEpochDay()).toDouble() / span * size.width).toFloat()
                        val right = ((point.to.toEpochDay() - start.toEpochDay() + 1).toDouble() / span * size.width).toFloat()
                        val width = (right - left).coerceAtMost(28.dp.toPx()).coerceAtLeast(2.dp.toPx())
                        val x = ((left + right - width) / 2).coerceIn(0f, (size.width - width).coerceAtLeast(0f))
                        val height = if (peak == 0L) 0f else (point.personalYuan.toDouble() / peak * (bottom - 5.dp.toPx())).toFloat()
                        if (height > 0) drawRoundRect(if (index == active) MobileTealInk else MobileTeal, Offset(x, bottom - height), Size(width, height), CornerRadius(3.dp.toPx()))
                        if (index == active) drawCircle(MobileTealInk, 4.dp.toPx(), Offset(x + width / 2, bottom - height))
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(chartDate(start, report.bucket), style = MaterialTheme.typography.labelSmall, color = MobileMuted)
                Text(chartDate(end, report.bucket), style = MaterialTheme.typography.labelSmall, color = MobileMuted)
            }
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(selected?.let { spendingDateLabel(it.from, it.to) } ?: "點圖表或逐筆查看日期", style = MaterialTheme.typography.bodyMedium, color = MobileTealInk)
                if (selected != null) Text("${spendingMoney(selected.personalYuan)} · ${selected.receiptCount} 筆", style = MaterialTheme.typography.titleSmall)
            }
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { points[if (active < 0) points.lastIndex else (active - 1).coerceAtLeast(0)].let { select(SpendingSelection(it.from, it.to)) } },
                    enabled = active != 0, modifier = Modifier.heightIn(min = 48.dp).testTag("spending-chart-previous")) { Text("‹ 前一筆") }
                TextButton(onClick = { points[if (active < 0) 0 else (active + 1).coerceAtMost(points.lastIndex)].let { select(SpendingSelection(it.from, it.to)) } },
                    enabled = active != points.lastIndex, modifier = Modifier.heightIn(min = 48.dp).testTag("spending-chart-next")) { Text("下一筆 ›") }
            }
        }
    }
}

private fun chartDate(date: LocalDate, bucket: String): String = when (bucket) {
    "year" -> "${date.year}年"
    "month" -> "${date.year}/${date.monthValue}"
    else -> date.format(DateTimeFormatter.ofPattern("M/d"))
}
