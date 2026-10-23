package com.momonong.pixelreceipt.mobile

import android.app.Application
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.google.gson.JsonParser
import com.momonong.pixelreceipt.data.ingestion.ImageStore
import com.momonong.pixelreceipt.data.ingestion.ImportInput
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MobileRecoveryTest {
    private lateinit var root: File
    private lateinit var store: MobileStore
    private lateinit var images: ImageStore
    private val vault = MemoryVault()
    private val api = FakeMobileApi()
    private lateinit var repo: MobileRepository
    @Before fun setup() {
        root = File(ApplicationProvider.getApplicationContext<Application>().cacheDir, UUID.randomUUID().toString()).apply { mkdirs() }
        reopen()
    }
    private fun reopen() { store = MobileStore(root); images = ImageStore(File(root, "images")); repo = MobileRepository(store, images, vault, api) }
    @After fun cleanup() { root.deleteRecursively() }
    private fun png(color: Int): ByteArray = ByteArrayOutputStream().use { output ->
        Bitmap.createBitmap(4, 3, Bitmap.Config.ARGB_8888).apply { eraseColor(color); compress(Bitmap.CompressFormat.PNG, 100, output); recycle() }
        output.toByteArray()
    }
    private suspend fun imported(): MobileBatch {
        val first = png(0xff123456.toInt()); val second = png(0xffaabbcc.toInt())
        val id = UUID.randomUUID().toString()
        val batch = repo.import(id, "第一張是收據；第二張價標是代買", listOf(first, second).map { bytes -> ImportInput { ByteArrayInputStream(bytes) } })
        assertArrayEquals(first, images.file(batch.images[0].hash).readBytes())
        assertArrayEquals(second, images.file(batch.images[1].hash).readBytes())
        return batch
    }
    @Test fun originalsOrderRolesAndNotesSurviveReopenAndFreeze() = runBlocking {
        val original = imported()
        repo.edit(original.id) { old -> old.copy(images = old.images.mapIndexed { i, image -> if (i == 1) image.copy(role = "price_tag", notes = "牙膏價標") else image }) }
        val frozen = store.freeze(original.id)
        reopen(); repo.initialize()
        val restored = store.batch(original.id)
        assertEquals(frozen, restored)
        assertEquals("代買", restored.userNotes.takeLast(2))
        val body = JsonParser.parseString(restored.frozen!!.body).asJsonObject
        assertEquals("price_tag", body.getAsJsonArray("evidence")[1].asJsonObject["role"].asString)
        assertEquals("牙膏價標", body.getAsJsonArray("evidence")[1].asJsonObject["notes"].asString)
        assertEquals(original.images.map { it.hash }, restored.images.map { it.hash })
        assertEquals(restored.frozen, store.freeze(original.id).frozen)
        repo.edit(original.id) { it.copy(userNotes = "不可覆蓋已送出的說明") }
        assertEquals(restored.userNotes, store.batch(original.id).userNotes)
    }
    @Test fun uncertainWriteReopensAndRetriesIdenticalKeyAndBody() = runBlocking {
        val batch = imported()
        api.submitError = IOException("uncertain response")
        api.beforeSubmit = { frozen -> assertEquals(frozen, MobileStore(root).batch(batch.id).frozen) }
        try { repo.sync(batch.id); fail() } catch (_: IOException) { }
        val first = store.batch(batch.id)
        assertNotNull(first.frozen); assertNull(first.intakeId)
        reopen(); repo.initialize(); api.submitError = null
        repo.sync(batch.id)
        assertEquals(2, api.submissions.size)
        assertEquals(api.submissions[0], api.submissions[1])
        assertEquals(first.frozen, store.batch(batch.id).frozen)
        assertEquals(api.result.id, store.batch(batch.id).intakeId)
        assertEquals(1, store.load().batches.size)
        assertEquals(2, api.uploads.size) // Uncertain intake response does not re-upload acknowledged originals.
    }
    @Test fun unauthorizedPreservesEverythingAndRequiresRelogin() = runBlocking {
        val batch = imported()
        repo.edit(batch.id) { it.copy(userNotes = "保留說明", images = it.images.map { image -> image.copy(notes = "保留逐圖說明") }) }
        api.uploadError = MobileApiFailure(401)
        try { repo.sync(batch.id); fail() } catch (error: MobileApiFailure) { assertEquals(401, error.statusCode) }
        val before = store.batch(batch.id)
        assertNull(vault.read()); assertNotNull(before.frozen)
        reopen(); assertFalse(repo.initialize())
        assertEquals(before, store.batch(batch.id))
        assertTrue(before.images.all { images.file(it.hash).isFile })
        api.uploadError = null; repo.login("test-password")
        repo.sync(batch.id)
        assertEquals(before.frozen, store.batch(batch.id).frozen)
    }
    @Test fun knownUnknownJobOnlyGetsStatusAndNeverPostsAgain() = runBlocking {
        val batch = imported()
        api.result = api.result.copy(status = "unknown")
        repo.sync(batch.id)
        val frozen = store.batch(batch.id).frozen
        reopen(); repo.initialize(); repo.sync(batch.id)
        assertEquals(1, api.submissions.size)
        assertEquals(1, api.statusCalls)
        assertEquals(frozen, store.batch(batch.id).frozen)
        assertEquals("unknown", store.batch(batch.id).status)
    }
    @Test fun cancellationRetainsSubmissionForManualRecovery() = runBlocking {
        val batch = imported()
        val entered = CompletableDeferred<Unit>()
        api.beforeSubmit = { entered.complete(Unit); awaitCancellation() }
        val task = launch { repo.sync(batch.id) }
        entered.await(); task.cancelAndJoin()
        val frozen = store.batch(batch.id).frozen
        assertNotNull(frozen); assertNull(store.batch(batch.id).intakeId)
        reopen(); repo.initialize()
        assertEquals(frozen, store.batch(batch.id).frozen)
        assertTrue(store.batch(batch.id).message!!.contains("已停止"))
    }
    @Test fun fastNotesThenImmediateSendFreezesTheLatestOrderedEdit() = runBlocking {
        val batch = imported()
        val commands = MobileCommands(this)
        val edits = (1..50).map { index -> commands.launch {
            if (index == 1) delay(20) // Force later UI events to queue while the first disk edit is pending.
            repo.edit(batch.id) { it.copy(userNotes = "說明 $index") }
        } }
        val send = commands.launch { repo.sync(batch.id) }
        edits.joinAll(); send.join()
        val body = JsonParser.parseString(store.batch(batch.id).frozen!!.body).asJsonObject
        assertEquals("說明 50", body["user_notes"].asString)
    }
    @Test fun acknowledgedImagesResumeAfterProcessDeathWithoutReupload() = runBlocking {
        val batch = imported()
        val entered = CompletableDeferred<Unit>()
        api.beforeUpload = { hash -> if (hash == batch.images[1].hash) { entered.complete(Unit); awaitCancellation() } }
        val task = launch { repo.sync(batch.id) }
        entered.await(); task.cancelAndJoin()
        val before = store.batch(batch.id)
        assertEquals(listOf(batch.images[0].hash), before.uploadedHashes)
        reopen(); repo.initialize(); api.beforeUpload = {}
        repo.sync(batch.id)
        assertEquals(before.frozen, store.batch(batch.id).frozen)
        assertEquals(1, api.uploads.count { it == batch.images[0].hash })
        assertEquals(2, api.uploads.count { it == batch.images[1].hash })
        assertEquals(batch.images.map { it.hash }, store.batch(batch.id).uploadedHashes)
    }
    @Test fun importOperationIsDeduplicatedAndStaleCasCannotOverwrite() = runBlocking {
        val batch = imported()
        val duplicate = repo.import(batch.id, "不應覆寫", listOf(ImportInput { error("must not reopen URI") }))
        assertEquals(batch, duplicate)
        store.update(batch.id, batch.revision) { it.copy(userNotes = "新的說明") }
        try { store.update(batch.id, batch.revision) { it.copy(userNotes = "舊說明") }; fail() } catch (_: MobileFailure) { }
        assertEquals("新的說明", MobileStore(root).batch(batch.id).userNotes)
    }
    @Test fun interruptedImportKeepsCopiedOriginalAndCanBeCompleted() = runBlocking {
        val first = png(0xffaaffbb.toInt()); val id = UUID.randomUUID().toString()
        try { repo.import(id, "原始分享說明", listOf(ImportInput { ByteArrayInputStream(first) }, ImportInput { throw IOException() })); fail() } catch (_: IOException) { }
        reopen(); repo.initialize()
        assertFalse(store.batch(id).importComplete)
        assertArrayEquals(first, images.file(store.batch(id).images.single().hash).readBytes())
        try { store.freeze(id); fail() } catch (_: IllegalArgumentException) { }
        repo.import(id, "不應覆蓋", listOf(ImportInput { ByteArrayInputStream(first) }, ImportInput { ByteArrayInputStream(png(0xff112233.toInt())) }))
        assertFalse(store.batch(id).importComplete)
        repo.confirmImport(id, store.batch(id).revision)
        assertTrue(store.batch(id).importComplete)
        assertEquals(2, store.batch(id).images.size)
        assertEquals("原始分享說明", store.batch(id).userNotes)
    }
    @Test fun interruptedImportsRequireMembershipReviewForSubsetUnrelatedReverseAndDuplicateSelections() = runBlocking {
        val a = png(0xffaa1122.toInt()); val b = png(0xffbb3344.toInt()); val c = png(0xffcc5566.toInt())
        listOf(listOf(a), listOf(c), listOf(b, a), listOf(a, a, b)).forEach { selection ->
            val id = UUID.randomUUID().toString()
            try { repo.import(id, "保留原說明", listOf(ImportInput { ByteArrayInputStream(a) }, ImportInput { throw IOException("expired URI") })); fail() } catch (_: IOException) { }
            repo.edit(id) { old -> old.copy(images = old.images.map { it.copy(role = "price_tag", notes = "既有角色與說明") }) }
            reopen(); repo.initialize() // Source URIs are not assumed to survive process death.
            val resumed = repo.import(id, "不可覆寫", selection.map { bytes -> ImportInput { ByteArrayInputStream(bytes) } })
            assertFalse(resumed.importComplete)
            assertEquals(2, resumed.importReview!!.expectedCount)
            assertEquals(listOf(sha256(a), null), resumed.importReview.originalSlots)
            assertEquals(selection.map(::sha256), resumed.importReview.selectedHashes)
            assertEquals("保留原說明", resumed.userNotes)
            assertEquals("既有角色與說明", resumed.images.first { it.hash == sha256(a) }.notes)
            assertEquals("price_tag", resumed.images.first { it.hash == sha256(a) }.role)
            try { store.freeze(id); fail() } catch (_: IllegalArgumentException) { }
            assertArrayEquals(a, images.file(sha256(a)).readBytes())
            val confirmed = repo.confirmImport(id, resumed.revision)
            assertTrue(confirmed.importComplete)
            if (confirmed.images.size > 1) {
                val revised = repo.edit(id) { it.copy(images = it.images.reversed()) }
                assertFalse(revised.importComplete)
                try { repo.confirmImport(id, confirmed.revision); fail() } catch (_: MobileFailure) { }
                val removed = repo.edit(id) { it.copy(images = it.images.filterNot { image -> image.hash == sha256(a) }) }
                assertFalse(removed.importComplete)
                assertTrue(images.file(sha256(a)).isFile) // Association removal never garbage-collects an original.
            }
        }
    }
    @Test fun processDeathDuringImportKeepsExpectedSlotsAndNeedsReviewBeforeFreeze() = runBlocking {
        val a = png(0xffbb1122.toInt()); val b = png(0xffcc3344.toInt()); val id = UUID.randomUUID().toString()
        val death = object : Error("simulated process stop") {}
        try {
            repo.import(id, "原分享說明", listOf(ImportInput { ByteArrayInputStream(a) }, ImportInput { throw death }))
            fail()
        } catch (error: Error) { if (error !== death) throw error }
        reopen(); repo.initialize()
        val restored = store.batch(id)
        assertEquals(listOf(sha256(a), null), restored.importReview!!.originalSlots)
        assertFalse(restored.importComplete)
        val resumed = repo.import(id, "", listOf(ImportInput { ByteArrayInputStream(b) }))
        assertFalse(resumed.importComplete)
        assertEquals(listOf(sha256(a), sha256(b)), resumed.images.map { it.hash })
        try { store.freeze(id); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun freshImportPersistsExpectedOrderBeforeOpeningUrisAndLegacyEmptyImportNeedsConfirmation() = runBlocking {
        val a = png(0xffbd1122.toInt()); val id = UUID.randomUUID().toString()
        repo.import(id, "", listOf(ImportInput {
            assertEquals(listOf<String?>(null), store.batch(id).importReview!!.originalSlots)
            assertFalse(store.batch(id).importComplete)
            ByteArrayInputStream(a)
        }))
        assertTrue(store.batch(id).importComplete)
        val legacy = store.create(UUID.randomUUID().toString(), "舊中斷批次")
        assertNull(legacy.importReview)
        val resumed = repo.import(legacy.id, "", listOf(ImportInput { ByteArrayInputStream(a) }))
        assertFalse(resumed.importComplete)
        assertNull(resumed.importReview!!.expectedCount)
        assertTrue(resumed.importReview.originalSlots.isEmpty())
        try { store.freeze(legacy.id); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun backupRecoveryAndOversizedWriteLeaveReadableOriginal() {
        store.load()
        val batch = store.create(UUID.randomUUID().toString(), "已保存")
        val base = File(root, "state.json")
        assertTrue(base.renameTo(File(root, "state.json.bak")))
        assertEquals("已保存", MobileStore(root).batch(batch.id).userNotes)
        try { store.update(batch.id) { it.copy(userNotes = "x".repeat(17 * 1024 * 1024)) }; fail() } catch (_: MobileFailure) { }
        assertEquals("已保存", MobileStore(root).batch(batch.id).userNotes)
    }
    @Test fun corruptStoreFailsClosedAndIsNotReset() {
        val data = "invalid-json".toByteArray(); File(root, "state.json").writeBytes(data)
        try { store.load(); fail() } catch (_: MobileFailure) { }
        assertArrayEquals(data, File(root, "state.json").readBytes())
    }
    @Test fun boundedReaderStopsBeforeConsumingOversizedInput() {
        val input = ByteArrayInputStream(ByteArray(100_000))
        try { readBounded(input, 10); fail() } catch (_: IllegalStateException) { }
        assertEquals(99_989, input.available())
    }
}

internal class MemoryVault : TokenVault {
    private var token: String? = "test-session"
    override fun read() = token
    override fun save(token: String) { this.token = token }
    override fun clear() { token = null }
}
internal class FakeMobileApi : MobileApi {
    var submitError: Exception? = null
    var uploadError: Exception? = null
    var beforeSubmit: suspend (FrozenIntake) -> Unit = {}
    var beforeUpload: suspend (String) -> Unit = {}
    val uploads = mutableListOf<String>()
    var result = ServerIntake(UUID.randomUUID().toString(), "queued", null, "{}")
    val submissions = mutableListOf<FrozenIntake>()
    var statusCalls = 0
    var loginCalls = 0
    var recognitionCalls = 0
    override suspend fun login(deviceId: String, password: String): String { loginCalls++; return "new-test-session" }
    override suspend fun logout(token: String) = Unit
    override suspend fun recognition(token: String): Map<String, EngineAvailability> { recognitionCalls++; return mapOf("broker" to EngineAvailability(false, "recognition_not_configured")) }
    override suspend fun upload(token: String, image: MobileImage, file: File): String { uploads += image.hash; beforeUpload(image.hash); uploadError?.let { throw it }; return image.hash }
    override suspend fun submit(token: String, frozen: FrozenIntake): ServerIntake { beforeSubmit(frozen); submissions += frozen; submitError?.let { throw it }; return result }
    override suspend fun status(token: String, id: String): ServerIntake { statusCalls++; return result }
}
