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
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.unit.Density
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MobileDiningRenderingTest {
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
    private fun report(resource: String = "/insights-dining-synthetic.json"): InsightReport {
        val raw = javaClass.getResource(resource)!!.readText()
        return parseInsightReport(raw, insightQueryFromJson(jsonObject(raw).getAsJsonObject("filters").toString()))
    }
    private fun render(report: InsightReport = report(), actions: MobileActions = MobileActions(), scale: Float = 1f) {
        scenario.onActivity { it.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, scale)) {
                MobileContent(MobileUiState(page = MobilePage.Spending, signedIn = true,
                    insight = InsightUiState(enabled = true, query = report.query, report = report, stale = false)),
                    ImageStore(directory), actions)
            }
        } }
        ui.waitForIdle()
    }
    private fun scroll(tag: String) { ui.onNodeWithTag("insight-list").performScrollToNode(hasTestTag(tag)) }
    private fun capture(name: String) {
        val file = File(System.getProperty("pixelreceipt.screenshots"), "$name.png").apply { parentFile?.mkdirs() }
        val bitmap = ui.onRoot().captureToImage().asAndroidBitmap()
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }

    @Test fun foodScopeShowsMerchantPieHighestSpendingAndPercentages() {
        var query: InsightQuery? = null
        val report = report()
        val top = report.groups.getValue("merchants").maxBy { it.personal }
        render(actions = MobileActions(insightQuery = { query = it }))
        ui.onNodeWithText("餐飲個人支出").assertExists()
        ui.onNodeWithText("NT$ 1,265").assertExists()
        capture("dining-overview-390")
        scroll("insight-dining-highest")
        ui.onNodeWithTag("insight-dining-highest").performClick()
        assertEquals(report.query.copy(merchantKey = top.key), query)
        scroll("insight-composition-card")
        ui.onNodeWithText("餐飲店家占比").assertExists()
        capture("dining-merchants-390")
        scroll("insight-slice-${top.key}")
        ui.onNodeWithTag("insight-slice-${top.key}").performClick()
        assertEquals(report.query.copy(merchantKey = top.key), query)
        scroll("insight-dimension-selector")
        ui.onNodeWithText("分析面向：店家 ▾").assertExists()
        scroll("insight-rank-${top.key}")
        ui.onNodeWithText("占目前餐飲 56.9%").assertExists()
        capture("dining-ranking-390")
    }

    @Test fun foodEntryRetainsDatesAndMealCityScopeAndScopedShareUsesCorrectDenominator() {
        var query: InsightQuery? = null
        val lunch = report("/insights-dining-lunch-synthetic.json")
        val before = lunch.copy(query = lunch.query.copy(category = null))
        render(report = before, actions = MobileActions(insightQuery = { query = it }))
        scroll("insight-food-open")
        ui.onNodeWithTag("insight-food-open").performClick()
        assertEquals(lunch.query, query)
        render(report = lunch, actions = MobileActions(insightQuery = { query = it }))
        val store = lunch.groups.getValue("merchants").single()
        assertEquals(410L, lunch.summary.getValue("personal_yuan"))
        scroll("insight-slice-${store.key}")
        ui.onNodeWithTag("insight-slice-${store.key}").performClick()
        assertEquals(lunch.query.copy(merchantKey = store.key), query)
        scroll("insight-rank-${store.key}")
        ui.onNodeWithText("占目前餐飲 100%").assertExists()
    }

    @Test @Config(qualifiers = "w320dp-h720dp-mdpi") fun foodShopsRemainReadableWithLargeText() {
        render(scale = 1.5f)
        scroll("insight-composition-chart")
        capture("dining-320-font150")
        scroll("insight-dimension-selector")
        ui.onNodeWithText("分析面向：店家 ▾").assertExists()
    }

    @Test @Config(qualifiers = "w840dp-h960dp-mdpi") fun unfoldedFoodAnalysisShowsActualMerchantSlices() {
        render()
        scroll("insight-charts-pair")
        ui.onNodeWithText("餐飲店家占比").assertExists()
        ui.onNodeWithTag("insight-trend-chart").assertExists()
        capture("dining-840")
    }
}
