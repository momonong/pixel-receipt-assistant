package com.momonong.pixelreceipt.mobile

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.momonong.pixelreceipt.core.ui.theme.PixelReceiptTheme
import kotlinx.coroutines.*
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
class MobilePriceRenderingTest {
    @get:Rule val ui=createEmptyComposeRule()
    private lateinit var scenario:ActivityScenario<ComponentActivity>
    private lateinit var dir:File
    private lateinit var scope:CoroutineScope
    private var opened:String?=null
    private var posted:String?=null
    private fun show(scale:Float=1f){
        val contract=jsonObject(javaClass.getResource("/MobilePrice-contract.json")!!.readText())
        val id="10000000-0000-4000-8000-000000000001"
        val prices=parseProductPrices(contract["product_prices_response"].toString(),id,0)
        val state=PriceUiState(open=true,selectedProduct=prices.product,prices=prices)
        val context=ApplicationProvider.getApplicationContext<Application>()
        dir=File(context.cacheDir,UUID.randomUUID().toString()).apply{mkdirs()};scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val controller=MobilePriceController(HttpsMobileApi(),scope,{null},{},{},dir)
        shadowOf(context.packageManager).addActivityIfNotPresent(ComponentName(context,ComponentActivity::class.java));scenario=ActivityScenario.launch(Intent(context,ComponentActivity::class.java))
        scenario.onActivity{it.setContent{PriceRenderingTheme{CompositionLocalProvider(LocalDensity provides Density(1f,scale)){MobilePriceScreen(state,controller,{opened=it},{})}}}}
    }
    private fun showReview(){
        val contract=jsonObject(javaClass.getResource("/MobilePrice-contract.json")!!.readText());val lid="20000000-0000-4000-8000-000000000001"
        val ledger=jsonObject(javaClass.getResource("/receipt-actions/ledger-active.json")!!.readText()).apply{addProperty("id",lid);getAsJsonObject("snapshot").add("candidate",contract["price_source_candidate"])}
        val api=object:MobileApi by HttpsMobileApi(){
            override suspend fun products(token:String,query:String,offset:Int)=contract["product_list_response"].toString()
            override suspend fun priceReview(token:String,id:String)=contract["price_review_unreviewed_response"].toString()
            override suspend fun ledgerDetail(token:String,id:String)=ledger.toString()
            override suspend fun savePriceReview(token:String,id:String,body:String,key:String):String{posted=body;return contract["price_review_response"].toString()}
        }
        val context=ApplicationProvider.getApplicationContext<Application>();dir=File(context.cacheDir,UUID.randomUUID().toString()).apply{mkdirs()};scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val controller=MobilePriceController(api,scope,{"prm_fixture"},{},{},dir,{"30000000-0000-4000-8000-000000000001"});controller.open(lid)
        shadowOf(context.packageManager).addActivityIfNotPresent(ComponentName(context,ComponentActivity::class.java));scenario=ActivityScenario.launch(Intent(context,ComponentActivity::class.java))
        scenario.onActivity{it.setContent{val state by controller.state.collectAsState();PriceRenderingTheme{MobilePriceScreen(state,controller,{opened=it},{})}}}
    }
    @After fun cleanup(){if(::scenario.isInitialized)scenario.close();if(::scope.isInitialized)scope.cancel();if(::dir.isInitialized)dir.deleteRecursively()}
    private fun capture(name:String){val path=System.getProperty("pixelreceipt.screenshots")?:return;val image=ui.onRoot().captureToImage().asAndroidBitmap();val file=File(path,"$name.png");file.parentFile!!.mkdirs();file.outputStream().use{assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,it))}}
    @Test fun historicalComparisonShowsExactProductAndSourceLedger(){
        show();ui.onNodeWithText("歷史價格，非目前售價",substring=true).assertExists();ui.onNodeWithText("無糖500mL",substring=true).assertExists();capture("price-history-390")
        ui.onNodeWithTag("price-list").performScrollToNode(hasTestTag("price-source-20000000-0000-4000-8000-000000000001-0"));ui.onNodeWithTag("price-source-20000000-0000-4000-8000-000000000001-0").performClick();assertEquals("20000000-0000-4000-8000-000000000001",opened)
    }
    @Test fun reviewerExplicitlyMatchesBothSpecsAndSavesSharedDiscountWithoutChangingLedger(){
        showReview();ui.onNodeWithText("逐列對應商品").assertExists();capture("price-review-390")
        listOf(0 to "10000000-0000-4000-8000-000000000001",1 to "10000000-0000-4000-8000-000000000002").forEach{(position,id)->
            ui.onNodeWithTag("price-list").performScrollToNode(hasTestTag("price-map-$position"));ui.onNodeWithTag("price-map-$position").performClick()
            ui.onNodeWithTag("price-list").performScrollToNode(hasTestTag("price-product-$id"));ui.onNodeWithTag("price-product-$id").performClick()
            ui.onNodeWithText("確認相同商品與單位").assertExists();ui.onNodeWithText("確認對應").performClick()
        }
        ui.onNodeWithTag("price-list").performScrollToNode(hasText("按票面行金額比例分配"));ui.onNodeWithText("按票面行金額比例分配").performClick()
        ui.onNodeWithTag("price-list").performScrollToNode(hasTestTag("price-save-review"));ui.onNodeWithTag("price-save-review").performClick()
        ui.onNodeWithTag("price-list").performScrollToNode(hasText("商品對應與價格分配已儲存。"));ui.onNodeWithText("商品對應與價格分配已儲存。").assertIsDisplayed()
        val body=jsonObject(posted!!);assertEquals(0L,body["expected_revision"].asLong);assertEquals(2,body.array("item_mappings").size());assertEquals("proportional_line_amount",body.array("adjustment_decisions")[0].asJsonObject.text("method"));capture("price-review-saved-390")
    }
    @Config(qualifiers="w320dp-h740dp-mdpi")
    @Test fun largeTextSmallPhoneKeepsSourceActionReachable(){show(1.5f);ui.onNodeWithTag("price-list").performScrollToNode(hasTestTag("price-source-20000000-0000-4000-8000-000000000002-0"));ui.onNodeWithTag("price-source-20000000-0000-4000-8000-000000000002-0").assertIsDisplayed();capture("price-history-320-large")}
    @Config(qualifiers="w840dp-h1050dp-mdpi")
    @Test fun wideDisplayKeepsHistoryReadable(){show();ui.onNodeWithText("歷史價格，非目前售價",substring=true).assertExists();capture("price-history-840")}
}

@Composable private fun PriceRenderingTheme(content:@Composable ()->Unit){
    PixelReceiptTheme(darkTheme=false,dynamicColor=false){MaterialTheme(colorScheme=lightColorScheme(primary=MobileTeal,onPrimary=androidx.compose.ui.graphics.Color.White,background=MobileGround,surface=androidx.compose.ui.graphics.Color.White,onSurface=MobileInk),typography=MobileTypography,content=content)}
}
