package com.momonong.pixelreceipt.mobile

import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger
import java.time.LocalDate

class MobileInsightChartDataTest {
    private fun row(key: String, amount: Long) = InsightGroup(key, null, amount, 1, 1)
    private fun date(value: String) = LocalDate.parse(value)

    @Test fun compositionUsesExactPersonalAmountsAndKeepsUnknownOutsideRemainder() {
        val rows = listOf(row("food", 100), row("clothing", 90), row("housing", 80), row("transport", 70),
            row("education", 60), row("leisure", 50), row("other", 40), row("unknown", 1))
        val chart = insightComposition(rows, 491)
        assertEquals(InsightCompositionKind.Ring, chart.kind)
        assertEquals(5, chart.slices.size)
        assertEquals(491L, chart.slices.sumOf { it.amount })
        assertTrue(chart.slices.any { it.key == "unknown" && it.amount == 1L })
        val remainder = chart.slices.single { it.key == INSIGHT_REMAINDER }
        assertEquals(setOf("transport", "education", "leisure", "other"), remainder.members.map { it.key }.toSet())
        assertFalse(remainder.key in insightCategories)
        assertEquals(rows.map { it.key }.toSet(), chart.slices.flatMap { it.members }.map { it.key }.toSet())
    }

    @Test fun negativeAdjustmentDoesNotBecomeFalsePieShare() {
        val chart = insightComposition(listOf(row("food", 120), row("unallocated", -20)), 100)
        assertEquals(InsightCompositionKind.Signed, chart.kind)
        assertTrue(chart.slices.isEmpty())
    }

    @Test fun zeroAndInconsistentTotalsDoNotDrawFalseRing() {
        assertEquals(InsightCompositionKind.Zero, insightComposition(listOf(row("food", 0)), 0).kind)
        assertEquals(InsightCompositionKind.Inconsistent, insightComposition(listOf(row("food", 20)), 10).kind)
        assertEquals(InsightCompositionKind.Inconsistent,
            insightComposition(listOf(row("food", Long.MAX_VALUE), row("other", Long.MAX_VALUE)), -2).kind)
    }

    @Test fun hugeAndTinyAmountsStayExactWhileRatiosAreOnlyDisplay() {
        val rows = listOf(row("food", Long.MAX_VALUE - 1), row("unknown", 1))
        val chart = insightComposition(rows, Long.MAX_VALUE)
        assertEquals(InsightCompositionKind.Ring, chart.kind)
        assertEquals(Long.MAX_VALUE, chart.slices.sumOf { it.amount })
        assertEquals("<0.1%", insightPercent(1, Long.MAX_VALUE))
        assertEquals("33.3%", insightPercent(1, 3))
        assertEquals("0%", insightPercent(0, 3))
        assertEquals("—", insightPercent(0, 0))
    }

    @Test fun ringDoesNotAddZeroWedgesAndKeepsTrueOtherCategoryDrillable() {
        val chart = insightComposition(listOf(row("other", 50), row("unknown", 0)), 50)
        assertEquals(listOf("other"), chart.slices.map { it.key })
        assertEquals("other", insightDrillQuery(InsightQuery(), "categories", chart.slices.single().members.single())?.category)
    }

    @Test fun everyDrillRetainsOtherQueryFiltersAndMonthUsesFullBucket() {
        val query = InsightQuery(dateFrom = date("2026-01-01"), dateTo = date("2026-12-31"),
            compareFrom = date("2025-01-01"), compareTo = date("2025-12-31"), meal = "lunch", cityKey = "a".repeat(64))
        assertEquals(query.copy(category = "food"), insightDrillQuery(query, "categories", row("food", 1)))
        assertEquals(query.copy(merchantKey = "b".repeat(64)), insightDrillQuery(query, "merchants", row("b".repeat(64), 1)))
        val month = row("2026-11", 1).copy(from = date("2026-11-01"), to = date("2026-11-30"))
        assertEquals(query.copy(dateFrom = month.from, dateTo = month.to, compareFrom = null, compareTo = null),
            insightDrillQuery(query, "series", month))
        assertNull(insightDrillQuery(query, "categories", row("unallocated", -1)))
        assertNull(insightDrillQuery(query, "categories", row(INSIGHT_REMAINDER, 1)))
    }

    @Test fun sparseDatesUseCalendarSpacingRatherThanEqualIndexSpacing() {
        val start = date("2026-11-01")
        val axis = InsightTimeAxis(start, date("2026-11-30"))
        val points = listOf(1, 2, 30).map { d -> row("$d", d.toLong()).copy(from = start.withDayOfMonth(d), to = start.withDayOfMonth(d)) }
        assertEquals(0.5f / 30, axis.fraction(points[0]), 0.00001f)
        assertEquals(1.5f / 30, axis.fraction(points[1]), 0.00001f)
        assertEquals(29.5f / 30, axis.fraction(points[2]), 0.00001f)
        assertEquals(2, axis.nearest(points, 0.9f))
        assertNull(axis.nearest(emptyList(), 0.2f))
    }

    @Test fun oneDayIsCenteredAndAxisStaysFiniteAtMoneyLimit() {
        val day = date("2026-11-01")
        assertEquals(0.5f, InsightTimeAxis(day, day).fraction(row("one", 1).copy(from = day, to = day)))
        val ticks = insightAxisTicks(Long.MAX_VALUE)
        assertEquals(BigInteger.ZERO, ticks.first())
        assertTrue(ticks.last() >= BigInteger.valueOf(Long.MAX_VALUE))
        assertTrue(ticks.last().toDouble().isFinite())
        assertEquals(1, ticks.zipWithNext().map { (a,b) -> b-a }.distinct().size)
        assertTrue(insightAxisTicks(0).last().signum() > 0)
    }

    @Test fun diningFixtureUsesFoodBurdenRatherThanGrossReceiptsOrAdvances() {
        val raw = javaClass.getResource("/insights-dining-synthetic.json")!!.readText()
        val query = insightQueryFromJson(jsonObject(raw).getAsJsonObject("filters").toString())
        val report = parseInsightReport(raw, query)
        assertEquals("food", query.category)
        assertEquals(1265L, report.summary.getValue("personal_yuan"))
        assertEquals(90L, report.summary.getValue("advance_yuan"))
        val merchants = report.groups.getValue("merchants")
        val chart = insightComposition(merchants, 1265, "merchants")
        assertEquals(InsightCompositionKind.Ring, chart.kind)
        assertEquals(1265L, chart.slices.sumOf { it.amount })
        val top = merchants.maxBy { it.personal }
        assertEquals(720L, top.personal)
        assertEquals("56.9%", insightPercent(top.personal, 1265))
        assertEquals(65L, merchants.single { it.label == "合成示例・綜合商店" }.personal)
        assertEquals(query.copy(merchantKey = top.key), insightDrillQuery(query, "merchants", top))
    }

    @Test fun unknownMerchantStaysExplicitInLargeTailsAndRemainderCannotBecomeCategory() {
        val known = ('a'..'f').mapIndexed { index, c -> InsightGroup(c.toString().repeat(64), "合成店家$c", 10L-index, 1, 1) }
        val unknown = InsightGroup("0".repeat(64), null, 1, 1, 1)
        val chart = insightComposition(known + unknown, 46, "merchants")
        assertEquals(5, chart.slices.size)
        assertEquals(46L, chart.slices.sumOf { it.amount })
        assertEquals("店家待補", chart.slices.single { it.key == unknown.key }.label)
        assertTrue(chart.slices.single { it.key == INSIGHT_REMAINDER }.label.endsWith("家"))
        assertEquals("0".repeat(64), insightDrillQuery(InsightQuery(category = "food"), "merchants", unknown)?.merchantKey)
    }
}
