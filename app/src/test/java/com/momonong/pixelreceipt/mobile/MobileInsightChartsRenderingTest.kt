package com.momonong.pixelreceipt.mobile

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.momonong.pixelreceipt.data.ingestion.ImageStore
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.UUID
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MobileInsightChartsRenderingTest {
    @get:Rule val ui = createEmptyComposeRule()
    private lateinit var scenario: ActivityScenario<ComponentActivity>
    private lateinit var directory: File

    @Before fun setup() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        directory = File(app.cacheDir, UUID.randomUUID().toString()).apply { mkdirs() }
        shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
        scenario = ActivityScenario.launch(Intent(app, ComponentActivity::class.java))
    }

    @After fun cleanup() { scenario.close(); directory.deleteRecursively() }

    private fun report(resource: String = "/insights-charts-synthetic.json"): InsightReport {
        val raw = javaClass.getResource(resource)!!.readText()
        val q = insightQueryFromJson(jsonObject(raw).getAsJsonObject("filters").toString())
        return parseInsightReport(raw, q)
    }

    private fun render(actions: MobileActions = MobileActions(), report: InsightReport = report(), scale: Float = 1f) {
        scenario.onActivity { it.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
            MobileContent(MobileUiState(page = MobilePage.Spending, signedIn = true,
                insight = InsightUiState(enabled = true, query = report.query, report = report, stale = false)),
                ImageStore(directory), actions)
            }
        } }
        ui.waitForIdle()
    }

    private fun scroll(tag: String) {
        ui.onNodeWithTag("insight-list").performScrollToNode(hasTestTag(tag))
    }

    private fun capture(name: String) {
        val file = File(System.getProperty("pixelreceipt.screenshots"), "$name.png").apply { parentFile?.mkdirs() }
        val bitmap = ui.onRoot().captureToImage().asAndroidBitmap()
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }

    @Test fun activeMultiDimensionalScreenContainsRealCharts() {
        render()
        ui.onNodeWithTag("insight-list").performScrollToNode(hasTestTag("insight-composition-chart"))
        ui.onNodeWithTag("insight-composition-chart").assertIsDisplayed()
        ui.onNodeWithTag("insight-list").performScrollToNode(hasTestTag("insight-trend-chart"))
        ui.onNodeWithTag("insight-trend-chart").assertIsDisplayed()
    }

    @Test fun donutLegendAndRemainderKeepExactCategoriesAndExistingScope() {
        var selected: InsightQuery? = null
        val report = report()
        render(MobileActions(insightQuery = { selected = it }))
        capture("charts-overview-390")
        scroll("insight-composition-card")
        capture("charts-composition-390")
        scroll("insight-slice-food")
        ui.onNodeWithTag("insight-slice-food").performClick()
        assertEquals(report.query.copy(category = "food"), selected)
        scroll("insight-slice-$INSIGHT_REMAINDER")
        ui.onNodeWithTag("insight-slice-$INSIGHT_REMAINDER").performClick()
        val remainder = insightComposition(report.groups.getValue("categories"), 4620).slices.single { it.key == INSIGHT_REMAINDER }
        val group = remainder.members.first()
        scroll("insight-category-${group.key}")
        ui.onNodeWithTag("insight-category-${group.key}").performClick()
        assertEquals(report.query.copy(category = group.key), selected)
        assertTrue(selected!!.category in insightCategories)
    }

    @Test fun trendTouchAndAccessibleButtonsSelectActualDateBeforeDrilling() {
        var selected: InsightQuery? = null
        render(MobileActions(insightQuery = { selected = it }))
        scroll("insight-trend-card")
        capture("charts-trend-390")
        scroll("insight-trend-previous")
        ui.onNodeWithTag("insight-trend-previous").performClick()
        ui.onNodeWithText("2026年11月11日").assertExists()
        ui.onNodeWithTag("insight-trend-next").performClick()
        ui.onNodeWithText("2026年11月12日").assertExists()
        scroll("insight-trend-chart")
        ui.onNodeWithTag("insight-trend-chart").performTouchInput { click(androidx.compose.ui.geometry.Offset(1f, centerY)) }
        scroll("insight-trend-open")
        ui.onNodeWithTag("insight-trend-open").performClick()
        assertEquals(LocalDate.parse("2026-11-01"), selected?.dateFrom)
        assertEquals(selected?.dateFrom, selected?.dateTo)
        assertNull(selected?.compareFrom)
        assertNull(selected?.compareTo)
    }

    @Test fun quickRankingsLeadToMealsMerchantsAndCitiesInSamePeriod() {
        var selected: InsightQuery? = null
        val report = report()
        render(MobileActions(insightQuery = { selected = it }))
        scroll("insight-quick-meals")
        ui.onNodeWithTag("insight-quick-meals").performClick()
        scroll("insight-rank-lunch")
        capture("charts-meals-390")
        ui.onNodeWithTag("insight-rank-lunch").performClick()
        assertEquals(report.query.copy(meal = "lunch"), selected)
        scroll("insight-quick-merchants")
        ui.onNodeWithTag("insight-quick-merchants").performClick()
        val merchant = report.groups.getValue("merchants").maxBy { it.personal }
        scroll("insight-rank-${merchant.key}")
        ui.onNodeWithTag("insight-rank-${merchant.key}").performClick()
        assertEquals(report.query.copy(merchantKey = merchant.key), selected)
        scroll("insight-quick-cities")
        ui.onNodeWithTag("insight-quick-cities").performClick()
        val city = report.groups.getValue("cities").maxBy { it.personal }
        scroll("insight-rank-${city.key}")
        capture("charts-cities-390")
        ui.onNodeWithTag("insight-rank-${city.key}").performClick()
        assertEquals(report.query.copy(cityKey = city.key), selected)
    }

    @Test fun signedAdjustmentStaysVisibleOutsidePie() {
        val report = report("/insights-unallocated-http-synthetic.json")
        render(report = report)
        scroll("insight-composition-card")
        ui.onNodeWithTag("insight-composition-chart").assertDoesNotExist()
        ui.onNodeWithText("含待分配調整，改用正負長條呈現，金額仍計入總額。").assertExists()
        scroll("insight-rank-unallocated")
        ui.onNodeWithText("待分配調整").assertExists()
        capture("charts-signed-390")
    }

    @Test fun undatedBurdenIsDisclosedWithoutInventingTimePoints() {
        val report = report("/insights-charts-undated-synthetic.json")
        assertEquals(190L, report.summary.getValue("personal_yuan"))
        assertEquals(1, report.series.size)
        render(report = report)
        scroll("insight-trend-card")
        ui.onNodeWithText("1 筆缺日期，NT$ 125 已計入總額，尚未放入折線圖。").assertExists()
        ui.onNodeWithText("目前只有一個日期區間，更多帳目才能看出變化。").assertExists()
        capture("charts-undated-390")
    }

    @Test fun emptyAndAdvanceOnlyStatesDoNotInventSharesOrTrend() {
        val base = report()
        val empty = base.copy(summary = base.summary.mapValues { 0L }, groups = base.groups.mapValues { emptyList() },
            series = emptyList(), coverage = base.coverage.mapValues { 0L })
        render(report = empty)
        scroll("insight-composition-card")
        ui.onNodeWithText("這個範圍還沒有已入帳帳目。").assertExists()
        ui.onNodeWithTag("insight-composition-chart").assertDoesNotExist()
        scroll("insight-trend-card")
        ui.onNodeWithText("尚無含日期的已入帳支出。").assertExists()
        capture("charts-empty-390")
        render(report = empty.copy(summary = empty.summary + mapOf("receipt_count" to 1L, "advance_yuan" to 50L)))
        scroll("insight-composition-card")
        ui.onNodeWithText("已有帳目，個人支出為零；代買另列。").assertExists()
    }

    @Test fun sparseUnknownDataStillShowsItsActualOnePoint() {
        render(report = report("/insights-unknown-http-synthetic.json"))
        scroll("insight-composition-card")
        ui.onNodeWithTag("insight-slice-unknown").assertExists()
        scroll("insight-trend-card")
        ui.onNodeWithText("目前只有一個日期區間，更多帳目才能看出變化。").assertExists()
        capture("charts-sparse-390")
    }

    @Test @Config(qualifiers = "w320dp-h720dp-mdpi") fun compactLargeTextChartsAreReadableAndControlsReachable() {
        render(scale = 1.5f)
        scroll("insight-composition-chart")
        capture("charts-320-font150-composition")
        scroll("insight-slice-food")
        ui.onNodeWithTag("insight-slice-food").assertIsDisplayed()
        scroll("insight-trend-open")
        ui.onNodeWithTag("insight-trend-open").assertIsDisplayed()
        capture("charts-320-font150-trend")
        ui.onNodeWithTag("insight-charts-pair").assertDoesNotExist()
    }

    @Test @Config(qualifiers = "w600dp-h960dp-mdpi") fun narrowFoldableKeepsFullWidthCharts() {
        render()
        scroll("insight-trend-card")
        capture("charts-600")
        ui.onNodeWithTag("insight-charts-pair").assertDoesNotExist()
    }

    @Test @Config(qualifiers = "w840dp-h960dp-mdpi") fun unfoldedScreenPairsCharts() {
        render()
        scroll("insight-charts-pair")
        ui.onNodeWithTag("insight-composition-chart").assertIsDisplayed()
        ui.onNodeWithTag("insight-trend-chart").assertIsDisplayed()
        capture("charts-840")
    }

    @Test @Config(qualifiers = "w840dp-h960dp-mdpi") fun oddRankingRowRetainsTheSamePhysicalScale() {
        render()
        scroll("insight-rank-dinner")
        val firstWidth = ui.onNodeWithTag("insight-rank-bar-dinner", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.width
        scroll("insight-rank-breakfast")
        val lastWidth = ui.onNodeWithTag("insight-rank-bar-breakfast", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.width
        assertEquals("A singleton row must not double the bar scale", firstWidth, lastWidth, 0.5f)
        capture("charts-840-rankings")
    }

    @Test fun monthlyBucketIsLabelledAndOpensTheWholeActualMonth() {
        var selected: InsightQuery? = null
        render(MobileActions(insightQuery = { selected = it }), report("/insights-charts-monthly-synthetic.json"))
        scroll("insight-trend-card")
        ui.onNodeWithText("每月個人支出・單位：元").assertExists()
        scroll("insight-trend-open")
        ui.onNodeWithTag("insight-trend-open").performClick()
        assertEquals(LocalDate.parse("2026-11-01"), selected?.dateFrom)
        assertEquals(LocalDate.parse("2026-11-30"), selected?.dateTo)
        capture("charts-monthly-390")
    }
}
