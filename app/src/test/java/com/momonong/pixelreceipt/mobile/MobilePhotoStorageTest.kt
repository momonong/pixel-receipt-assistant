package com.momonong.pixelreceipt.mobile

import com.momonong.pixelreceipt.data.ingestion.ImageStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.UUID

class MobilePhotoStorageTest {
    private val image = MobileImage("a".repeat(64), "image/png", 10)
    private fun completed(id: String = UUID.randomUUID().toString()) = MobileBatch(id, images = listOf(image), importComplete = true, intakeId = UUID.randomUUID().toString(), status = "succeeded", uploadedHashes = listOf(image.hash), ledgerId = UUID.randomUUID().toString(), draftStatus = "confirmed")
    @Test fun unsentSharedImageProtectsCompletedPhoto() {
        val done = completed()
        assertEquals(listOf(image), reclaimablePhotos(MobileState(UUID.randomUUID().toString(), listOf(done))))
        assertTrue(reclaimablePhotos(MobileState(UUID.randomUUID().toString(), listOf(done, MobileBatch(UUID.randomUUID().toString(), images = listOf(image))))).isEmpty())
    }
    @Test fun unknownAndPendingSaveOrMutationProtectPhotos() {
        val done = completed()
        listOf("unknown", "processing", "queued", "failed").forEach { status -> assertTrue(reclaimablePhotos(MobileState(UUID.randomUUID().toString(), listOf(done.copy(status = status)))).isEmpty()) }
        val review = createReview(nativeDraft())
        val save = review.copy(saveAttempt = FrozenReviewSave("{}", 1, 0))
        val confirm = review.copy(confirmation = FrozenConfirmation(UUID.randomUUID().toString(), "{}", "{}", 1))
        listOf(done.copy(review = save), done.copy(review = confirm, ledgerId = null, draftStatus = "needs_review")).forEach { pending ->
            assertTrue(reclaimablePhotos(MobileState(UUID.randomUUID().toString(), listOf(pending))).isEmpty())
        }
        val interrupted = MobileBatch(UUID.randomUUID().toString(), importReview = MobileImportReview(previousHashes = listOf(image.hash)))
        assertTrue(reclaimablePhotos(MobileState(UUID.randomUUID().toString(), listOf(done, interrupted))).isEmpty())
        val mutation = ReceiptMutation("intake_delete", done.intakeId!!, done.id, UUID.randomUUID().toString(), "{}")
        assertTrue(reclaimablePhotos(MobileState(UUID.randomUUID().toString(), listOf(done), mutations = listOf(mutation))).isEmpty())
    }
    @Test fun remoteHashMismatchCannotEvictLocalPhoto() = runBlocking {
        val root = Files.createTempDirectory("photos").toFile()
        try {
            val images = ImageStore(root)
            images.file(image.hash).writeText("local copy")
            val cache = MobilePhotoCache(images)
            try { cache.reclaim(image, { file -> file.writeText("wrong data") }, { true }); fail("must reject mismatched bytes") } catch (_: Exception) { }
            assertEquals("local copy", images.file(image.hash).readText())
            assertEquals(listOf(image.hash), root.list()!!.toList())
        } finally { root.deleteRecursively() }
    }
    @Test fun changedSessionCannotEvictVerifiedPhoto() = runBlocking {
        val bytes = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        val original = MobileImage(sha256(bytes), "image/png", bytes.size.toLong())
        val root = Files.createTempDirectory("photos").toFile()
        try {
            val images = ImageStore(root); images.file(original.hash).writeBytes(bytes)
            try { MobilePhotoCache(images).reclaim(original, { it.writeBytes(bytes) }, { false }); fail("session changed") } catch (_: Exception) { }
            assertArrayEquals(bytes, images.file(original.hash).readBytes())
        } finally { root.deleteRecursively() }
    }
    @Test fun offlineRecoveryKeepsOriginalStateAndLeavesNoPartialFile() = runBlocking {
        val root = Files.createTempDirectory("photos").toFile()
        try {
            val images = ImageStore(root)
            try { MobilePhotoCache(images).recover(image, { it.writeText("partial"); throw java.io.IOException("offline") }, { true }); fail("offline") } catch (_: java.io.IOException) { }
            assertFalse(images.file(image.hash).exists()); assertEquals(0, root.list()!!.size)
        } finally { root.deleteRecursively() }
    }
    @Test fun capabilityOnlyChangesNewlyFrozenBodies() {
        val batch = completed().copy(intakeId = null, status = null, ledgerId = null, draftStatus = null)
        val fresh = freezeIntake(batch)
        assertEquals("receipt_groups_v1", jsonObject(fresh.body).array("capabilities")[0].asString)
        val old = FrozenIntake(UUID.randomUUID().toString(), "{\"legacy\":true}", sha256("{\"legacy\":true}".toByteArray()))
        assertSame(old, freezeIntake(batch.copy(frozen = old)))
    }
    @Test fun storageEndpointRejectsUntrustedNumbersAndUsesAuthenticatedFixedRoute() = runBlocking {
        val body = """{"schema":"pixelreceipt-storage-1","evidence_bytes":5,"evidence_count":1,"evidence_limit_bytes":100,"database_bytes":8,"filesystem_available_bytes":90,"warning":"ok"}"""
        val requests = mutableListOf<MobileRequest>()
        val api = HttpsMobileApi(MobileTransport { requests += it; body })
        assertEquals(5L, parsePhotoStorage(api.photoStorage("prm_fixture")).evidenceBytes)
        assertEquals("v1/storage", requests.single().path); assertEquals("prm_fixture", requests.single().token)
        assertTrue(allowedMobileRequest("v1/storage", "GET"))
        listOf(body.replace("\"evidence_bytes\":5", "\"evidence_bytes\":-1"), body.replace("\"evidence_bytes\":5", "\"evidence_bytes\":1.5"), body.replace("\"ok\"", "\"other\"")).forEach { bad ->
            try { parsePhotoStorage(bad); fail("bad storage accepted") } catch (_: IllegalStateException) { }
        }
    }
    @Test fun originalDownloadNeverFollowsRedirectsOrLeaksBearerInUrl() = runBlocking {
        val root = Files.createTempDirectory("photo-http").toFile()
        try {
            val connection = StubConnection(302)
            var calls = 0
            try {
                HttpsPhotoDownload { url ->
                    calls++; assertEquals("https", url.protocol); assertEquals("momonong.me", url.host)
                    assertEquals("/receipts/v1/evidence/${image.hash}/content", url.path); assertNull(url.query)
                    connection
                }.download("prm_fixture", image, File(root, "part"))
                fail("redirect accepted")
            } catch (error: MobileApiFailure) { assertEquals(302, error.statusCode) }
            assertEquals(1, calls); assertFalse(connection.instanceFollowRedirects)
            assertEquals("Bearer prm_fixture", connection.getRequestProperty("Authorization")); assertTrue(connection.disconnected)
        } finally { root.deleteRecursively() }
    }

    @Test fun originalDownloadChecksMimeSizeHashAndBoundBeforeKeepingAnyBytes() = runBlocking {
        val bytes = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        val expected = MobileImage(sha256(bytes), "image/png", bytes.size.toLong())
        val root = Files.createTempDirectory("photo-http-bytes").toFile()
        try {
            val target = File(root, "part")
            fun connection(body: ByteArray, mime: String = "image/png") = object : StubConnection(200) {
                override fun getContentType() = mime
                override fun getContentLengthLong() = -1L
                override fun getInputStream() = java.io.ByteArrayInputStream(body)
            }
            HttpsPhotoDownload { connection(bytes) }.download("prm_fixture", expected, target)
            assertArrayEquals(bytes, target.readBytes()); assertTrue(target.delete())
            for ((body, mime) in listOf(bytes + byteArrayOf(0) to "image/png", bytes.copyOf(4) to "image/png", ByteArray(8) to "image/png", bytes to "image/jpeg")) {
                try { HttpsPhotoDownload { connection(body, mime) }.download("prm_fixture", expected, target); fail("invalid download") }
                catch (_: java.io.IOException) { }
                assertFalse(target.exists())
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun recognitionSuccessStillNeedsConfirmedAccountingBeforeReclaim() {
        val done = completed()
        for (incomplete in listOf(done.copy(ledgerId = null, draftStatus = "needs_review"), done.copy(ledgerId = null), done.copy(draftStatus = "voided"), done.copy(purpose = "reference"), done.copy(deletedAt = 1))) {
            assertTrue(reclaimablePhotos(MobileState(UUID.randomUUID().toString(), listOf(incomplete))).isEmpty())
        }
        assertEquals(listOf(image), reclaimablePhotos(MobileState(UUID.randomUUID().toString(), listOf(done))))
    }
    @Test fun groupOnlyBecomesReclaimableAfterEveryChildIsConfirmed() {
        val parentId = UUID.randomUUID().toString()
        val childIds = List(2) { UUID.randomUUID().toString() }
        val parent = completed(parentId).copy(ledgerId = null, draftStatus = null, receiptIds = childIds)
        val children = childIds.map { completed(it).copy(parentBatchId = parentId) }
        fun state(values: List<MobileBatch>) = MobileState(UUID.randomUUID().toString(), listOf(parent) + values)
        assertTrue(reclaimablePhotos(state(children.map { it.copy(ledgerId = null, draftStatus = "needs_review") })).isEmpty())
        assertTrue(reclaimablePhotos(state(listOf(children[0], children[1].copy(ledgerId = null, draftStatus = "needs_review")))).isEmpty())
        assertTrue(reclaimablePhotos(state(listOf(children[0], children[1].copy(draftStatus = "voided")))).isEmpty())
        assertTrue(reclaimablePhotos(state(children.take(1))).isEmpty())
        assertEquals(listOf(image), reclaimablePhotos(state(children)))
    }

}
