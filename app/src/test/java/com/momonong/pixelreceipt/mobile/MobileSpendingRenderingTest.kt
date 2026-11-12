package com.momonong.pixelreceipt.mobile

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.momonong.pixelreceipt.core.ui.theme.PixelReceiptTheme
import com.momonong.pixelreceipt.data.ingestion.ImageStore
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class,qualifiers="w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MobileSpendingRenderingTest {
    @get:Rule val ui=createEmptyComposeRule()
    private lateinit var scenario:ActivityScenario<ComponentActivity>
    private lateinit var dir:File
    private val model=mutableStateOf(MobileUiState())
    private val scale=mutableStateOf(1f)
    private lateinit var report:SpendingReport
    private lateinit var page:String
    private var range:Pair<LocalDate,LocalDate>?=null;private var selected:SpendingSelection?=null;private var refreshed=0;private var opened:String?=null;private var chosenPeriod:SpendingPeriod?=null
    private fun fixture(name:String)=javaClass.getResource("/$name")!!.readText()
    private fun choose(selection:SpendingSelection){
        selected=selection
        val source=jsonObject(page);val rows=com.google.gson.JsonArray()
        source.array("entries").forEach{value->
            val e=value.asJsonObject;val date=e.getAsJsonObject("snapshot").getAsJsonObject("candidate").text("receipt_date")
            if((selection.merchantKey==null||e.text("merchant_key")==selection.merchantKey)&&(selection.from==null||date>=selection.from.toString())&&(selection.to==null||date<=selection.to.toString()))rows.add(e.deepCopy())
        }
        source.add("entries",rows);source.addProperty("total_count",rows.size())
        model.value=model.value.copy(spending=model.value.spending.copy(selection=selection,entriesJson=source.toString()))
    }
    @Before fun setup(){
        val q=SpendingQuery(LocalDate.parse("2026-11-01"),LocalDate.parse("2026-11-12"),LocalDate.parse("2026-10-01"),LocalDate.parse("2026-10-12"))
        report=parseSpending(fixture("spending-dashboard-synthetic.json"),q);page=fixture("spending-dashboard-ledger-synthetic.json")
        model.value=MobileUiState(page=MobilePage.Spending,signedIn=true,spending=SpendingUiState(query=q,report=report,entriesJson=page,stale=false,fetchedAt=System.currentTimeMillis()))
        val context=ApplicationProvider.getApplicationContext<Application>();dir=File(context.cacheDir,UUID.randomUUID().toString()).apply{mkdirs()}
        shadowOf(context.packageManager).addActivityIfNotPresent(ComponentName(context,ComponentActivity::class.java));scenario=ActivityScenario.launch(Intent(context,ComponentActivity::class.java))
        scenario.onActivity{it.setContent{PixelReceiptTheme(darkTheme=false,dynamicColor=false){CompositionLocalProvider(LocalDensity provides Density(1f,scale.value)){
            MobileContent(model.value,ImageStore(dir),MobileActions(refreshSpending={refreshed++},spendingSelect=::choose,spendingPeriod={chosenPeriod=it},spendingRange={a,b->range=a to b},ledgerDetail={opened=it},select={model.value=model.value.copy(page=MobilePage.Inbox)},spending={model.value=model.value.copy(page=MobilePage.Spending)}))
        }}}}
    }
    @After fun cleanup(){scenario.close();dir.deleteRecursively()}
    private fun capture(name:String){ui.waitForIdle();val image=ui.onRoot().captureToImage().asAndroidBitmap();val f=File(System.getProperty("pixelreceipt.screenshots"),"$name.png");f.parentFile!!.mkdirs();f.outputStream().use{assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,it))}}
    @Test fun phoneLeadsWithPersonalSpendingAndKeepsAdvanceAndDiscountMeaningsClear(){
        ui.onNodeWithTag("spending-personal").assertTextContains("1,349", substring=true)
        ui.onNodeWithText("7 筆已入帳",substring=true).assertExists()
        capture("spending-overview-390")
        ui.onNodeWithTag("spending-breakdown").performClick()
        ui.onNodeWithText("自己用").assertExists();ui.onNodeWithText("送人").assertExists()
        ui.onNodeWithText("1,409",substring=true).assertExists();ui.onNodeWithText("NT$ 50").assertExists()
        capture("spending-breakdown-390")
    }
    @Test fun shopAndChartSelectionsLeadToTheirReceiptRowsWithoutLeavingTheApp(){
        val shop=report.merchants.first{it.name=="日常生鮮"}
        ui.onNodeWithTag("spending-list").performScrollToNode(hasTestTag("spending-merchant-${shop.key}"))
        ui.onNodeWithTag("spending-merchant-${shop.key}").performClick();assertEquals(shop.key,selected!!.merchantKey)
        ui.onNodeWithTag("spending-list").performScrollToNode(hasTestTag("spending-selected"));ui.onNodeWithTag("spending-selected").assertTextContains("日常生鮮")
        val entry=jsonObject(page).array("entries").first{it.asJsonObject.text("merchant_key")==shop.key}.asJsonObject
        ui.onNodeWithTag("spending-list").performScrollToNode(hasTestTag("spending-entry-${entry.text("id")}"))
        ui.onNodeWithTag("spending-entry-${entry.text("id")}").performClick();assertEquals(entry.text("id"),opened)
        capture("spending-shop-drilldown-390")
        ui.onNodeWithTag("spending-list").performScrollToNode(hasTestTag("spending-chart-next"))
        ui.onNodeWithTag("spending-chart-next").performClick();assertEquals(LocalDate.parse("2026-11-01"),selected!!.from);assertNull(selected!!.merchantKey)
    }
    @Config(qualifiers="w840dp-h1050dp-mdpi")
    @Test fun unfoldedScreenUsesBothColumnsAndKeepsTheSameFinancialValues(){
        ui.onNodeWithTag("spending-expanded").assertExists();ui.onNodeWithTag("spending-personal").assertTextContains("1,349", substring=true)
        capture("spending-overview-fold-840")
    }
    @Config(qualifiers="w600dp-h900dp-mdpi")
    @Test fun narrowUnfoldedScreenKeepsBothPanelsUsable(){
        ui.onNodeWithTag("spending-expanded").assertExists()
        ui.onNodeWithTag("spending-list").performScrollToNode(hasTestTag("spending-chart-next"))
        ui.onNodeWithTag("spending-chart-next").assertIsDisplayed().performClick()
        assertNotNull(selected);capture("spending-overview-fold-600")
    }
    @Config(qualifiers="w320dp-h740dp-mdpi")
    @Test fun smallPhoneAtLargeTextCanUseRangeAndChartActions(){
        ui.runOnIdle{scale.value=1.5f}
        ui.onNodeWithTag("spending-period").performClick();ui.onNodeWithTag("spending-period-PreviousMonth").performClick();assertEquals(SpendingPeriod.PreviousMonth,chosenPeriod)
        ui.onNodeWithTag("spending-list").performScrollToNode(hasTestTag("spending-chart-next"));ui.onNodeWithTag("spending-chart-next").assertIsDisplayed().performClick();assertNotNull(selected)
        capture("spending-chart-320-font150")
    }
    @Test fun offlineAndSignedOutViewsNeverInventZeroOrShowAnUnverifiedScope(){
        ui.runOnIdle{model.value=model.value.copy(spending=model.value.spending.copy(report=null,entriesJson=null,error="合成離線狀態",stale=true))}
        ui.onNodeWithTag("spending-personal").assertDoesNotExist();ui.onNodeWithText("合成離線狀態").assertExists()
        ui.onNodeWithTag("spending-retry").performClick();assertEquals(1,refreshed);capture("spending-offline-390")
        ui.runOnIdle{model.value=model.value.copy(signedIn=false,spending=model.value.spending.copy(report=report))}
        ui.onNodeWithTag("spending-personal").assertDoesNotExist();ui.onNodeWithText("登入後查看支出分析").assertExists()
    }
    @Config(qualifiers="w320dp-h740dp-mdpi")
    @Test fun customRangeUsesTheCalendarAndAppliesTheChosenDates(){
        ui.runOnIdle{scale.value=1.5f}
        ui.onNodeWithTag("spending-period").performClick();ui.onNodeWithTag("spending-period-Custom").performClick()
        ui.onNodeWithTag("spending-date-from").performClick()
        ui.runOnIdle {
            val picker=org.robolectric.shadows.ShadowDialog.getLatestDialog() as android.app.DatePickerDialog
            picker.datePicker.updateDate(2026,9,1)
            picker.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
        }
        ui.onNodeWithTag("spending-date-apply").assertIsDisplayed().performClick()
        assertEquals(LocalDate.parse("2026-10-01") to LocalDate.parse("2026-11-12"),range)
    }
    @Test fun offlineCachedSummaryDoesNotPretendTheUnreadLedgerIsEmpty(){
        ui.runOnIdle{model.value=model.value.copy(spending=model.value.spending.copy(entriesJson=null,stale=true,error="合成離線狀態"))}
        ui.onNodeWithTag("spending-personal").assertTextContains("1,349",substring=true)
        ui.onNodeWithTag("spending-list").performScrollToNode(hasTestTag("spending-details"))
        ui.onNodeWithText("這個範圍沒有已入帳帳目。").assertDoesNotExist()
        ui.onNodeWithText("帳目明細尚未讀取，連線後更新分析。").assertExists()
        capture("spending-cached-offline-390")
    }
    @Test fun cachedAndEmptyReportsHaveHonestContext(){
        ui.runOnIdle{model.value=model.value.copy(spending=model.value.spending.copy(stale=true))}
        ui.onNodeWithTag("spending-list").performScrollToNode(hasText("顯示上次保存的分析；更新後才能確認最新狀態。"))
        ui.onNodeWithText("顯示上次保存的分析；更新後才能確認最新狀態。").assertIsDisplayed()
        ui.runOnIdle{
            val zero=SpendingSummary(0,0,0,0,0,0,0,0,0,0,0)
            model.value=model.value.copy(spending=model.value.spending.copy(report=report.copy(summary=zero,comparison=null,series=emptyList(),merchants=emptyList()),entriesJson="{\"entries\":[]}",stale=false))
        }
        ui.onNodeWithTag("spending-list").performScrollToNode(hasTestTag("spending-personal"))
        ui.onNodeWithTag("spending-personal").assertTextContains("NT$ 0")
        ui.onNodeWithTag("spending-list").performScrollToNode(hasText("這段期間還沒有含日期的已入帳消費。"))
        ui.onNodeWithText("這段期間還沒有含日期的已入帳消費。").assertExists()
        capture("spending-empty-390")
    }
    @Config(qualifiers="w840dp-h420dp-mdpi")
    @Test fun shortLandscapeWithLargeTextKeepsDetailsAndNavigationReachable(){
        ui.runOnIdle{scale.value=1.5f}
        ui.onNodeWithTag("spending-list").performScrollToNode(hasTestTag("spending-details"));ui.onNodeWithTag("spending-details").assertExists()
        ui.onNodeWithTag("mobile-navigation-Spending").assertIsDisplayed();capture("spending-landscape-font150")
    }
}
