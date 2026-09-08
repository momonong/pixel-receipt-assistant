package com.momonong.pixelreceipt.data.ingestion

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

class ImportFailure(message: String) : IOException(message)
data class StoredImage(val hash: String, val bytes: Long, val mime: String, val width: Int, val height: Int)

/** Only the ingestion coordinator may mutate this directory; it serializes publication and GC. */
class ImageStore(private val root: File) {
    companion object {
        const val MAX_IMAGES = 20
        const val MAX_BYTES = 20L * 1024 * 1024
        const val MAX_BATCH_BYTES = 100L * 1024 * 1024
        const val MAX_STORE_BYTES = 1024L * 1024 * 1024
        const val MAX_PIXELS = 50_000_000L
    }

    init { check(root.isDirectory || root.mkdirs()) }
    fun file(hash: String): File {
        require(hash.matches(Regex("[0-9a-f]{64}")))
        return File(root, hash)
    }

    suspend fun copy(open: () -> InputStream?, remainingBytes: Long): StoredImage {
        val temp = File(root, "${UUID.randomUUID()}.part")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var bytes = 0L
            val limit = minOf(MAX_BYTES, remainingBytes)
            val available = MAX_STORE_BYTES - (root.listFiles()?.sumOf { it.length() } ?: 0L)
            open()?.use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count == -1) break
                        if (count == 0) continue
                        bytes += count
                        if (bytes > limit) throw ImportFailure("圖片超過 20 MiB，或本批超過 100 MiB。")
                        if (bytes > available) throw ImportFailure("本機圖片已達 1 GiB 上限，無法再匯入。")
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            } ?: throw ImportFailure("無法讀取圖片，請重新選取。")
            if (bytes == 0L) throw ImportFailure("圖片是空白檔案。")
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(temp.path, bounds)
            val mime = bounds.outMimeType ?: throw ImportFailure("圖片已損壞或格式不支援。")
            if (mime !in setOf("image/jpeg", "image/png", "image/webp")) {
                throw ImportFailure("目前支援 JPEG、PNG 與 WebP，請先轉換此圖片。")
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
                bounds.outWidth.toLong() * bounds.outHeight > MAX_PIXELS ||
                maxOf(bounds.outWidth, bounds.outHeight) > 20_000
            ) throw ImportFailure("圖片尺寸無效或超過 5,000 萬像素／單邊 20,000 像素。")
            checkContainer(temp, mime)
            decode(temp, 1024)?.recycle() ?: throw ImportFailure("圖片已損壞，無法解碼。")
            currentCoroutineContext().ensureActive()
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            val destination = file(hash)
            if (destination.exists()) {
                val existingDigest = MessageDigest.getInstance("SHA-256")
                destination.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        existingDigest.update(buffer, 0, count)
                    }
                }
                if (destination.length() != bytes || existingDigest.digest().joinToString("") { "%02x".format(it) } != hash) {
                    throw ImportFailure("本機原圖完整性檢查失敗。")
                }
            } else if (!temp.renameTo(destination)) throw ImportFailure("無法保存原圖，請確認可用儲存空間。")
            return StoredImage(hash, bytes, mime, bounds.outWidth, bounds.outHeight)
        } finally {
            if (temp.exists() && !temp.delete()) throw ImportFailure("暫存圖片清理失敗，請重新啟動 App。")
        }
    }

    /** Reject obvious truncated containers also on API 26/27, whose decoder is permissive. */
    private fun checkContainer(file: File, mime: String) {
        java.io.RandomAccessFile(file, "r").use { input ->
            if (mime == "image/jpeg") {
                if (input.length() < 4) throw ImportFailure("JPEG 不完整。")
                input.seek(input.length() - 2)
                if (input.readUnsignedShort() != 0xffd9) throw ImportFailure("JPEG 不完整。")
            }
            if (mime == "image/png") {
                if (input.length() < 20) throw ImportFailure("PNG 不完整。")
                input.seek(input.length() - 12)
                if (input.readInt() != 0 || input.readInt() != 0x49454e44 || input.readInt() != 0xae426082.toInt()) {
                    throw ImportFailure("PNG 不完整。")
                }
            }
            if (mime == "image/webp") {
                input.seek(4)
                val length = Integer.reverseBytes(input.readInt()).toLong() and 0xffffffffL
                if (length + 8 != input.length()) throw ImportFailure("WebP 不完整。")
            }
        }
    }

    fun cleanup(referenced: Set<String>) {
        root.listFiles()?.forEach {
            if (it.name !in referenced && !it.delete()) throw ImportFailure("未完成的圖片清理失敗，請重新啟動 App。")
        }
    }

    fun preview(hash: String): Bitmap? = decode(file(hash), 1600)

    private fun decode(file: File, maxSide: Int): Bitmap? {
        if (Build.VERSION.SDK_INT >= 28) {
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setOnPartialImageListener { false }
                val scale = maxOf(info.size.width, info.size.height).toFloat() / maxSide
                if (scale > 1) decoder.setTargetSize(
                    maxOf(1, (info.size.width / scale).toInt()), maxOf(1, (info.size.height / scale).toInt()),
                )
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
        val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val exif = ExifInterface(file)
        val matrix = Matrix().apply {
            if (exif.isFlipped) postScale(-1f, 1f)
            postRotate(exif.rotationDegrees.toFloat())
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also {
            if (it !== bitmap) bitmap.recycle()
        }
    }
}

fun ContentResolver.openSharedImage(uri: Uri): InputStream? {
    if (uri.scheme != "content") throw ImportFailure("僅接受系統提供的 content 圖片，請從相簿重新選取。")
    return openInputStream(uri)
}
