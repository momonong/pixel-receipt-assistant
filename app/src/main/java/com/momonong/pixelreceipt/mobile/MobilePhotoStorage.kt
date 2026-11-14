package com.momonong.pixelreceipt.mobile

import com.momonong.pixelreceipt.data.ingestion.ImageStore
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

internal data class CentralPhotoStorage(val evidenceBytes: Long, val evidenceCount: Long, val evidenceLimitBytes: Long,
    val databaseBytes: Long, val filesystemAvailableBytes: Long, val warning: String)
internal data class PhotoStorageState(val localBytes: Long = 0, val reclaimableBytes: Long = 0,
    val central: CentralPhotoStorage? = null, val busy: Boolean = false, val message: String? = null, val revision: Long = 0)
internal fun parsePhotoStorage(json: String): CentralPhotoStorage {
    val value = jsonObject(json)
    check(value.text("schema") == "pixelreceipt-storage-1")
    val warning = value.text("warning"); check(warning in setOf("ok", "near_limit", "full", "low_disk"))
    return CentralPhotoStorage(strictLong(value["evidence_bytes"]), strictLong(value["evidence_count"]), strictLong(value["evidence_limit_bytes"]),
        strictLong(value["database_bytes"]), strictLong(value["filesystem_available_bytes"]), warning)
}

/** A hash shared with any uncertain work is protected, including parent/child receipt links. */
internal fun reclaimablePhotos(state: MobileState): List<MobileImage> {
    fun stable(batch: MobileBatch): Boolean = !batch.deleted && batch.purpose == "purchase" &&
        batch.importComplete && batch.importReview?.requiresConfirmation != true &&
        batch.intakeId != null && batch.status == "succeeded" && !batch.syncRetryable &&
        batch.review?.saveAttempt == null && (batch.review?.confirmation == null || (batch.ledgerId != null && batch.draftStatus == "confirmed")) &&
        state.mutations.orEmpty().none { it.phase == "pending" && (it.batchId == batch.id || it.targetId in listOfNotNull(batch.intakeId, batch.draftId, batch.ledgerId)) }
    fun accounted(batch: MobileBatch): Boolean = batch.ledgerId != null && batch.draftStatus == "confirmed" && batch.ledgerStatus != "voided"
    fun completed(batch: MobileBatch): Boolean {
        if (!stable(batch)) return false
        if (!batch.isReceiptGroup) return accounted(batch)
        val children = state.batches.filter { it.id in batch.receiptIds.orEmpty() && it.parentBatchId == batch.id }
        return children.size == batch.receiptIds.orEmpty().size && children.all { !it.isReceiptGroup && stable(it) && accounted(it) }
    }
    val protected = state.batches.filterNot(::completed).flatMap { batch ->
        batch.images.map { it.hash } + batch.importReview?.let { it.originalSlots.filterNotNull() + it.selectedHashes + it.previousHashes }.orEmpty()
    }.toSet()
    return state.batches.filter(::completed).flatMap { it.images }.filter { it.hash !in protected }.distinctBy { it.hash }
}

/** Only used with the mobile ImageStore; no gallery or legacy ledger cache traversal. */
internal class MobilePhotoCache(private val images: ImageStore) {
    private fun root() = images.file("0".repeat(64)).parentFile!!
    fun bytes(): Long = root().listFiles()?.filter { it.isFile && it.name.matches(Regex("[0-9a-f]{64}")) }?.sumOf { it.length() } ?: 0
    fun localBytes(image: MobileImage): Long = images.file(image.hash).takeIf { it.isFile }?.length() ?: 0
    suspend fun reclaim(image: MobileImage, download: suspend (File) -> Unit, stillCurrent: () -> Boolean): Long = verified(image, download) {
        currentCoroutineContext().ensureActive()
        if (!stillCurrent()) throw MobileFailure("登入或收據狀態已變更；照片保留，請重試。")
        val local = images.file(image.hash)
        val bytes = local.takeIf { it.isFile }?.length() ?: return@verified 0
        if (!local.delete()) throw IOException("手機照片快取無法回收，請檢查儲存空間。")
        images.photosChanged()
        bytes
    }
    suspend fun recover(image: MobileImage, download: suspend (File) -> Unit, stillCurrent: () -> Boolean) {
        if (images.file(image.hash).isFile) return
        verified(image, download) { part ->
            currentCoroutineContext().ensureActive()
            if (!stillCurrent()) throw MobileFailure("登入已變更；照片下載已停止。")
            // Reuse validation/decoder, fsync and same-directory atomic publication.
            val saved = images.copy({ part.inputStream() }, ImageStore.MAX_BATCH_BYTES) { if (!stillCurrent()) throw MobileFailure("登入或收據狀態已變更，照片下載已停止。") }
            check(saved.hash == image.hash && saved.bytes == image.bytes && saved.mime == image.mime)
            images.photosChanged()
        }
    }
    private suspend fun <T> verified(image: MobileImage, download: suspend (File) -> Unit, action: suspend (File) -> T): T {
        require(image.bytes in 1..ImageStore.MAX_BYTES && image.mime in setOf("image/jpeg", "image/png", "image/webp"))
        val part = File(root(), "${UUID.randomUUID()}.part")
        try { download(part); verifyPhoto(part, image); currentCoroutineContext().ensureActive(); return action(part) }
        finally { if (part.exists() && !part.delete()) throw IOException("照片暫存清理失敗，請重新啟動 App。") }
    }
}
internal fun verifyPhoto(file: File, image: MobileImage) {
    if (!file.isFile || file.length() != image.bytes) throw IOException("中央照片大小不符，手機原圖保留。")
    val digest = MessageDigest.getInstance("SHA-256")
    val header = ByteArray(12)
    file.inputStream().use { input ->
        val first = input.read(header); if (first < 3) throw IOException("中央照片格式不符。")
        digest.update(header, 0, first)
        val buffer = ByteArray(64 * 1024)
        while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
    }
    val mime = when {
        header[0] == 0xff.toByte() && header[1] == 0xd8.toByte() && header[2] == 0xff.toByte() -> "image/jpeg"
        header.take(8).toByteArray().contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)) -> "image/png"
        header.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" && header.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WEBP" -> "image/webp"
        else -> null
    }
    if (mime != image.mime || digest.digest().joinToString("") { "%02x".format(it) } != image.hash) throw IOException("中央照片完整性不符，手機原圖保留。")
}
internal fun photoSize(bytes: Long): String = if (bytes < 1024 * 1024) "${bytes / 1024} KiB" else "%.1f MiB".format(java.util.Locale.ROOT, bytes / (1024.0 * 1024))
