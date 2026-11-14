package com.momonong.pixelreceipt.mobile

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.ui.graphics.asAndroidBitmap
import android.content.ComponentName
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import com.google.gson.JsonArray
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MobileReceiptGroupsRenderingTest {
    @get:Rule val ui = createEmptyComposeRule()
    private lateinit var scenario: ActivityScenario<ComponentActivity>
    private lateinit var directory: File
    private lateinit var images: ImageStore
    private lateinit var original: MobileImage
    private val parentId = UUID.randomUUID().toString()
    private val ids = List(2) { UUID.randomUUID().toString() }
    private val parent get() = MobileBatch(parentId, images = listOf(original), importComplete = true,
        intakeId = UUID.randomUUID().toString(), status = "succeeded", receiptIds = ids)
    private fun children() = ids.mapIndexed { index, id -> MobileBatch(id, images = parent.images, importComplete = true, intakeId = id,
        status = "succeeded", draftId = UUID.randomUUID().toString(), draftStatus = "needs_review", parentBatchId = parentId,
        receiptInfo = MobileReceiptInfo("r${index + 1}", if (index == 0) "左側收據" else "右側收據", listOf(ReceiptRegion(1, listOf(index * 500, 0, (index + 1) * 500, 1000))), if (index == 0) "advance" else "self"),
        review = createReview(nativeDraft()).let { review -> review.copy(editorJson = review.editor.apply { getAsJsonObject("candidate").addProperty("printed_total_yuan", if (index == 0) "125" else "230")
            add("line_decisions", JsonArray().apply { add(wholeDecision(0, if (index == 0) "advance" else "self")) }) }.toString()) }) }
    @Before fun setup() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        directory = File(context.cacheDir, UUID.randomUUID().toString()).apply { mkdirs() }; images = ImageStore(directory)
        // Use a real saved synthetic original, so the production preview/crop path is rendered.
        val bitmap = Bitmap.createBitmap(400, 320, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.rgb(230, 244, 239))
        canvas.drawRect(200f, 0f, 400f, 320f, Paint().apply { color = android.graphics.Color.rgb(255, 244, 220) })
        val paper = Paint().apply { color = android.graphics.Color.WHITE }
        canvas.drawRect(12f, 12f, 188f, 308f, paper); canvas.drawRect(212f, 12f, 388f, 308f, paper)
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.BLACK; textSize = 20f }
        listOf("LEFT RECEIPT", "TOTAL 125", "ADVANCE").forEachIndexed { index, text -> canvas.drawText(text, 20f, 50f + index * 60f, ink) }
        listOf("RIGHT RECEIPT", "TOTAL 230", "SELF").forEachIndexed { index, text -> canvas.drawText(text, 220f, 50f + index * 60f, ink) }
        val bytes = ByteArrayOutputStream().use { output -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); output.toByteArray() }
        bitmap.recycle()
        val saved = images.copy({ ByteArrayInputStream(bytes) }, ImageStore.MAX_BATCH_BYTES)
        original = MobileImage(saved.hash, saved.mime, saved.bytes)
        shadowOf(context.packageManager).addActivityIfNotPresent(ComponentName(context, ComponentActivity::class.java))
        scenario = ActivityScenario.launch(Intent(context, ComponentActivity::class.java))
    }
    @After fun cleanup() { scenario.close(); directory.deleteRecursively() }
    private fun mount(state: () -> MobileUiState, actions: MobileActions = MobileActions(), font: Float = 1f) {
        scenario.onActivity { it.setContent { PixelReceiptTheme(darkTheme = false, dynamicColor = false) {
            CompositionLocalProvider(LocalDensity provides Density(1f, font)) { MobileContent(state(), images, actions) }
        } } }
        ui.waitForIdle()
    }
    @Test fun regionalThumbnailKeepsEachReceiptSeparateAndPreservesSourcePixels() {
        val source = Bitmap.createBitmap(100, 80, Bitmap.Config.ARGB_8888)
        Canvas(source).apply {
            drawColor(android.graphics.Color.RED)
            drawRect(50f, 0f, 100f, 80f, Paint().apply { color = android.graphics.Color.BLUE })
        }
        val left = regionThumbnail(source, ReceiptRegion(1, listOf(0, 0, 500, 1000)))
        val right = regionThumbnail(source, ReceiptRegion(1, listOf(500, 0, 1000, 1000)))
        assertEquals(50, left.width); assertEquals(50, right.width)
        assertEquals(android.graphics.Color.RED, left.getPixel(25, 40))
        assertEquals(android.graphics.Color.BLUE, right.getPixel(25, 40))
        assertEquals(100, source.width); assertEquals(android.graphics.Color.RED, source.getPixel(25, 40))
        source.recycle(); left.recycle(); right.recycle()
    }
    private fun awaitThumbnail(label: String) {
        ui.waitUntil(timeoutMillis = 5000) { ui.onAllNodesWithContentDescription("$label，收據所在區域").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithContentDescription("$label，收據所在區域").assertIsDisplayed()
    }
    private fun capture(name: String) {
        val file = File(System.getProperty("pixelreceipt.screenshots"), "$name.png"); file.parentFile!!.mkdirs()
        val bitmap = ui.onRoot().captureToImage().asAndroidBitmap()
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
    @Test fun groupedCardsOpenIndependentNativeReviewAndChildrenStayOutOfInbox() {
        val batches = listOf(parent) + children()
        val current = mutableStateOf(MobileUiState(batches = batches, selectedId = parentId, page = MobilePage.Detail, signedIn = true))
        var opened: String? = null
        mount({ current.value }, MobileActions(openReceipt = { id ->
            opened = id; current.value = current.value.copy(selectedId = id, page = MobilePage.Review)
        }))
        awaitThumbnail("左側收據"); awaitThumbnail("右側收據")
        ui.onNodeWithTag("group-receipt-${ids[0]}").assert(hasText("代買"))
        ui.onNodeWithTag("group-receipt-${ids[1]}").assert(hasText("自己用"))
        capture("multi-receipt-group-390")
        ui.onNodeWithTag("group-receipt-${ids[0]}").assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(ids[0], opened)
        ui.onNodeWithTag("native-review").assertExists()
        ui.runOnIdle { current.value = current.value.copy(selectedId = parentId, page = MobilePage.Inbox) }
        ui.onNodeWithTag("mobile-batch-$parentId").assertExists()
        ids.forEach { ui.onNodeWithTag("mobile-batch-$it").assertDoesNotExist() }
        ui.onNodeWithText("2 張收據 · 0 已入帳 · 2 待處理").assertExists()
    }
    @Test @Config(qualifiers = "w320dp-h740dp-mdpi") fun narrowLargeTextKeepsSecondReceiptAndSafeParentDeleteReachable() {
        val state = MobileUiState(batches = listOf(parent) + children(), selectedId = parentId, page = MobilePage.Detail, signedIn = true)
        var opened: String? = null
        mount({ state }, MobileActions(openReceipt = { opened = it }), font = 1.5f)
        ui.onNodeWithTag("mobile-detail").performScrollToNode(hasTestTag("group-receipt-${ids[1]}"))
        awaitThumbnail("右側收據")
        ui.onNodeWithTag("group-receipt-${ids[1]}").assert(hasText("自己用"))
        capture("multi-receipt-group-320-font150")
        ui.onNodeWithTag("group-receipt-${ids[1]}").assertIsDisplayed().performClick()
        assertEquals(ids[1], opened)
        ui.onNodeWithTag("mobile-detail").performScrollToIndex(0)
        ui.onNodeWithTag("receipt-more-$parentId").performClick()
        ui.onNodeWithTag("receipt-action-$parentId").performClick()
        ui.onNodeWithText(GROUP_DELETE_EXPLANATION).assertExists()
        ui.onNodeWithTag("receipt-action-confirm").assertDoesNotExist()
        ui.onNodeWithTag("receipt-action-cancel").assertIsDisplayed()
    }
    @Test fun completedPhotoCanBeReclaimedAndRecoveredWithExactBytesAndCropRerender() = runBlocking {
        val bytes = images.file(original.hash).readBytes()
        val cache = MobilePhotoCache(images)
        val state = MobileUiState(batches = listOf(parent) + children(), selectedId = parentId, page = MobilePage.Detail, signedIn = true)
        mount({ state })
        awaitThumbnail("左側收據")
        assertEquals(bytes.size.toLong(), cache.reclaim(original, { it.writeBytes(bytes) }, { true }))
        assertFalse(images.file(original.hash).exists())
        ui.waitForIdle()
        cache.recover(original, { it.writeBytes(bytes) }, { true })
        awaitThumbnail("左側收據")
        assertArrayEquals(bytes, images.file(original.hash).readBytes())
        assertEquals(2L, images.changes.value)
    }
    @Test @Config(qualifiers = "w320dp-h740dp-mdpi") fun settingsPhotoSpaceRemainsReadableAtLargeFontAndRecoveryIsExplicit() {
        val state = MobileUiState(page = MobilePage.Settings, signedIn = true,
            photoStorage = PhotoStorageState(localBytes = 600L * 1024 * 1024, reclaimableBytes = 400L * 1024 * 1024,
                central = CentralPhotoStorage(8L * 1024 * 1024 * 1024, 3200, 10L * 1024 * 1024 * 1024, 1024, 900L * 1024 * 1024, "near_limit")))
        var reclaimed = false
        mount({ state }, MobileActions(reclaimPhotos = { reclaimed = true }), font = 1.5f)
        ui.onNodeWithTag("mobile-settings-page").performScrollToIndex(2)
        capture("photo-storage-settings-320-font150-top")
        ui.onNodeWithTag("mobile-settings-page").performScrollToNode(hasTestTag("photo-storage-reclaim"))
        ui.onNodeWithTag("photo-storage-reclaim").assertIsDisplayed().assertIsEnabled().assertHeightIsAtLeast(48.dp)
        capture("photo-storage-settings-320-font150-action")
        ui.onNodeWithTag("photo-storage-reclaim").performClick()
        assertTrue(reclaimed)
    }

    @Test fun recoveryChecksSessionAgainImmediatelyBeforeAtomicPublication() = runBlocking {
        val bytes = images.file(original.hash).readBytes()
        assertTrue(images.file(original.hash).delete())
        var checks = 0
        try { MobilePhotoCache(images).recover(original, { it.writeBytes(bytes) }, { ++checks == 1 }); fail("changed session must stop publication") }
        catch (_: MobileFailure) { }
        assertEquals(2, checks); assertFalse(images.file(original.hash).exists())
        assertEquals(0, directory.listFiles()!!.size)
    }
    @Test fun failedCachePublicationLeavesNoPartialAndDoesNotAlterLedgerState() = runBlocking {
        val bytes = images.file(original.hash).readBytes()
        assertTrue(images.file(original.hash).delete()); assertTrue(images.file(original.hash).mkdir())
        try { MobilePhotoCache(images).recover(original, { it.writeBytes(bytes) }, { true }); fail("unwritable destination") }
        catch (_: java.io.IOException) { }
        assertTrue(images.file(original.hash).isDirectory)
        assertEquals(listOf(original.hash), directory.list()!!.toList())
        assertEquals(0L, images.changes.value)
    }

}
