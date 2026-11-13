package com.momonong.pixelreceipt.mobile

import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.time.LocalDate
import kotlin.math.abs

internal data class InsightSlice(val key: String, val label: String, val amount: Long, val members: List<InsightGroup>)
internal enum class InsightCompositionKind { Ring, Zero, Signed, Inconsistent }
internal data class InsightComposition(val kind: InsightCompositionKind, val slices: List<InsightSlice>)
internal const val INSIGHT_REMAINDER = "chart:remaining-categories"

/** Ratios are for drawing only. The reconciled integer projection remains the money authority. */
internal fun insightComposition(rows: List<InsightGroup>, total: Long, dimension: String = "categories"): InsightComposition {
    val sum = rows.fold(BigInteger.ZERO) { value, row -> value + BigInteger.valueOf(row.personal) }
    if (sum != BigInteger.valueOf(total) || total < 0) return InsightComposition(InsightCompositionKind.Inconsistent, emptyList())
    if (rows.any { it.personal < 0 }) return InsightComposition(InsightCompositionKind.Signed, emptyList())
    if (total == 0L) return InsightComposition(InsightCompositionKind.Zero, emptyList())
    val positive = rows.filter { it.personal > 0 }.sortedByDescending { it.personal }
    val visible = if (positive.size <= 5) positive else {
        val unknown = positive.firstOrNull { it.key == "unknown" || (dimension == "merchants" && it.key != "unallocated" && it.label.isNullOrBlank()) }
        (positive.filter { it != unknown }.take(if (unknown == null) 4 else 3) + listOfNotNull(unknown))
            .sortedByDescending { it.personal }
    }
    val remaining = positive.filter { row -> visible.none { it.key == row.key } }
    val slices = visible.map { InsightSlice(it.key, insightGroupLabel(it, dimension), it.personal, listOf(it)) }.toMutableList()
    if (remaining.isNotEmpty()) slices += InsightSlice(INSIGHT_REMAINDER, "其餘 ${remaining.size} ${if (dimension == "merchants") "家" else "類"}",
        remaining.fold(0L) { value, row -> Math.addExact(value, row.personal) }, remaining)
    return InsightComposition(InsightCompositionKind.Ring, slices)
}

internal fun insightPercent(amount: Long, total: Long): String {
    if (total <= 0 || amount < 0) return "—"
    val percent = BigDecimal.valueOf(amount).multiply(BigDecimal.valueOf(100))
        .divide(BigDecimal.valueOf(total), 1, RoundingMode.HALF_UP)
    return if (amount > 0 && percent.signum() == 0) "<0.1%" else "${percent.stripTrailingZeros().toPlainString()}%"
}

internal fun insightGroupLabel(group: InsightGroup, dimension: String): String = when {
    group.key == "unallocated" -> "待分配調整"
    dimension == "categories" -> insightCategories[group.key] ?: group.label ?: "未分類"
    dimension == "meals" -> insightMeals[group.key] ?: group.label ?: "餐別待補"
    dimension == "merchants" -> group.label?.takeIf { it.isNotBlank() } ?: "店家待補"
    else -> group.label ?: "未填資料"
}

internal fun insightDrillQuery(query: InsightQuery, dimension: String, group: InsightGroup): InsightQuery? {
    if (group.key == "unallocated") return null
    return when (dimension) {
        "categories" -> group.key.takeIf { it in insightCategories }?.let { query.copy(category = it) }
        "meals" -> group.key.takeIf { it in insightMeals }?.let { query.copy(meal = it) }
        "brands" -> query.copy(brandKey = group.key)
        "merchants" -> query.copy(merchantKey = group.key)
        "branches" -> query.copy(branchKey = group.key)
        "cities" -> query.copy(cityKey = group.key)
        "districts" -> query.copy(districtKey = group.key)
        "series" -> if (group.from == null || group.to == null) null else query.copy(
            dateFrom = group.from, dateTo = group.to, compareFrom = null, compareTo = null, undatedOnly = false)
        else -> null
    }
}

internal data class InsightTimeAxis(val start: LocalDate, val end: LocalDate) {
    fun fraction(group: InsightGroup): Float {
        val first = start.toEpochDay().toDouble()
        val span = (end.toEpochDay() - start.toEpochDay() + 1).toDouble().coerceAtLeast(1.0)
        val middle = (group.from!!.toEpochDay().toDouble() + group.to!!.toEpochDay().toDouble() + 1) / 2
        return ((middle - first) / span).toFloat().coerceIn(0f, 1f)
    }
    fun nearest(points: List<InsightGroup>, fraction: Float): Int? = points.indices.minByOrNull {
        abs(fraction(points[it]) - fraction.coerceIn(0f, 1f))
    }
}

internal fun insightTimeAxis(report: InsightReport): InsightTimeAxis? {
    val first = report.query.dateFrom ?: report.series.firstOrNull()?.from ?: return null
    val last = report.query.dateTo ?: report.series.lastOrNull()?.to ?: return null
    return InsightTimeAxis(first, last)
}

/** Nice whole-yuan tick values can exceed Long.MAX_VALUE; they are scale labels, never ledger values. */
internal fun insightAxisTicks(peak: Long): List<BigInteger> {
    val target = (BigInteger.valueOf(peak.coerceAtLeast(0)) + BigInteger.valueOf(3)) / BigInteger.valueOf(4)
    val power = BigInteger.TEN.pow((target.toString().length - 1).coerceAtLeast(0))
    val step = listOf(1, 2, 5, 10).map { power * BigInteger.valueOf(it.toLong()) }
        .first { it >= target && it.signum() > 0 }
    return (0..4).map { step * BigInteger.valueOf(it.toLong()) }
}
