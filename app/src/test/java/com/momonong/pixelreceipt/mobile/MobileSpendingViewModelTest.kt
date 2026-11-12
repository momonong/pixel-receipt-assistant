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
    private val saved get() = SavedStateHandle(mapOf("mobileSpendingPeriod" to "Custom", "mobileSpendingFrom" to "2026-11-01", "mobileSpendingTo" to "2026-11-12"))
    @Before fun setup() {
        root = File(app.cacheDir, UUID.randomUUID().toString()).apply { mkdirs() }
        store = MobileStore(File(root, "receipts")); store.load()
        val api = object : MobileApi by FakeMobileApi() {
            override suspend fun spending(token: String, path: String): String {
                calls += path
                return if (path.startsWith("v1/spending?")) jsonObject(fixture("spending-dashboard-synthetic.json")).apply { add("comparison", com.google.gson.JsonNull.INSTANCE) }.toString()
                else jsonObject(fixture("spending-dashboard-ledger-synthetic.json")).apply {
                    val key = path.substringAfter("merchant_key=", "").takeIf { it.isNotEmpty() }
                    val all = array("entries"); val filtered = com.google.gson.JsonArray()
                    all.forEach { if (key == null || it.asJsonObject.text("merchant_key") == key) filtered.add(it) }
                    add("entries", filtered); addProperty("total_count", filtered.size())
                    if (key != null) addProperty("merchant_key", key)
                }.toString()
            }
            override suspend fun ledgerDetail(token: String, id: String): String = jsonObject(fixture("spending-dashboard-ledger-synthetic.json")).array("entries").first { it.asJsonObject.text("id") == id }.toString()
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
        model.spending(); await { model.spendingState.value.entriesJson != null }
        assertEquals(MobilePage.Spending.name, model.destination.value)
        assertEquals(1349L, model.spendingState.value.report!!.summary.personalYuan)
        val shop = model.spendingState.value.report!!.merchants.first { it.name == "日常生鮮" }
        model.selectSpending(SpendingSelection(merchantKey = shop.key))
        await { model.spendingState.value.entriesJson?.let { jsonObject(it).text("merchant_key") == shop.key } == true }
        val entry = jsonObject(model.spendingState.value.entriesJson!!).array("entries")[0].asJsonObject.text("id")
        model.openLedger(entry); await { model.busy.value == null }
        model.back(); assertEquals(MobilePage.Spending.name, model.destination.value)
        assertEquals(shop.key, model.spendingState.value.selection.merchantKey)
        val restored = vm(SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) }))
        await { restored.spendingState.value.entriesJson != null }
        assertEquals(SpendingPeriod.Custom, restored.spendingState.value.period)
        assertEquals(shop.key, restored.spendingState.value.selection.merchantKey)
        assertTrue(calls.all { it.startsWith("v1/spending?") || it.startsWith("v1/ledger?") })
    }
    @Test fun formalLedgerChangesInvalidateAnalysisButEditingReceiptNotesDoesNot() {
        val model = vm(saved); model.spending(); await { model.spendingState.value.entriesJson != null && !model.spendingState.value.loading }
        val summaryReads = calls.count { it.startsWith("v1/spending?") }
        store.create(UUID.randomUUID().toString(), "只改說明")
        shadowOf(Looper.getMainLooper()).idle(); assertEquals(summaryReads, calls.count { it.startsWith("v1/spending?") })
        val entry = jsonObject(fixture("spending-dashboard-ledger-synthetic.json")).array("entries")[0].toString()
        store.cacheEntry(entry)
        await { calls.count { it.startsWith("v1/spending?") } > summaryReads && !model.spendingState.value.loading }
        model.logout(); await { !model.signedIn.value }
        assertNull(model.spendingState.value.report)
    }
}
