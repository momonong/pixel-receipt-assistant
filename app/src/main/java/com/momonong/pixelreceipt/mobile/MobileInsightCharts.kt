package com.momonong.pixelreceipt.mobile

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.time.LocalDate
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.hypot

// Identity belongs to the category, rather than its current ranking. Text/amounts also identify every mark.
private fun insightCategoryColor(key: String): Color = when (key) {
    "food" -> MobileTeal
    "clothing" -> Color(0xFF4876A1)
    "housing" -> Color(0xFFA07830)
    "transport" -> Color(0xFF58918D)
    "education" -> Color(0xFF345773)
    "leisure" -> Color(0xFF795B27)
    "health" -> Color(0xFF38776B)
    "household" -> Color(0xFF657F8A)
    "other" -> Color(0xFF4B6164)
    "unknown" -> Color(0xFF3F534A)
    else -> Color(0xFF77857E)
}

@Composable
internal fun InsightCompositionCard(report: InsightReport, apply: (InsightQuery) -> Unit) {
    val dining = report.query.category == "food"
    val dimension = if (dining) "merchants" else "categories"
    val rows = report.groups[dimension].orEmpty()
    val total = report.summary.getValue("personal_yuan")
    val chart = remember(rows, total, dimension) { insightComposition(rows, total, dimension) }
    val merchantPalette = listOf(MobileTeal, Color(0xFF4876A1), Color(0xFFA07830), Color(0xFF58918D), Color(0xFF4B6164))
    val colors = chart.slices.mapIndexed { index, slice -> slice.key to when {
        !dining -> insightCategoryColor(slice.key)
        slice.key == INSIGHT_REMAINDER -> insightCategoryColor(INSIGHT_REMAINDER)
        slice.members.singleOrNull()?.label.isNullOrBlank() -> insightCategoryColor("unknown")
        else -> merchantPalette[index]
    } }.toMap()
    var selectedKey by rememberSaveable(report.query, rows) { mutableStateOf(chart.slices.firstOrNull()?.key) }
    var remainingOpen by rememberSaveable(report.query) { mutableStateOf(false) }
    val selected = chart.slices.firstOrNull { it.key == selectedKey } ?: chart.slices.firstOrNull()
    val largeText = LocalDensity.current.fontScale > 1.3f
    LedgerCard(Modifier.testTag("insight-composition-card")) {
        Text(if (dining) "餐飲店家占比" else "支出組成", style = MaterialTheme.typography.titleMedium)
        if (dining) Text("以目前日期與篩選內的餐飲支出為準。", style = MaterialTheme.typography.bodySmall, color = MobileMuted)
        when (chart.kind) {
            InsightCompositionKind.Ring -> {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(if (largeText) 188.dp else 164.dp), contentAlignment = Alignment.Center) {
                        Canvas(Modifier.fillMaxSize().testTag("insight-composition-chart")
                            .semantics {
                                contentDescription = "${if (dining) "餐飲店家" else "生活分類"}圓環圖，${chart.slices.size} 個區塊。金額與比例列在下方，可查看帳目。"
                                stateDescription = selected?.let { "${it.label}，${insightMoney(it.amount)}，${insightPercent(it.amount, total)}" }.orEmpty()
                            }
                            .pointerInput(chart.slices, total) {
                                detectTapGestures { tap ->
                                    val x = tap.x - size.width / 2f
                                    val y = tap.y - size.height / 2f
                                    val radius = minOf(size.width, size.height) / 2f - 14.dp.toPx()
                                    if (hypot(x, y) < radius - 24.dp.toPx() || hypot(x, y) > radius + 24.dp.toPx()) return@detectTapGestures
                                    val angle = ((Math.toDegrees(atan2(y.toDouble(), x.toDouble())) + 450) % 360)
                                    var end = 0.0
                                    chart.slices.firstOrNull {
                                        end += it.amount.toDouble() / total * 360
                                        angle < end
                                    }?.let { selectedKey = it.key }
                                }
                            }) {
                            val inset = 14.dp.toPx()
                            var start = -90f
                            chart.slices.forEach { slice ->
                                val sweep = (slice.amount.toDouble() / total * 360).toFloat()
                                val gap = if (chart.slices.size == 1) 0f else minOf(0.8f, sweep / 20)
                                drawArc(colors.getValue(slice.key), start + gap / 2, sweep - gap, false,
                                    Offset(inset, inset), Size(size.width - 2 * inset, size.height - 2 * inset),
                                    style = Stroke(if (slice.key == selected?.key) 17.dp.toPx() else 11.dp.toPx(), cap = StrokeCap.Butt))
                                start += sweep
                            }
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = if (largeText) 132.dp else 116.dp)) {
                            Text(if (dining) "占目前餐飲" else selected?.label.orEmpty(), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(selected?.let { insightPercent(it.amount, total) }.orEmpty(),
                                style = MaterialTheme.typography.headlineSmall.copy(fontFeatureSettings = "tnum"))
                        }
                    }
                }
                if (dining) Text(selected?.label.orEmpty(), modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(if (dining) "點選店家，查看餐飲品項與原始帳目" else "點選分類，查看對應帳目", color = MobileMuted, style = MaterialTheme.typography.bodySmall)
                chart.slices.forEach { slice ->
                    val target = slice.members.singleOrNull()?.let { insightDrillQuery(report.query, dimension, it) }
                    val legendLabel = if (slice.key == INSIGHT_REMAINDER) "${slice.label}・${if (remainingOpen) "收起" else "展開"}" else slice.label
                    InsightLegendRow(legendLabel, slice.amount, insightPercent(slice.amount, total), colors.getValue(slice.key),
                        Modifier.testTag("insight-slice-${slice.key}"), if (slice.key == INSIGHT_REMAINDER || target != null) ({
                        selectedKey = slice.key
                        if (slice.key == INSIGHT_REMAINDER) remainingOpen = !remainingOpen
                        else target?.let(apply)
                    }) else null)
                    if (slice.key == INSIGHT_REMAINDER && remainingOpen) {
                        slice.members.forEach { group ->
                            val memberQuery = insightDrillQuery(report.query, dimension, group)
                            InsightLegendRow(insightGroupLabel(group, dimension), group.personal,
                                insightPercent(group.personal, total), insightCategoryColor(INSIGHT_REMAINDER),
                                Modifier.padding(start = 16.dp).testTag("insight-category-${group.key}"),
                                memberQuery?.let { target -> ({ apply(target) }) })
                        }
                    }
                }
            }
            InsightCompositionKind.Zero -> {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.size(112.dp).semantics { contentDescription = "個人支出為零，沒有比例區塊。" }) {
                        drawCircle(MobileRule, radius = size.minDimension / 2 - 8.dp.toPx(), style = Stroke(8.dp.toPx()))
                    }
                    Text("0%", color = MobileMuted, style = MaterialTheme.typography.titleLarge)
                }
                Text(if (report.summary.getValue("receipt_count") == 0L) "這個範圍還沒有已入帳帳目。"
                    else "已有帳目，個人支出為零；代買另列。", color = MobileMuted)
            }
            InsightCompositionKind.Signed -> {
                Text("含待分配調整，改用正負長條呈現，金額仍計入總額。", color = MobileMuted, style = MaterialTheme.typography.bodyMedium)
                val low = minOf(0L, rows.minOfOrNull { it.personal } ?: 0L)
                val high = maxOf(0L, rows.maxOfOrNull { it.personal } ?: 0L)
                rows.sortedByDescending { it.personal }.forEach { row ->
                    InsightRankingRow(row, dimension, low, high, report.query, apply)
                }
            }
            InsightCompositionKind.Inconsistent -> Text("組成資料尚未對齊，請更新分析後再查看比例。", color = MobileWarning)
        }
        when {
            report.query.category == null -> TextButton(onClick = { apply(report.query.copy(category = "food")) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("insight-food-open")) { Text("餐飲都花在哪？ ›") }
            dining && report.query.merchantKey != null -> TextButton(onClick = { apply(report.query.copy(merchantKey = null)) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("insight-merchant-clear")) { Text("查看目前範圍的全部店家") }
            dining -> TextButton(onClick = { apply(report.query.copy(category = null)) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("insight-food-clear")) { Text("返回分類占比") }
        }
    }
}

@Composable
private fun InsightLegendRow(label: String, amount: Long, percent: String, color: Color,
    modifier: Modifier = Modifier, open: (() -> Unit)?) {
    Row(modifier.fillMaxWidth().heightIn(min = 52.dp)
        .then(if (open == null) Modifier else Modifier.clickable(onClickLabel = "查看 $label", onClick = open))
        .semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Canvas(Modifier.size(10.dp)) { drawCircle(color) }
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Column(horizontalAlignment = Alignment.End) {
            Text(insightMoney(amount), style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"))
            Text(percent, color = MobileMuted, style = MaterialTheme.typography.bodySmall)
        }
        if (open != null) Text("›", color = MobileMuted)
    }
}

@Composable
internal fun InsightTrendCard(report: InsightReport, apply: (InsightQuery) -> Unit) {
    val points = report.series
    val axis = remember(report) { insightTimeAxis(report) }
    val ticks = remember(points) { insightAxisTicks(points.maxOfOrNull { it.personal } ?: 0L) }
    var selectedIndex by rememberSaveable(report.query, points) { mutableIntStateOf(points.lastIndex) }
    val selected = points.getOrNull(selectedIndex)
    val scale = LocalDensity.current.fontScale
    val tickStyle = MaterialTheme.typography.labelMedium
    val textMeasurer = rememberTextMeasurer()
    val tickWidth = with(LocalDensity.current) { ticks.maxOf { textMeasurer.measure(insightTickLabel(it), tickStyle).size.width }.toDp() }
    val tickHeight = textMeasurer.measure("0", tickStyle).size.height
    LedgerCard(Modifier.testTag("insight-trend-card")) {
        Text("支出趨勢", style = MaterialTheme.typography.titleMedium)
        val bucketLabel = when (report.bucket) { "month" -> "每月"; "year" -> "每年"; else -> "每日" }
        Text("${bucketLabel}個人支出・單位：元", style = MaterialTheme.typography.bodySmall, color = MobileMuted)
        if (axis != null && points.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().height(if (points.size == 1) 108.dp else 176.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.width(tickWidth).fillMaxHeight().padding(vertical = 1.dp), verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.End) {
                    ticks.reversed().forEach { Text(insightTickLabel(it), style = MaterialTheme.typography.labelMedium, color = MobileMuted) }
                }
                Canvas(Modifier.weight(1f).fillMaxHeight().testTag("insight-trend-chart")
                    .semantics { contentDescription = "個人支出折線圖，${points.size} 個日期區間；下方提供日期選取及精確金額。" }
                    .pointerInput(points, axis) {
                        detectTapGestures { tap -> axis.nearest(points, tap.x / size.width.coerceAtLeast(1))?.let { selectedIndex = it } }
                    }) {
                    val top = tickHeight / 2f + 1.dp.toPx()
                    val bottom = size.height - top
                    val height = bottom - top
                    fun position(point: InsightGroup) = Offset(axis.fraction(point) * size.width,
                        bottom - (point.personal.toDouble() / ticks.last().toDouble() * height).toFloat())
                    repeat(ticks.size) { index ->
                        val y = top + height * index / ticks.lastIndex
                        drawLine(MobileRule, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                    }
                    if (points.size > 1) {
                        val path = Path()
                        points.forEachIndexed { index, point ->
                            val at = position(point)
                            if (index == 0) path.moveTo(at.x, at.y) else path.lineTo(at.x, at.y)
                        }
                        drawPath(path, MobileTeal, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
                    }
                    selected?.let { point ->
                        val at = position(point)
                        drawLine(MobileTeal.copy(alpha = 0.25f), Offset(at.x, top), Offset(at.x, bottom), 1.dp.toPx())
                        drawCircle(Color.White, 6.dp.toPx(), at)
                        drawCircle(MobileTeal, 4.dp.toPx(), at)
                    }
                }
            }
            Row(Modifier.fillMaxWidth()) {
                val dates = if (axis.start == axis.end) listOf(axis.start) else listOf(axis.start,
                    axis.start.plusDays((axis.end.toEpochDay() - axis.start.toEpochDay()) / 2), axis.end).distinct()
                Spacer(Modifier.width(tickWidth + 8.dp))
                Row(Modifier.weight(1f), horizontalArrangement = if (dates.size == 1) Arrangement.Center else Arrangement.SpaceBetween) {
                    dates.forEach { Text(insightAxisDate(it, axis), color = MobileMuted, style = MaterialTheme.typography.labelMedium) }
                }
            }
            if (points.size == 1) Text("目前只有一個日期區間，更多帳目才能看出變化。", color = MobileMuted, style = MaterialTheme.typography.bodySmall)
            selected?.let { point ->
                Column(Modifier.fillMaxWidth().testTag("insight-trend-selection")
                    .semantics { stateDescription = "${spendingDateLabel(point.from, point.to)}，${insightMoney(point.personal)}，${point.receipts} 筆" }) {
                    Text(spendingDateLabel(point.from, point.to), style = MaterialTheme.typography.bodyMedium)
                    Text("${insightMoney(point.personal)}・${point.receipts} 筆", style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"))
                }
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { selectedIndex-- }, enabled = selectedIndex > 0,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("insight-trend-previous")) { Text("‹ 前一筆") }
                    TextButton(onClick = { selectedIndex++ }, enabled = selectedIndex < points.lastIndex,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("insight-trend-next")) { Text("下一筆 ›") }
                }
                TextButton(onClick = { insightDrillQuery(report.query, "series", point)?.let(apply) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = if (scale > 1.3f) 56.dp else 48.dp).testTag("insight-trend-open")) {
                    Text("查看這個日期的帳目 ›")
                }
            }
        } else {
            Canvas(Modifier.fillMaxWidth().height(48.dp).semantics { contentDescription = "沒有可放到時間軸的支出。" }) {
                drawLine(MobileRule, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1.dp.toPx())
            }
            Text("尚無含日期的已入帳支出。", color = MobileMuted)
        }
        val undated = report.coverage.getOrDefault("undated_personal_yuan", 0L)
        val undatedCount = report.coverage.getOrDefault("undated_receipt_count", 0L)
        if (undatedCount > 0) Text("${undatedCount} 筆缺日期，${insightMoney(undated)} 已計入總額，尚未放入折線圖。",
            style = MaterialTheme.typography.bodySmall, color = MobileWarning)
    }
}

@Composable
internal fun InsightRankingRow(group: InsightGroup, dimension: String, low: Long, high: Long,
    query: InsightQuery, apply: (InsightQuery) -> Unit, shareTotal: Long? = null) {
    val label = insightGroupLabel(group, dimension)
    val target = insightDrillQuery(query, dimension, group)
    val fontScale = LocalDensity.current.fontScale
    Column(Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("insight-rank-${group.key}")
        .then(if (target == null) Modifier else Modifier.clickable(onClickLabel = "查看 $label 的帳目") { apply(target) })
        .padding(vertical = 8.dp).semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BoxWithConstraints {
            if (maxWidth < 300.dp || fontScale > 1.3f) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(label, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(insightMoney(group.personal), style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"))
            } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(insightMoney(group.personal), style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"))
            }
        }
        shareTotal?.takeIf { it > 0 && group.personal >= 0 }?.let {
            Text("占目前餐飲 ${insightPercent(group.personal, it)}", style = MaterialTheme.typography.bodySmall, color = MobileMuted)
        }
        Canvas(Modifier.fillMaxWidth().height(8.dp).testTag("insight-rank-bar-${group.key}")) {
            val span = high.toDouble() - low.toDouble()
            if (span > 0) {
                val zero = (-low.toDouble() / span * size.width).toFloat()
                val end = ((group.personal.toDouble() - low.toDouble()) / span * size.width).toFloat()
                if (low < 0) drawLine(MobileMuted, Offset(zero, 0f), Offset(zero, size.height), 1.dp.toPx())
                if (group.personal != 0L) drawRect(if (group.personal < 0) MobileWarning else MobileTeal,
                    Offset(minOf(zero, end), 0f), Size(kotlin.math.abs(end - zero), size.height))
            }
        }
        if (group.receipts > 0) Text("${group.receipts} 筆${group.meals?.takeIf { it > 0 }?.let { "・$it 餐" }.orEmpty()}${group.average?.let { "・${if (dimension == "meals") "每餐" else "每筆"}平均 NT$ $it" }.orEmpty()}",
            style = MaterialTheme.typography.bodySmall, color = MobileMuted)
    }
}

private fun insightTickLabel(value: BigInteger): String = when {
    value >= BigInteger.valueOf(100_000_000) -> BigDecimal(value).divide(BigDecimal.valueOf(100_000_000), 1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + "億"
    value >= BigInteger.valueOf(10_000) -> BigDecimal(value).divide(BigDecimal.valueOf(10_000), 1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + "萬"
    else -> java.text.NumberFormat.getIntegerInstance(Locale.TAIWAN).format(value)
}

private fun insightAxisDate(date: LocalDate, axis: InsightTimeAxis): String =
    if (axis.start.year != axis.end.year) "${date.year}/${date.monthValue}" else "${date.monthValue}/${date.dayOfMonth}"
