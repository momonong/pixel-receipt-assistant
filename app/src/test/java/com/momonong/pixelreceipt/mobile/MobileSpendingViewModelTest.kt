package com.momonong.pixelreceipt.mobile

import android.app.Application
import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.momonong.pixelreceipt.data.ingestion.ImageStore
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class MobileSpendingViewModelTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var root: File
    private lateinit var store: MobileStore
    private lateinit var repo: MobileRepository
    private val calls = CopyOnWriteArrayList<String>()
    private val models = ViewModelStore()
    private fun fixture(name: String) = javaClass.getResource("/$name")!!.readText()
    private val saved get() = SavedStateHandle(mapOf("mobileInsightQuery" to InsightQuery(java.time.LocalDate.parse("2026-11-01"),java.time.LocalDate.parse("2026-11-12")).filters().toString()))
    @Before fun setup() {
        root = File(app.cacheDir, UUID.randomUUID().toString()).apply { mkdirs() }
        store = MobileStore(File(root, "receipts")); store.load()
        val api = object : MobileApi by FakeMobileApi() {
            override suspend fun ledgerScope(token: String) = "7a11bd13-6cf2-4f2f-8d86-4db17417aa1d"
            override suspend fun insights(token:String,path:String):String {
                calls += path
                val query=path.substringAfter('?').split('&').associate{it.substringBefore('=') to it.substringAfter('=')}
                val q=insightQueryFromJson(com.google.gson.JsonObject().apply{query.filterKeys{it !in setOf("limit","offset")}.forEach{(k,v)->if(k=="undated_only")addProperty(k,v.toBoolean())else addProperty(k,v)}}.toString())
                if(!path.startsWith("v1/insights/entries"))return insightFixture(q)
                val entries=jsonObject(fixture("insights-unknown-entries-http-synthetic.json")).array("entries")
                return com.google.gson.JsonObject().apply{add("entries",entries);addProperty("total_count",1);addProperty("limit",20);addProperty("offset",0);add("filters",q.filters())}.toString()
            }
            override suspend fun ledgerDetail(token: String, id: String): String = jsonObject(fixture("insights-unknown-entries-http-synthetic.json")).array("entries").first { it.asJsonObject.text("id") == id }.toString()
        }
        repo = MobileRepository(store, ImageStore(File(root, "images")), MemoryVault().apply { save("prm_fixture") }, api, MobileSpendingCacheFile(File(root, "analysis")))
    }
    @After fun cleanup() { models.clear(); root.deleteRecursively() }
    private fun vm(handle: SavedStateHandle): MobileViewModel = MobileViewModel(app, handle, repo).also { models.put(UUID.randomUUID().toString(), it); await { it.state.value != null && it.signedIn.value } }
    private fun await(condition: () -> Boolean) {
        val end = System.nanoTime() + 10_000_000_000L
        while (!condition() && System.nanoTime() < end) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5) }
        shadowOf(Looper.getMainLooper()).idle(); assertTrue("ViewModel did not settle", condition())
    }
    @Test fun nativeNavigationKeepsThePeriodAndShopThroughDetailsAndRecreation() {
        val handle = saved; val model = vm(handle)
        model.spending(); await { model.insightState.value.entries.isNotEmpty() }
        assertEquals(MobilePage.Spending.name, model.destination.value)
        assertEquals(300L, model.insightState.value.report!!.summary.getValue("personal_yuan"))
        val city = model.insightState.value.report!!.groups.getValue("cities").first().key
        model.insightQuery(model.insightState.value.query.copy(cityKey=city,meal="not_applicable"))
        await { model.insightState.value.entries.isNotEmpty() && model.insightState.value.query.cityKey==city }
        val entry = model.insightState.value.entries.first().text("id")
        model.openLedger(entry); await { model.busy.value == null }
        model.back(); assertEquals(MobilePage.Spending.name, model.destination.value)
        assertEquals(city, model.insightState.value.query.cityKey)
        val restored = vm(SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) }))
        await { restored.insightState.value.entries.isNotEmpty() }
        assertEquals(java.time.LocalDate.parse("2026-11-01"), restored.insightState.value.query.dateFrom)
        assertEquals(city, restored.insightState.value.query.cityKey)
        assertEquals("not_applicable", restored.insightState.value.query.meal)
        assertTrue(calls.all { it.startsWith("v1/insights?") || it.startsWith("v1/insights/entries?") })
    }
    @Test fun nestedPriceSourceMetadataReturnsToOriginalAnalysisAfterRecreation() {
        val handle=saved;val model=vm(handle)
        model.spending();await{model.insightState.value.entries.isNotEmpty()&&!model.insightState.value.loading}
        val query=model.insightState.value.query.copy(meal="not_applicable",cityKey=model.insightState.value.report!!.groups.getValue("cities").first().key)
        model.insightQuery(query);await{model.insightState.value.entries.isNotEmpty()&&!model.insightState.value.loading}
        model.prices(null);assertEquals(MobilePage.Prices.name,model.destination.value)
        val ledger=model.insightState.value.entries.first().text("id")
        model.openLedger(ledger);await{model.busy.value==null}
        model.editInsight(ledger,true,false);model.back();assertEquals(MobilePage.LedgerDetail.name,model.destination.value)
        model.back();assertEquals(MobilePage.Prices.name,model.destination.value)
        models.clear()
        val restored=vm(SavedStateHandle(handle.keys().associateWith{handle.get<Any?>(it)}))
        restored.back()
        assertEquals("Metadata return must not overwrite price page's original destination",MobilePage.Spending.name,restored.destination.value)
        assertEquals(query,restored.insightState.value.query)
    }
    @Test fun formalLedgerChangesInvalidateAnalysisButEditingReceiptNotesDoesNot() {
        val model = vm(saved); model.spending(); await { model.insightState.value.entries.isNotEmpty() && !model.insightState.value.loading }
        val summaryReads = calls.count { it.startsWith("v1/insights?") }
        store.create(UUID.randomUUID().toString(), "只改說明")
        shadowOf(Looper.getMainLooper()).idle(); assertEquals(summaryReads, calls.count { it.startsWith("v1/insights?") })
        val entry = jsonObject(fixture("insights-unknown-entries-http-synthetic.json")).array("entries")[0].toString()
        store.cacheEntry(entry)
        await { calls.count { it.startsWith("v1/insights?") } > summaryReads && !model.insightState.value.loading }
        model.logout(); await { !model.signedIn.value }
        assertNull(model.insightState.value.report)
    }
}
