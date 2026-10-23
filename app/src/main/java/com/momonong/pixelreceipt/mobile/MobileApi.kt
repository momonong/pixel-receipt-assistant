package com.momonong.pixelreceipt.mobile

import com.google.gson.JsonParser
import com.google.gson.JsonObject
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

const val MOBILE_BASE_URL = "https://momonong.me/receipts/"
class MobileApiFailure(val statusCode: Int, val code: String? = null) : IOException("中央服務 HTTP $statusCode${code?.let { ": $it" }.orEmpty()}")

interface MobileApi {
    suspend fun login(deviceId: String, password: String): String
    suspend fun logout(token: String)
    suspend fun recognition(token: String): Map<String, EngineAvailability>
    suspend fun upload(token: String, image: MobileImage, file: File): String
    suspend fun submit(token: String, frozen: FrozenIntake): ServerIntake
    suspend fun status(token: String, id: String): ServerIntake
    suspend fun draft(token: String, id: String): String = throw UnsupportedOperationException()
    suspend fun review(token: String, id: String, body: String): String = throw UnsupportedOperationException()
    suspend fun confirm(token: String, id: String, attempt: FrozenConfirmation): String = throw UnsupportedOperationException()
    suspend fun ledger(token: String, offset: Int = 0): String = throw UnsupportedOperationException()
    suspend fun ledgerDetail(token: String, id: String): String = throw UnsupportedOperationException()
}

/** Deliberately not a data class: neither body nor Bearer may appear in debug strings. */
internal class MobileRequest(
    val path: String, val method: String = "GET", val token: String? = null,
    val json: String? = null, val file: File? = null, val mime: String = "application/json",
    val idempotencyKey: String? = null,
) {
    override fun toString(): String = "MobileRequest($method $path; credentials/body redacted)"
}
internal fun interface MobileTransport { suspend fun call(request: MobileRequest): String }

internal class HttpsMobileTransport(private val connectionFactory: (URL) -> HttpsURLConnection = { it.openConnection() as HttpsURLConnection }) : MobileTransport {
    private val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "receipt-https").apply { isDaemon = true } }
    override suspend fun call(request: MobileRequest): String = withTimeout(90_000) {
        require(allowedMobileRequest(request.path, request.method))
        request.token?.let { require(it.length <= 8192 && it.none(Char::isISOControl)) }
        request.idempotencyKey?.let { require(it.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))) }
        suspendCancellableCoroutine { continuation ->
            val connection = AtomicReference<HttpsURLConnection?>()
            val future = AtomicReference<Future<*>?>()
            continuation.invokeOnCancellation { connection.getAndSet(null)?.disconnect(); future.get()?.cancel(true) }
            future.set(executor.submit {
                try {
                    if (!continuation.isActive) return@submit
                    val http = connectionFactory(URL(MOBILE_BASE_URL + request.path))
                    connection.set(http)
                    if (!continuation.isActive) { http.disconnect(); return@submit }
                    http.instanceFollowRedirects = false
                    http.connectTimeout = 15_000
                    http.readTimeout = 30_000
                    http.useCaches = false
                    http.requestMethod = request.method
                    http.setRequestProperty("Accept", "application/json")
                    http.setRequestProperty("User-Agent", "PixelReceipt-Android/0.5.0")
                    request.token?.let { http.setRequestProperty("Authorization", "Bearer $it") }
                    request.idempotencyKey?.let { http.setRequestProperty("Idempotency-Key", it) }
                    if (request.method in setOf("POST", "PUT")) {
                        http.doOutput = true
                        http.setRequestProperty("Content-Type", request.mime)
                        val bytes = request.json?.toByteArray(Charsets.UTF_8)
                        val size = request.file?.length() ?: bytes?.size?.toLong() ?: 0L
                        require(size <= 20L * 1024 * 1024)
                        http.setFixedLengthStreamingMode(size)
                        http.outputStream.use { output ->
                            if (request.file != null) request.file.inputStream().use { it.copyTo(output, 64 * 1024) }
                            else if (bytes != null) output.write(bytes)
                        }
                    }
                    val code = http.responseCode
                    if (code !in 200..299) {
                        // Keep only a constrained machine code, never the untrusted error body.
                        val errorCode = runCatching {
                            val text = http.errorStream?.use { readBounded(it, 64 * 1024).toString(Charsets.UTF_8) }
                            text?.let { JsonParser.parseString(it).asJsonObject["detail"] }
                                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
                                ?.takeIf { it.matches(Regex("[a-z][a-z0-9_]{0,79}")) }
                        }.getOrNull()
                        throw MobileApiFailure(code, errorCode)
                    }
                    val body = http.inputStream.use { input ->
                        val output = java.io.ByteArrayOutputStream()
                        val responseLimit = if (request.path.startsWith("v1/intakes") || request.path.startsWith("v1/drafts") || request.path.startsWith("v1/ledger") || request.path.startsWith("v2/drafts")) 16 * 1024 * 1024 else 64 * 1024
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            if (output.size() + n > responseLimit) throw IOException("中央回應超過限制。")
                            output.write(buffer, 0, n)
                        }
                        output.toString(Charsets.UTF_8.name())
                    }
                    if (continuation.isActive) continuation.resume(body)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                } finally { connection.getAndSet(null)?.disconnect() }
            })
            if (!continuation.isActive) future.get()?.cancel(true)
        }
    }
}

class HttpsMobileApi internal constructor(private val transport: MobileTransport) : MobileApi {
    constructor() : this(HttpsMobileTransport())
    override suspend fun login(deviceId: String, password: String): String {
        require(validUuid(deviceId) && password.length in 1..1024)
        val body = JsonObject().apply {
            addProperty("device_id", deviceId)
            addProperty("device_name", "PixelReceipt Android")
            addProperty("password", password)
        }.toString()
        val response = objectJson(transport.call(MobileRequest("v2/mobile/login", "POST", json = body)))
        check(response["role"]?.asString == "reviewer" && response["token_type"]?.asString == "Bearer")
        check(response["device_id"]?.asString == deviceId)
        return response["access_token"].asString.also { require(it.startsWith("prm_") && it.length <= 8192 && it.none(Char::isISOControl)) }
    }
    override suspend fun logout(token: String) { transport.call(MobileRequest(if (token.startsWith("prm_")) "v2/mobile/logout" else "v1/mobile/logout", "POST", token)) }
    override suspend fun recognition(token: String): Map<String, EngineAvailability> {
        val engines = objectJson(transport.call(MobileRequest("v1/recognition", token = token))).getAsJsonObject("engines")
        return listOf("broker", "local").associateWith { name ->
            val value = engines.getAsJsonObject(name)
            EngineAvailability(value["available"].asBoolean, value["reason"]?.takeUnless { it.isJsonNull }?.asString)
        }
    }
    override suspend fun upload(token: String, image: MobileImage, file: File): String {
        val response = objectJson(transport.call(MobileRequest("v1/evidence", "POST", token, file = file, mime = image.mime)))
        return response["sha256"].asString.also { check(it == image.hash) }
    }
    override suspend fun submit(token: String, frozen: FrozenIntake): ServerIntake = parseIntake(
        transport.call(MobileRequest("v1/intakes", "POST", token, json = frozen.body, idempotencyKey = frozen.key)),
    )
    override suspend fun status(token: String, id: String): ServerIntake {
        require(validUuid(id))
        return parseIntake(transport.call(MobileRequest("v1/intakes/$id", token = token))).also { check(it.id == id) }
    }
    override suspend fun draft(token: String, id: String): String {
        require(validUuid(id)); return transport.call(MobileRequest("v1/drafts/$id", token = token)).also { check(draftObject(it).text("id") == id) }
    }
    override suspend fun review(token: String, id: String, body: String): String {
        require(validUuid(id)); return transport.call(MobileRequest("v2/drafts/$id/review", "PUT", token, json = body)).also { check(draftObject(it).text("id") == id) }
    }
    override suspend fun confirm(token: String, id: String, attempt: FrozenConfirmation): String {
        require(validUuid(id)); return transport.call(MobileRequest("v2/drafts/$id/confirm", "POST", token, json = attempt.body, idempotencyKey = attempt.key)).also { check(ledgerObject(it).text("draft_id") == id) }
    }
    override suspend fun ledger(token: String, offset: Int): String {
        require(offset in 0..100_000)
        return transport.call(MobileRequest("v1/ledger?limit=20&offset=$offset", token = token)).also { value ->
            val json = objectJson(value); check(json["entries"].isJsonArray); strictLong(json["total_count"], 0, Int.MAX_VALUE.toLong())
            json.getAsJsonArray("entries").forEach { ledgerObject(it.toString()) }
        }
    }
    override suspend fun ledgerDetail(token: String, id: String): String {
        require(validUuid(id)); return transport.call(MobileRequest("v1/ledger/$id", token = token)).also { check(ledgerObject(it).text("id") == id) }
    }
    private fun objectJson(body: String): JsonObject = JsonParser.parseString(body).asJsonObject
    private fun parseIntake(body: String): ServerIntake {
        val json = objectJson(body)
        val id = json["id"].asString.also { check(validUuid(it)) }
        val status = json["status"].asString.also { check(it in intakeStatuses) }
        val draft = json["draft_id"]?.takeUnless { it.isJsonNull }?.asString?.also { check(validUuid(it)) }
        val summary = JsonObject().apply {
            addProperty("id", id); addProperty("status", status)
            add("draft_id", json["draft_id"] ?: com.google.gson.JsonNull.INSTANCE)
            listOf("job_key", "error_code", "created_at", "updated_at").forEach { name ->
                json[name]?.takeUnless { it.isJsonNull }?.asString?.takeIf { it.length <= 256 }?.let { addProperty(name, it) }
            }
        }.toString()
        val draftStatus = json["draft_status"]?.takeUnless { it.isJsonNull }?.asString?.also { check(it in setOf("needs_review", "confirmed", "voided")) }
        val ledgerId = json["ledger_id"]?.takeUnless { it.isJsonNull }?.asString?.also { check(validUuid(it)) }
        val ledgerStatus = json["ledger_status"]?.takeUnless { it.isJsonNull }?.asString?.also { check(it in setOf("active", "voided")) }
        return ServerIntake(id, status, draft, summary, draftStatus, ledgerId, ledgerStatus)
    }
}

/** Fixed HTTPS origin plus a method/path pair allowlist; query syntax is deliberately narrow. */
internal fun allowedMobileRequest(path: String, method: String): Boolean {
    val uuid = "[0-9a-fA-F-]{36}"
    return when (method) {
        "GET" -> path == "v1/recognition" || path.matches(Regex("v1/(intakes|drafts|ledger)/$uuid")) ||
            path.matches(Regex("v1/ledger\\?limit=20&offset=(0|[1-9][0-9]{0,5})"))
        "POST" -> path in setOf("v1/mobile/logout", "v2/mobile/login", "v2/mobile/logout", "v1/evidence", "v1/intakes") ||
            path.matches(Regex("v2/drafts/$uuid/confirm"))
        "PUT" -> path.matches(Regex("v2/drafts/$uuid/review"))
        else -> false
    }
}
