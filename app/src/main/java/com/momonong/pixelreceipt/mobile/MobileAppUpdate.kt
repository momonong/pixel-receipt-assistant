package com.momonong.pixelreceipt.mobile

import com.momonong.pixelreceipt.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal const val MAX_UPDATE_BYTES = 128L * 1024 * 1024
internal const val UPDATE_PACKAGE = "com.momonong.pixelreceipt.mobile"

data class AppRelease(val versionCode: Long, val versionName: String, val sizeBytes: Long, val sha256: String, val signerSha256: String)

internal fun parseAppRelease(body: String): AppRelease = try {
    require(body.length <= 8192)
    val value = jsonObject(body)
    require(value.keySet() == setOf("version_code", "version_name", "package_name", "size_bytes", "sha256", "signer_sha256", "download_url"))
    require(value.text("package_name") == UPDATE_PACKAGE && value.text("download_url") == "/receipts/v1/mobile/apk")
    val version = strictLong(value["version_code"], 1, Int.MAX_VALUE.toLong())
    val size = strictLong(value["size_bytes"], 1, MAX_UPDATE_BYTES)
    val name = value.text("version_name").also { require(it.matches(Regex("[0-9]{1,4}\\.[0-9]{1,4}\\.[0-9]{1,4}"))) }
    val hash = value.text("sha256").also { require(it.matches(Regex("[0-9a-f]{64}"))) }
    val signer = value.text("signer_sha256").also { require(it.matches(Regex("[0-9a-f]{64}"))) }
    AppRelease(version, name, size, hash, signer)
} catch (_: RuntimeException) { throw IllegalArgumentException("更新資訊無效，請稍後重新檢查。") }

/** Only this download's part file is removed on failure; an older complete file stays intact. */
internal fun writeVerifiedUpdate(input: InputStream, target: File, release: AppRelease, progress: (Long) -> Unit, active: () -> Unit = { if (Thread.currentThread().isInterrupted) throw CancellationException() }) {
    val parent = requireNotNull(target.parentFile)
    val part = File(parent, target.name + ".part")
    try {
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("無法保存更新檔。")
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        FileOutputStream(part).use { output ->
            val buffer = ByteArray(64 * 1024)
            progress(0)
            while (true) {
                active()
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > release.sizeBytes || total > MAX_UPDATE_BYTES) throw IOException("更新檔大小不符，請重新檢查。")
                output.write(buffer, 0, count); digest.update(buffer, 0, count); progress(total)
            }
            active()
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            if (total != release.sizeBytes || hash != release.sha256) throw IOException("更新檔驗證失敗，請重新檢查。")
            output.fd.sync()
        }
        synchronized(UpdateApkFiles.lock) {
            active()
            if (!part.renameTo(target)) throw IOException("無法保存更新檔。")
        }
    } finally { part.delete() }
}

internal fun interface ApkDownload { suspend fun download(token: String, release: AppRelease, target: File, progress: (Long) -> Unit) }

internal class HttpsApkDownload(private val connectionFactory: (URL) -> HttpsURLConnection = { it.openConnection() as HttpsURLConnection }) : ApkDownload {
    override suspend fun download(token: String, release: AppRelease, target: File, progress: (Long) -> Unit): Unit = withTimeout(300_000) {
        require(token.isNotEmpty() && token.length <= 8192 && token.none(Char::isISOControl))
        suspendCancellableCoroutine { continuation ->
            val connection = AtomicReference<HttpsURLConnection?>()
            val future = AtomicReference<Future<*>?>()
            continuation.invokeOnCancellation { connection.getAndSet(null)?.disconnect(); future.get()?.cancel(true) }
            future.set(executor.submit {
                try {
                    fun active() { if (!continuation.isActive || Thread.currentThread().isInterrupted) throw CancellationException() }
                    active()
                    val http = connectionFactory(URL(MOBILE_BASE_URL + "v1/mobile/apk"))
                    connection.set(http); active()
                    http.instanceFollowRedirects = false; http.connectTimeout = 15_000; http.readTimeout = 30_000; http.useCaches = false
                    http.requestMethod = "GET"
                    http.setRequestProperty("Authorization", "Bearer $token")
                    http.setRequestProperty("Accept", "application/vnd.android.package-archive")
                    http.setRequestProperty("Accept-Encoding", "identity")
                    http.setRequestProperty("User-Agent", "PixelReceipt-Android/${BuildConfig.VERSION_NAME}")
                    if (http.responseCode != 200) throw MobileApiFailure(http.responseCode)
                    if (http.contentLengthLong >= 0 && http.contentLengthLong != release.sizeBytes) throw IOException("更新檔已變更，請重新檢查。")
                    if (http.contentEncoding?.let { it != "identity" } == true) throw IOException("更新檔格式不符。")
                    http.inputStream.use { writeVerifiedUpdate(it, target, release, progress, ::active) }
                    if (continuation.isActive) continuation.resume(Unit)
                } catch (error: Exception) { if (continuation.isActive) continuation.resumeWithException(error) }
                finally { connection.getAndSet(null)?.disconnect() }
            })
            if (!continuation.isActive) future.get()?.cancel(true)
        }
    }
    companion object { private val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "receipt-update").apply { isDaemon = true } } }
}
