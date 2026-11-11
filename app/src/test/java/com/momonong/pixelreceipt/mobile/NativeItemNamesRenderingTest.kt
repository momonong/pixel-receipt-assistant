package com.momonong.pixelreceipt.mobile

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.google.gson.JsonNull
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
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeItemNamesRenderingTest {
    @get:Rule val ui = createEmptyComposeRule()
    private lateinit var scenario: ActivityScenario<ComponentActivity>
    private lateinit var directory: File
    private lateinit var entry: String
    private val current = mutableStateOf(MobileUiState())
    private var saved: Map<Int, String?>? = null
    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        directory = File(context.cacheDir, UUID.randomUUID().toString()).apply { mkdirs() }
        entry = jsonObject(ReceiptActionsApi().entry()).apply { getAsJsonObject("snapshot").getAsJsonObject("candidate").array("items")[0].asJsonObject.add("raw_name", JsonNull.INSTANCE) }.toString()
        shadowOf(context.packageManager).addActivityIfNotPresent(ComponentName(context, ComponentActivity::class.java))
        scenario = ActivityScenario.launch(Intent(context, ComponentActivity::class.java))
    }
    @After fun cleanup() { scenario.close(); directory.deleteRecursively() }
    private fun capture(name: String) {
        ui.waitForIdle()
        val file = File(System.getProperty("pixelreceipt.screenshots"), "$name.png")
        file.parentFile!!.mkdirs()
        val image = ui.onRoot().captureToImage().asAndroidBitmap()
        file.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
    private fun mount(page: MobilePage) {
        val id = ledgerObject(entry).text("id")
        current.value = MobileUiState(page = page, signedIn = true, reviewer = true, ledgerJson = """{"entries":[$entry],"total_count":1}""", selectedLedgerId = id,
            overviewJson = """{"scope":"active_confirmed_ledger","receipt_count":1,"personal_yuan":180,"printed_total_yuan":180,"missing_item_name_count":1,"receipts_missing_item_names":1}""")
        scenario.onActivity { it.setContent { PixelReceiptTheme(darkTheme = false, dynamicColor = false) {
            MobileContent(current.value, ImageStore(directory), MobileActions(updateItemNames = { target, names -> assertEquals(id, target); saved = names }))
        } } }
        ui.waitForIdle()
    }
    @Test fun overviewAndEachReceiptExposeMissingNamesWithoutBlockingAccountingHistory() {
        mount(MobilePage.Ledger)
        ui.onNodeWithText("1 筆帳目 · 1 個品項待補品名").assertIsDisplayed()
        ui.onNodeWithTag("native-ledger").performScrollToNode(hasText("1 個品項待補品名"))
        ui.onNodeWithText("1 個品項待補品名").assertIsDisplayed()
        ui.onNodeWithText("個人支出 NT$ 180").assertExists()
        capture("native-ledger-missing-names-390")
    }
    @Test fun nativeNameEditorCancelsWithoutWritingAndSavesOnlyOptionalNames() {
        mount(MobilePage.LedgerDetail)
        ui.onNodeWithTag("ledger-item-names-edit").performClick()
        ui.onNodeWithTag("ledger-item-name-0").performTextReplacement("合成飯")
        ui.onNodeWithTag("ledger-item-names-cancel").performClick(); assertNull(saved)
        ui.onNodeWithTag("ledger-item-names-edit").performClick()
        ui.onNodeWithTag("ledger-item-name-0").assertTextContains("品項 1 品名（可留空）")
        ui.onNodeWithTag("ledger-item-names-save").performClick()
        assertEquals(mapOf<Int, String?>(0 to null), saved)
        ui.onNodeWithTag("ledger-item-names-edit").performClick()
        ui.onNodeWithTag("ledger-item-name-0").performTextReplacement("合成飯")
        ui.onNodeWithTag("ledger-item-names-save").performClick()
        assertEquals(mapOf(0 to "合成飯"), saved)
        assertTrue(ledgerObject(entry).getAsJsonObject("snapshot").getAsJsonObject("candidate").array("items")[0].asJsonObject["raw_name"].isJsonNull)
    }
    @Test fun metadataNameOverridesSnapshotAndVoidedEntriesCannotOpenNewEditor() {
        entry = jsonObject(entry).apply { add("display_item_names", com.google.gson.JsonArray().apply { add("已補飯") }); addProperty("status", "voided") }.toString()
        mount(MobilePage.LedgerDetail)
        ui.onNodeWithTag("native-ledger-detail").performScrollToNode(hasText("1. 已補飯"))
        ui.onNodeWithText("1. 已補飯").assertIsDisplayed()
        ui.onNodeWithTag("ledger-item-names-edit").assertDoesNotExist()
    }
}
