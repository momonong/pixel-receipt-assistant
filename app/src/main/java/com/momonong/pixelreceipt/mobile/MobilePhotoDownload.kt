package com.momonong.pixelreceipt.mobile

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.IOException
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal fun interface PhotoDownload { suspend fun download(token: String, image: MobileImage, target: File) }
internal class HttpsPhotoDownload(private val connectionFactory: (URL) -> HttpsURLConnection = { it.openConnection() as HttpsURLConnection }) : PhotoDownload {
    override suspend fun download(token: String, image: MobileImage, target: File): Unit = withTimeout(90_000) {
        require(token.isNotEmpty() && token.length <= 8192 && token.none(Char::isISOControl))
        require(image.hash.matches(Regex("[0-9a-f]{64}")) && image.bytes in 1..com.momonong.pixelreceipt.data.ingestion.ImageStore.MAX_BYTES)
        suspendCancellableCoroutine { continuation ->
            val connection = AtomicReference<HttpsURLConnection?>()
            val future = AtomicReference<Future<*>?>()
            continuation.invokeOnCancellation { connection.getAndSet(null)?.disconnect(); future.get()?.cancel(true) }
            future.set(executor.submit {
                var completed = false
                try {
                    fun active() { if (!continuation.isActive || Thread.currentThread().isInterrupted) throw CancellationException() }
                    active()
                    val http = connectionFactory(URL(MOBILE_BASE_URL + "v1/evidence/${image.hash}/content"))
                    connection.set(http); active()
                    http.instanceFollowRedirects = false; http.connectTimeout = 15_000; http.readTimeout = 30_000; http.useCaches = false
                    http.requestMethod = "GET"
                    http.setRequestProperty("Authorization", "Bearer $token")
                    http.setRequestProperty("Accept", image.mime); http.setRequestProperty("Accept-Encoding", "identity")
                    if (http.responseCode != 200) throw MobileApiFailure(http.responseCode)
                    if (http.contentLengthLong >= 0 && http.contentLengthLong != image.bytes) throw IOException("中央照片大小已變更。")
                    if (http.contentType?.substringBefore(';')?.trim() != image.mime || http.contentEncoding?.let { it != "identity" } == true) throw IOException("中央照片格式不符。")
                    http.inputStream.use { input -> target.outputStream().use { output ->
                        var bytes = 0L; val buffer = ByteArray(64 * 1024)
                        while (true) { active(); val n = input.read(buffer); if (n < 0) break; bytes += n
                            if (bytes > image.bytes) throw IOException("中央照片超過預期大小。")
                            output.write(buffer, 0, n)
                        }
                        if (bytes != image.bytes) throw IOException("中央照片下載未完成。")
                        output.fd.sync()
                    } }
                    active(); verifyPhoto(target, image)
                    completed = true
                    if (continuation.isActive) continuation.resume(Unit)
                } catch (error: Exception) { target.delete(); if (continuation.isActive) continuation.resumeWithException(error) }
                finally { connection.getAndSet(null)?.disconnect(); if (!completed) target.delete() }
            })
            if (!continuation.isActive) future.get()?.cancel(true)
        }
    }
    companion object { private val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "receipt-photo").apply { isDaemon = true } } }
}
