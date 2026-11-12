package com.momonong.pixelreceipt.mobile

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
class MobilePriceBehaviorTest {
    private lateinit var dir:File
    private lateinit var scope:CoroutineScope
    private var token="prm_fixture"
    private var ledgerScope:String?="30000000-0000-4000-8000-000000000001"
    private val contract get()=jsonObject(javaClass.getResource("/MobilePrice-contract.json")!!.readText())
    private val pid="10000000-0000-4000-8000-000000000001"
    private val lid="20000000-0000-4000-8000-000000000001"
    @Before fun setup(){dir=File(ApplicationProvider.getApplicationContext<Application>().cacheDir,UUID.randomUUID().toString()).apply{mkdirs()};scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)}
    @After fun cleanup(){scope.cancel();dir.deleteRecursively()}
    private open inner class Source:MobileApi by HttpsMobileApi() {
        var posts=0;var lookups=0;var accepted:Pair<String,String>?=null;var lose=false
        override suspend fun products(token:String,query:String,offset:Int)=contract["product_list_response"].toString()
        override suspend fun createProduct(token:String,body:String,key:String):String {posts++;accepted=key to body;if(lose)throw IOException("lost");return jsonObject(contract["product_response"].toString()).apply{val request=jsonObject(body);listOf("display_name","brand","specification","purchase_unit").forEach{add(it,request[it])}}.toString()}
        override suspend fun analysisOperation(token:String,key:String):String {lookups++;return JsonObject().apply{addProperty("operation_id",key);addProperty("kind","product_create");addProperty("target_id",pid);add("result",contract["product_response"])}.toString()}
    }
    private fun controller(api:MobileApi)=MobilePriceController(api,scope,{token},{},{},dir,{ledgerScope})
    @Test fun exactUnitPricesStayRationalAndUnknownNeverBecomesZero(){
        val line=contract.getAsJsonObject("price_review_response").array("line_prices")[0].asJsonObject
        line.addProperty("printed_line_total_yuan",189);line.getAsJsonObject("printed_unit_price").addProperty("numerator_yuan",189)
        val parsed=parsePriceLine(line)
        assertEquals("189 / 2",parsed.printedUnit.display());assertEquals(189L,parsed.printedUnit.numerator)
        val unknown=parsePriceLine(contract.getAsJsonObject("price_review_unreviewed_response").array("line_prices")[0].asJsonObject)
        assertNull(unknown.paidUnit);assertFalse(unknown.eligible);assertTrue(unknown.reasons.contains("shared_adjustment_unallocated"))
    }
    @Test fun catalogPreservesDifferentSpecsAndDoesNotAutomaticallyMapAnything()=runBlocking {
        val c=controller(Source());c.open();c.awaitIdle()
        assertEquals(2,c.state.value.products.size);assertNull(c.state.value.selectedProduct)
        assertEquals("無糖500mL",c.state.value.products[0].specification)
    }
    @Test fun frozenAcceptedWriteIsDurableAndReconstructedControllerOnlyLooksUp()=runBlocking {
        val api=Source().apply{lose=true};val c=controller(api);c.createProduct("合成無糖茶500mL","合成茶品牌","無糖500mL","瓶");c.awaitIdle()
        assertTrue(c.state.value.pending);assertNotNull(c.state.value.error)
        val restored=controller(api);restored.open();restored.awaitIdle()
        assertFalse(restored.state.value.pending);assertEquals(pid,restored.state.value.selectedProduct?.id);assertEquals(1,api.posts);assertEquals(1,api.lookups)
        assertFalse(dir.walkTopDown().filter{it.isFile}.any{it.readText().contains(token)})
    }
    @Test fun lookup404KeepsFrozenRequestAndOnlyExplicitRetryReusesItsExactKeyAndBody()=runBlocking {
        val api=object:Source(){override suspend fun analysisOperation(token:String,key:String):String{lookups++;throw MobileApiFailure(404)}}.apply{lose=true}
        val c=controller(api);c.createProduct("茶",null,"500mL","瓶");c.awaitIdle();val frozen=api.accepted
        c.refresh();c.awaitIdle();assertTrue(c.state.value.pending);assertEquals(1,api.posts)
        api.lose=false;c.retryPending();c.awaitIdle();assertEquals(2,api.posts);assertEquals(frozen,api.accepted);assertFalse(c.state.value.pending)
    }
    @Test fun newTokenForSameVerifiedLedgerRecoversUnknownWithoutAnotherWrite()=runBlocking {
        val api=Source().apply{lose=true};val c=controller(api)
        c.createProduct("合成無糖茶500mL","合成茶品牌","無糖500mL","瓶");c.awaitIdle()
        token="prm_rotated";c.accountChanged();c.open();c.awaitIdle()
        assertFalse(c.state.value.pending);assertEquals(pid,c.state.value.selectedProduct?.id);assertEquals(1,api.posts);assertEquals(1,api.lookups)
    }
    @Test fun anotherVerifiedLedgerCannotReadOrReplayPreviousLedgersOperation()=runBlocking {
        val api=Source().apply{lose=true};val c=controller(api)
        c.createProduct("合成無糖茶500mL","合成茶品牌","無糖500mL","瓶");c.awaitIdle()
        token="prm_other";ledgerScope="30000000-0000-4000-8000-000000000002";c.accountChanged();c.open();c.awaitIdle()
        assertFalse(c.state.value.pending);assertEquals(0,api.lookups);assertEquals(1,api.posts)
        ledgerScope="30000000-0000-4000-8000-000000000001";c.accountChanged();c.open();c.awaitIdle();assertEquals(1,api.lookups);assertFalse(c.state.value.pending)
    }
    @Test fun unverifiedLedgerScopeBlocksLookupAndNewWrites()=runBlocking {
        ledgerScope=null;val api=Source();val c=controller(api)
        c.createProduct("茶",null,"500mL","瓶");c.awaitIdle();c.retryPending();c.awaitIdle()
        assertEquals(0,api.posts);assertEquals(0,api.lookups);assertNotNull(c.state.value.error)
    }
    @Test fun mismatchedSuccessfulProductPayloadDoesNotClearTheFrozenIntent()=runBlocking {
        val c=controller(object:Source(){override suspend fun createProduct(token:String,body:String,key:String)=contract["product_response"].toString()})
        c.createProduct("另一個商品",null,"2L","瓶");c.awaitIdle()
        assertTrue("different server identity must stay unresolved",c.state.value.pending)
        assertNull(c.state.value.selectedProduct);assertNotNull(c.state.value.error)
    }
    @Test fun lateOldAccount401CannotSignOutOrReplaceTheNewAccount()=runBlocking {
        val gate=CompletableDeferred<Unit>();var invalid=0
        val api=object:Source(){override suspend fun products(token:String,query:String,offset:Int):String{if(token=="prm_fixture")withContext(NonCancellable){gate.await();throw MobileApiFailure(401)};return super.products(token,query,offset)}}
        val c=MobilePriceController(api,scope,{token},{invalid++},{},dir,{ledgerScope});c.open();token="prm_new";c.accountChanged();c.open();yield();gate.complete(Unit);yield();c.awaitIdle()
        assertEquals(0,invalid);assertEquals(2,c.state.value.products.size);assertNull(c.state.value.error)
    }
    @Test fun priceMappingRequiresExplicitIdentityAndPreservesCasAndAllocationMethods(){
        val review=parsePriceReview(contract["price_review_unreviewed_response"].toString(),lid)
        val form=PriceReviewForm(review)
        assertEquals(0,jsonObject(form.body()).array("item_mappings").size())
        form.mappings[0]=pid;form.proportional(0);var body=jsonObject(form.body())
        assertEquals(0L,body["expected_revision"].asLong);assertEquals("confirmed_same_product_and_unit",body.array("item_mappings")[0].asJsonObject.text("unit_match"))
        form.explicit(0,mapOf(0 to "12",1 to "8"));body=jsonObject(form.body())
        assertEquals("explicit_amounts",body.array("adjustment_decisions")[0].asJsonObject.text("method"));assertEquals(8L,body.array("adjustment_decisions")[0].asJsonObject.array("amounts_by_item")[1].asJsonObject["amount_yuan"].asLong)
    }
    @Test fun restoredPriceReviewChecksCurrentVoidedLedgerBeforeAllowingEditing()=runBlocking {
        val ledger=jsonObject(javaClass.getResource("/receipt-actions/ledger-active.json")!!.readText()).apply{
            addProperty("id",lid);getAsJsonObject("snapshot").add("candidate",contract["price_source_candidate"])
        }
        val api=object:Source(){
            var reviewed=false;var voided=false;var writes=0
            override suspend fun priceReview(token:String,id:String)=contract[if(reviewed)"price_review_response" else "price_review_unreviewed_response"].toString()
            override suspend fun ledgerDetail(token:String,id:String)=ledger.deepCopy().apply{addProperty("status",if(voided)"voided" else "active")}.toString()
            override suspend fun savePriceReview(token:String,id:String,body:String,key:String):String {writes++;reviewed=true;accepted=key to body;throw IOException("lost accepted review")}
            override suspend fun analysisOperation(token:String,key:String)=JsonObject().apply{addProperty("operation_id",key);addProperty("kind","price_review");addProperty("target_id",lid);add("result",contract["price_review_response"])}.toString()
        }
        val c=controller(api);c.open(lid);c.awaitIdle();assertNotNull(c.state.value.review)
        c.saveReview(lid,contract["price_review_put_request"].toString());c.awaitIdle();assertTrue(c.state.value.pending)
        api.voided=true;val restored=controller(api);restored.open(lid);restored.awaitIdle()
        assertFalse(restored.state.value.pending);assertTrue("lookup result is historical; current ledger is voided",restored.state.value.voided);assertEquals(1,api.writes)
    }
    private inner class CrossLedgerSource:Source(){
        val other="20000000-0000-4000-8000-000000000002"
        var acceptedA=false;var voidedA=false;var aOperationMissing=false;var acceptARetry=false
        val reviewWrites=mutableListOf<Pair<String,String>>()
        val reviewWriteKeys=mutableListOf<String>()
        private fun unreviewed(id:String)=contract.getAsJsonObject("price_review_unreviewed_response").deepCopy().apply{
            addProperty("ledger_id",id);if(id==other)addProperty("revision",5)
        }
        override suspend fun priceReview(token:String,id:String):String=if(id==lid && acceptedA)contract["price_review_response"].toString() else unreviewed(id).toString()
        override suspend fun ledgerDetail(token:String,id:String)=jsonObject(javaClass.getResource("/receipt-actions/ledger-active.json")!!.readText()).apply{
            addProperty("id",id);addProperty("status",if(id==lid && voidedA)"voided" else "active")
            getAsJsonObject("snapshot").add("candidate",contract["price_source_candidate"].deepCopy().asJsonObject.apply{
                if(id==other)array("adjustments")[0].asJsonObject.apply{addProperty("raw_label","B 的折扣");addProperty("amount_yuan",7)}
            })
        }.toString()
        override suspend fun savePriceReview(token:String,id:String,body:String,key:String):String {
            reviewWrites+=id to body;reviewWriteKeys+=key
            if(id==lid){
                if(acceptARetry){acceptedA=true;return contract["price_review_response"].toString()}
                acceptedA=!aOperationMissing;throw IOException(if(acceptedA)"A accepted, response lost" else "A request lost before commit")
            }
            val request=jsonObject(body)
            return unreviewed(id).apply{addProperty("revision",request["expected_revision"].asLong+1);add("item_mappings",request["item_mappings"]);add("adjustment_decisions",request["adjustment_decisions"])}.toString()
        }
        override suspend fun analysisOperation(token:String,key:String):String {
            if(aOperationMissing)throw MobileApiFailure(404)
            return JsonObject().apply{addProperty("operation_id",key);addProperty("kind","price_review");addProperty("target_id",lid);add("result",contract["price_review_response"])}.toString()
        }
    }
    @Test fun recoveringAWhileOpeningBKeepsBReviewDiscountAndNextSaveTarget()=runBlocking {
        val api=CrossLedgerSource();val c=controller(api)
        c.open(lid);c.awaitIdle();c.saveReview(lid,contract["price_review_put_request"].toString());c.awaitIdle()
        assertTrue(c.state.value.pending);api.voidedA=true
        c.open(api.other);c.awaitIdle()
        val screen=c.state.value
        assertFalse(screen.pending);assertEquals(api.other,screen.ledgerId)
        assertEquals("recovering A must not replace B's editor",api.other,screen.review!!.ledgerId)
        assertEquals(5L,screen.review.revision);assertFalse("A being voided does not void B",screen.voided)
        assertEquals("B 的折扣",screen.adjustments.single().label);assertEquals(7L,screen.adjustments.single().amount)
        // This is the same target/body consumed by MobilePriceScreen's save action.
        c.saveReview(screen.review.ledgerId,PriceReviewForm(screen.review).body());c.awaitIdle()
        assertEquals(api.other,api.reviewWrites.last().first)
        assertEquals(5L,jsonObject(api.reviewWrites.last().second)["expected_revision"].asLong)
        assertEquals(api.other,c.state.value.review!!.ledgerId);assertEquals(6L,c.state.value.review!!.revision)
        assertFalse(c.state.value.pending);assertNotNull(c.state.value.message)
    }
    @Test fun explicitSameRequestRetryForAAfterLookup404KeepsBEditorAndNextSaveOnB()=runBlocking {
        val api=CrossLedgerSource().apply{aOperationMissing=true};val c=controller(api)
        c.open(lid);c.awaitIdle();c.saveReview(lid,contract["price_review_put_request"].toString());c.awaitIdle()
        assertTrue(c.state.value.pending)
        c.open(api.other);c.awaitIdle();assertTrue(c.state.value.pending);assertEquals(1,api.reviewWrites.size)
        api.acceptARetry=true;c.retryPending();c.awaitIdle()
        val screen=c.state.value
        assertFalse(screen.pending);assertEquals(api.other,screen.ledgerId)
        assertEquals("retrying A must not replace B's editor",api.other,screen.review!!.ledgerId)
        assertEquals(5L,screen.review.revision);assertFalse(screen.voided)
        assertEquals("B 的折扣",screen.adjustments.single().label);assertEquals(7L,screen.adjustments.single().amount)
        assertEquals(2,api.reviewWrites.size);assertEquals(api.reviewWrites[0],api.reviewWrites[1]);assertEquals(api.reviewWriteKeys[0],api.reviewWriteKeys[1])
        c.saveReview(screen.review.ledgerId,PriceReviewForm(screen.review).body());c.awaitIdle()
        assertEquals(api.other,api.reviewWrites.last().first);assertEquals(5L,jsonObject(api.reviewWrites.last().second)["expected_revision"].asLong)
        assertEquals(api.other,c.state.value.review!!.ledgerId);assertEquals(6L,c.state.value.review!!.revision)
    }
    @Test fun recoveringAInCatalogClearsOnlyTheJournalAndKeepsCatalogContext()=runBlocking {
        val api=CrossLedgerSource();val c=controller(api)
        c.open(lid);c.awaitIdle();c.saveReview(lid,contract["price_review_put_request"].toString());c.awaitIdle()
        assertTrue(c.state.value.pending);api.voidedA=true
        c.open();c.awaitIdle()
        assertFalse(c.state.value.pending);assertNull(c.state.value.ledgerId)
        assertNull("catalog must not contain A's editor",c.state.value.review)
        assertTrue(c.state.value.adjustments.isEmpty());assertFalse(c.state.value.voided)
        assertEquals(2,c.state.value.products.size);assertEquals(1,api.reviewWrites.size)
    }
    @Test fun unknownOrVoidedObservationCannotBePresentedAsHistoricalLowest(){
        val page=contract.getAsJsonObject("product_prices_response")
        val lowest=page.getAsJsonObject("lowest_paid_observation");lowest.addProperty("comparison_eligible",false);lowest.add("paid_unit_price",JsonNull.INSTANCE)
        try{parseProductPrices(page.toString(),pid,0);fail("invalid lowest accepted")}catch(_:IllegalArgumentException){}
    }
}
