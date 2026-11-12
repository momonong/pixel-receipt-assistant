package com.momonong.pixelreceipt.mobile

import android.app.Application
import android.app.DatePickerDialog
import android.content.DialogInterface
import androidx.compose.foundation.layout.WindowInsets
import org.robolectric.shadows.ShadowDialog
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
@Config(sdk=[28],application=Application::class,qualifiers="w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MobileInsightRenderingTest {
    @get:Rule val ui=createEmptyComposeRule()
    private lateinit var scenario:ActivityScenario<ComponentActivity>
    private lateinit var directory:File
    @Before fun setup(){
        val app=ApplicationProvider.getApplicationContext<Application>()
        directory=File(app.cacheDir,UUID.randomUUID().toString()).apply{mkdirs()}
        shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app,ComponentActivity::class.java))
        scenario=ActivityScenario.launch(Intent(app,ComponentActivity::class.java))
    }
    @After fun cleanup(){scenario.close();directory.deleteRecursively()}
    private fun render(scale:Float=1f,actions:MobileActions=MobileActions(),actual:Boolean=false){
        val raw=if(actual)javaClass.getResource("/insights-http-synthetic.json")!!.readText()else insightFixture()
        val q=insightQueryFromJson(jsonObject(raw).getAsJsonObject("filters").toString())
        val report=parseInsightReport(raw,q)
        scenario.onActivity{it.setContent{CompositionLocalProvider(LocalDensity provides Density(1f,scale)){
            MobileContent(MobileUiState(page=MobilePage.Spending,signedIn=true,insight=InsightUiState(enabled=true,query=q,report=report,stale=!actual,entriesLoading=actual)),ImageStore(directory),actions)
        }}}
        ui.waitForIdle()
    }
    private fun capture(name:String){
        val file=File(System.getProperty("pixelreceipt.screenshots","../.receipt-lab/spending-dimensions-20261112/screenshots"),"$name.png").apply{parentFile?.mkdirs()}
        val bitmap=ui.onRoot().captureToImage().asAndroidBitmap()
        file.outputStream().use{assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}
    }
    @Test fun categoriesAndUnknownCoverageAreVisible(){render();ui.onNodeWithText("個人支出").assertExists();ui.onNodeWithTag("insight-list").performScrollToNode(hasText("未分類"));ui.onNodeWithText("未分類").assertIsDisplayed();capture("insights-categories-390");ui.onNodeWithTag("insight-list").performScrollToNode(hasTestTag("insight-filter-open"));ui.onNodeWithTag("insight-filter-open").performClick();ui.onAllNodesWithText("餐別").onFirst().assertExists();capture("insights-filter-390")}
    @Test fun primaryAnalysisShowsCategoryAndKeepsCoverageCollapsed() {
        render()
        ui.onNodeWithTag("insight-dimension-selector").assertIsDisplayed()
        capture("insights-overview-390")
        ui.onNodeWithText("未分類").assertIsDisplayed()
        ui.onNodeWithTag("insight-coverage-expand").assertIsDisplayed()
        ui.onNodeWithText("縣市待補：NT$ 300").assertDoesNotExist()
        ui.onNodeWithTag("insight-coverage-expand").performClick()
        ui.onNodeWithText("縣市待補：NT$ 300").assertExists()
        ui.onNodeWithTag("insight-coverage-expand").performClick()
        ui.onNodeWithTag("insight-dimension-selector").performClick()
        listOf("生活分類","細分類","餐別","品牌","分店","縣市","地區","消費時間","週間分布","時段","支出歸屬","折扣").forEach{ui.onNodeWithText(it,useUnmergedTree=true).assertExists()}
        capture("insight-dimension-menu-390")
    }
    @Test fun merchantAndFullMonthRowsDrillIntoTheirExactScope() {
        var selected:InsightQuery?=null
        val q=InsightQuery(java.time.LocalDate.parse("2026-01-01"),java.time.LocalDate.parse("2026-12-31"))
        val raw=jsonObject(insightFixture(q)).apply {
            addProperty("bucket","month")
            getAsJsonArray("series")[0].asJsonObject.apply {addProperty("date_from","2026-11-01");addProperty("date_to","2026-11-30");addProperty("label","2026-11")}
        }
        val report=parseInsightReport(raw.toString(),q)
        scenario.onActivity{it.setContent{MobileContent(MobileUiState(page=MobilePage.Spending,signedIn=true,insight=InsightUiState(enabled=true,query=q,report=report)),ImageStore(directory),MobileActions(insightQuery={selected=it}))}}
        ui.onNodeWithTag("insight-dimension-selector").performClick();ui.onNodeWithText("消費時間",useUnmergedTree=true).performScrollTo().performClick()
        ui.onNodeWithTag("insight-list").performScrollToNode(hasText("2026-11"))
        ui.onNodeWithText("2026-11").performClick()
        assertEquals(java.time.LocalDate.parse("2026-11-01"),selected?.dateFrom);assertEquals(java.time.LocalDate.parse("2026-11-30"),selected?.dateTo)
        ui.onNodeWithTag("insight-list").performScrollToNode(hasTestTag("insight-dimension-selector"))
        ui.onNodeWithTag("insight-dimension-selector").performClick();ui.onNodeWithText("店家",useUnmergedTree=true).performScrollTo().performClick()
        val merchant=report.groups.getValue("merchants").first()
        ui.onNodeWithTag("insight-list").performScrollToNode(hasText(merchant.label!!))
        ui.onNodeWithText(merchant.label!!).performClick()
        assertEquals(merchant.key,selected?.merchantKey)
        capture("insights-original-merchant-390")
    }
    @Test fun filterSheetCombinesMealCategoryAndCity(){
        var applied:InsightQuery?=null
        render(actions=MobileActions(insightQuery={applied=it}),actual=true)
        capture("insights-mixed-overview-390")
        ui.onNodeWithTag("insight-filter-open").performClick()
        ui.onNodeWithTag("insight-choice-生活分類").performScrollTo().performClick();ui.onNode(hasText("食") and hasAnyAncestor(isPopup()),useUnmergedTree=true).performClick()
        ui.onNodeWithTag("insight-choice-餐別").performScrollTo().performClick();ui.onNodeWithText("晚餐",useUnmergedTree=true).performClick()
        ui.onNodeWithTag("insight-choice-縣市").performScrollTo().performClick();ui.onNodeWithText("臺南市",useUnmergedTree=true).performClick()
        ui.onNodeWithTag("insight-filter-apply").performScrollTo().performClick()
        assertEquals("food",applied?.category);assertEquals("dinner",applied?.meal);assertNotNull(applied?.cityKey)
    }
    @Test fun postLedgerMetadataCanBeSavedWithoutFinancialFields(){
        var saved:com.google.gson.JsonObject?=null
        val metadata=jsonObject(javaClass.getResource("/insight-metadata-synthetic.json")!!.readText())
        val state=MobileUiState(page=MobilePage.InsightEditor,signedIn=true,insight=InsightUiState(enabled=true,metadata=metadata,metadataId=metadata.text("ledger_id"),metadataLedger=true))
        scenario.onActivity{it.setContent{MobileContent(state,ImageStore(directory),MobileActions(saveInsightMetadata={saved=it}))}}
        ui.onNodeWithText("返回原畫面").assertDoesNotExist()
        ui.onNodeWithText("品牌（選填）").performTextInput("合成品牌")
        ui.onNodeWithTag("insight-metadata-save").performScrollTo().performClick()
        assertEquals("合成品牌",saved?.text("brand"));assertFalse(saved!!.has("amount_yuan"))
        capture("metadata-save-390")
    }
    @Test fun receiptFreeManualMetadataDoesNotOfferReceiptDateEvidence(){
        mountManualMetadata(false)
        ui.onNodeWithTag("insight-choice-日期依據").performScrollTo().performClick()
        ui.onNodeWithText("收據印刷日期",useUnmergedTree=true).assertDoesNotExist()
    }
    @Test fun legacyManualSourceWithPhotoKeepsReceiptDateChoice(){
        mountManualMetadata(true)
        ui.onNodeWithTag("insight-choice-日期依據").performScrollTo().performClick()
        ui.onNodeWithText("收據印刷日期",useUnmergedTree=true).assertExists()
    }
    private fun mountManualMetadata(hasPhoto:Boolean){
        val metadata=jsonObject(javaClass.getResource("/insight-metadata-synthetic.json")!!.readText())
        val entry=jsonObject(javaClass.getResource("/insight-manual-ledger-synthetic.json")!!.readText())
        if(hasPhoto)entry.getAsJsonObject("snapshot").getAsJsonArray("evidence").add(jsonObject("""{"sha256":"${"a".repeat(64)}","pages":[1]}"""))
        val details=com.google.gson.JsonObject().apply{add(metadata.text("ledger_id"),entry)}.toString()
        val state=MobileUiState(page=MobilePage.InsightEditor,signedIn=true,ledgerDetailsJson=details,insight=InsightUiState(enabled=true,metadata=metadata,metadataId=metadata.text("ledger_id"),metadataLedger=true))
        scenario.onActivity{it.setContent{MobileContent(state,ImageStore(directory),MobileActions())}}
    }
    @Test fun manualOneLineCanConfirmWithoutPhotoOrName(){
        var body:String?=null
        scenario.onActivity{it.setContent{MobileContent(MobileUiState(page=MobilePage.Manual,signedIn=true),ImageStore(directory),MobileActions(submitManual={raw->body=raw}))}}
        val today=java.time.LocalDate.now()
        ui.onNodeWithText("消費日期：${today.year}年${today.monthValue}月${today.dayOfMonth}日").assertExists()
        ui.onNodeWithText("返回原畫面").assertDoesNotExist()
        ui.onNodeWithTag("mobile-back").assertIsDisplayed()
        ui.onNodeWithTag("manual-amount").performTextInput("300")
        ui.onNodeWithTag("manual-form").performScrollToNode(hasTestTag("manual-confirm"))
        ui.onNodeWithTag("manual-confirm").assertIsEnabled().performClick()
        assertEquals(300L,jsonObject(body!!)["amount_yuan"].asLong)
        assertTrue(jsonObject(body!!)["description"].isJsonNull)
        capture("manual-confirm-390")
    }
    @Test fun manualConfirmationRemainsReachableAboveKeyboard() {
        var body:String?=null
        scenario.onActivity{it.setContent{MobileContent(MobileUiState(page=MobilePage.Manual,signedIn=true),ImageStore(directory),MobileActions(submitManual={body=it}),imeInsets=WindowInsets(bottom=280))}}
        ui.onNodeWithTag("manual-amount").performTextInput("300")
        ui.onNodeWithTag("manual-form").performScrollToNode(hasTestTag("manual-confirm"))
        ui.onNodeWithTag("manual-confirm").assertIsDisplayed().performClick()
        assertEquals(300L,jsonObject(body!!)["amount_yuan"].asLong)
        capture("manual-keyboard-390")
    }
    @Test fun manualCalendarChangesIsoDateAndKeepsMoney() {
        var body:String?=null
        scenario.onActivity{it.setContent{MobileContent(MobileUiState(page=MobilePage.Manual,signedIn=true),ImageStore(directory),MobileActions(submitManual={body=it}))}}
        ui.onNodeWithTag("manual-amount").performTextInput("300")
        val today=java.time.LocalDate.now()
        ui.onNodeWithText("消費日期：${today.year}年${today.monthValue}月${today.dayOfMonth}日").performClick()
        ui.runOnIdle{(ShadowDialog.getLatestDialog() as DatePickerDialog).apply{datePicker.updateDate(2026,0,2);getButton(DialogInterface.BUTTON_POSITIVE).performClick()}}
        ui.onNodeWithText("消費日期：2026年1月2日").assertExists()
        ui.onNodeWithTag("manual-form").performScrollToNode(hasTestTag("manual-confirm"))
        ui.onNodeWithTag("manual-confirm").performClick()
        assertEquals("2026-01-02",jsonObject(body!!).text("receipt_date"));assertEquals(300L,jsonObject(body!!)["amount_yuan"].asLong)
        assertEquals("user",jsonObject(body!!).getAsJsonObject("analysis").text("date_source"))
    }
    @Test @Config(qualifiers="w320dp-h720dp-mdpi") fun compactLargeText(){render(1.5f);ui.onNodeWithTag("insight-filter-open").assertExists();capture("insights-320-font150")}
    @Test @Config(qualifiers="w600dp-h960dp-mdpi") fun tablet(){render(actual=true);capture("insights-600")}
    @Test @Config(qualifiers="w840dp-h960dp-mdpi") fun foldable(){render(actual=true);capture("insights-840")}
}
