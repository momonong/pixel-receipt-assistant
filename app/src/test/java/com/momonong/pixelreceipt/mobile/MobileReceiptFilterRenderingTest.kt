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
import java.time.Instant
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class,qualifiers="w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MobileReceiptFilterRenderingTest {
    @get:Rule val ui=createEmptyComposeRule()
    private lateinit var scenario:ActivityScenario<ComponentActivity>
    private lateinit var dir:File
    private val page=mutableStateOf(MobilePage.Inbox)
    private val scale=mutableStateOf(1f)
    private var selected:String?=null
    private val batches=listOf(
        MobileBatch("completed",userNotes="合成日用品收據",importComplete=true,ledgerId="ledger",ledgerStatus="active"),
        MobileBatch("review",userNotes="合成午餐明細",importComplete=true,status="succeeded"),
        MobileBatch("processing",userNotes="合成新收件",importComplete=true,status="processing"),
        MobileBatch("voided",userNotes="合成作廢紀錄",importComplete=true,ledgerStatus="voided"),
        MobileBatch("delayed",userNotes="合成長期等待測試",importComplete=true,status="queued",serverJson="""{"created_at":"2020-01-01T00:00:00Z","polling_finished":false}"""),
        MobileBatch("missing",userNotes="合成缺照片測試",importComplete=false),
        MobileBatch("deleted",userNotes="合成刪除紀錄",importComplete=true,status="deleted"),
    )
    @Before fun setup() {
        val context=ApplicationProvider.getApplicationContext<Application>()
        dir=File(context.cacheDir,UUID.randomUUID().toString()).apply {mkdirs()}
        shadowOf(context.packageManager).addActivityIfNotPresent(ComponentName(context,ComponentActivity::class.java))
        scenario=ActivityScenario.launch(Intent(context,ComponentActivity::class.java))
        scenario.onActivity { it.setContent { PixelReceiptTheme(darkTheme=false,dynamicColor=false) {
            CompositionLocalProvider(LocalDensity provides Density(1f,scale.value)) {
                MobileContent(MobileUiState(page=page.value,signedIn=true,batches=batches),ImageStore(dir),MobileActions(select={selected=it},back={page.value=MobilePage.Inbox}))
            }
        } } }
    }
    @After fun cleanup(){scenario.close();dir.deleteRecursively()}
    private fun capture(name:String){ui.waitForIdle();val image=ui.onRoot().captureToImage().asAndroidBitmap();val f=File(System.getProperty("pixelreceipt.screenshots"),"$name.png");f.parentFile!!.mkdirs();f.outputStream().use{assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,it))}}
    private fun choose(key:String){ui.onNodeWithTag("receipt-filter-open").performClick();ui.onNodeWithTag("receipt-filter-list").performScrollToNode(hasTestTag("receipt-filter-$key"));ui.onNodeWithTag("receipt-filter-$key").performClick();ui.onNodeWithTag("receipt-filter-apply").performClick()}
    @Test fun initialViewNamesItsScopeAndKeepsNormalReceiptsVisible(){
        ui.onNodeWithTag("receipt-filter-open").assertExists()
        ui.onNodeWithText("顯示 3 / 7 筆").assertExists()
        ui.onNodeWithText("已完成").assertExists();ui.onNodeWithText("待核對").assertExists()
        ui.onNodeWithTag("receipt-attention").assertExists()
        capture("receipt-inbox-normal-390")
    }
    @Test fun deletedReceiptsRemainAvailableInAFilterAndCanBeOpened(){
        choose("status-deleted")
        ui.onNodeWithText("顯示 1 / 7 筆").assertExists()
        ui.onNodeWithText("合成刪除紀錄").assertExists()
        capture("receipt-inbox-deleted-390")
        ui.onNodeWithTag("mobile-batch-deleted").performClick();assertEquals("deleted",selected)
        ui.onNodeWithTag("receipt-filter-reset").performClick()
        ui.onNodeWithText("顯示 3 / 7 筆").assertExists()
    }
    @Test fun returningFromAnotherPagePreservesTheChosenScope(){
        choose("attention")
        ui.onNodeWithText("顯示 2 / 7 筆").assertExists()
        ui.runOnIdle{page.value=MobilePage.Help}
        ui.runOnIdle{page.value=MobilePage.Inbox}
        ui.onNodeWithText("顯示 2 / 7 筆").assertExists()
        capture("receipt-inbox-attention-390")
    }
    @Config(qualifiers="w320dp-h740dp-mdpi")
    @Test fun narrowLargeTextCanApplyTheLastFilterWithoutLosingTheAction(){
        ui.runOnIdle{scale.value=1.5f}
        choose("all")
        ui.onNodeWithText("顯示 7 / 7 筆").assertExists()
        ui.onNodeWithTag("receipt-filter-open").performClick()
        ui.onNodeWithTag("receipt-filter-list").performScrollToNode(hasTestTag("receipt-filter-status-deleted"));ui.onNodeWithTag("receipt-filter-status-deleted").performClick()
        ui.onNodeWithTag("receipt-filter-apply").assertIsDisplayed()
        capture("receipt-filter-sheet-320-font150")
        ui.onNodeWithTag("receipt-filter-apply").performClick()
        ui.onNodeWithText("合成刪除紀錄").assertExists()
        capture("receipt-inbox-filtered-320-font150")
    }
    @Test fun recreationRestoresBothTheFilterAndTheScrolledReceipt(){
        val many=(1..40).map { MobileBatch("receipt-$it",userNotes="合成第 $it 筆",importComplete=true) }
        val bridge=object : androidx.compose.ui.test.junit4.ComposeContentTestRule, androidx.compose.ui.test.junit4.ComposeTestRule by ui {
            override fun setContent(content: @androidx.compose.runtime.Composable () -> Unit) { scenario.onActivity { it.setContent(content = content) } }
        }
        val restoration=androidx.compose.ui.test.junit4.StateRestorationTester(bridge)
        restoration.setContent { PixelReceiptTheme(darkTheme=false,dynamicColor=false) { MobileContent(MobileUiState(batches=many,signedIn=true),ImageStore(dir),MobileActions()) } }
        choose("all")
        ui.onNodeWithTag("mobile-inbox").performScrollToNode(hasTestTag("mobile-batch-receipt-31"))
        ui.onNodeWithTag("mobile-batch-receipt-31").assertIsDisplayed()
        val before=ui.onNodeWithTag("mobile-batch-receipt-31").fetchSemanticsNode().boundsInRoot.top
        restoration.emulateSavedInstanceStateRestore()
        ui.onNodeWithTag("mobile-batch-receipt-31").assertIsDisplayed()
        assertEquals(before,ui.onNodeWithTag("mobile-batch-receipt-31").fetchSemanticsNode().boundsInRoot.top,2f)
        ui.onNodeWithTag("mobile-inbox").performScrollToIndex(0)
        ui.onNodeWithTag("receipt-filter-open").assertTextContains("全部收據 ▾")
    }
    @Test fun filterOnlyOffersStatesInThisCollection(){
        ui.onNodeWithTag("receipt-filter-open").performClick()
        assertThrows(AssertionError::class.java) {
            ui.onNodeWithTag("receipt-filter-list").performScrollToNode(hasTestTag("receipt-filter-status-reference"))
        }
        ui.onNodeWithTag("receipt-filter-apply").assertIsDisplayed()
    }

}
