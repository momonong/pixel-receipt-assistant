package com.momonong.pixelreceipt.mobile

import com.google.gson.JsonParser
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URL
import java.security.cert.Certificate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import javax.net.ssl.HttpsURLConnection

class MobileHttpTest {
    @Test fun productionTransportUsesFixedHttpsOriginProductUserAgentAndRejectsRedirect() = runBlocking {
        val connection = StubConnection(302)
        var count = 0
        val transport = HttpsMobileTransport { url ->
            count++
            assertEquals("https", url.protocol); assertEquals("momonong.me", url.host)
            assertEquals("/receipts/v1/recognition", url.path)
            connection
        }
        try { transport.call(MobileRequest("v1/recognition", token = "test-session")); fail() }
        catch (error: MobileApiFailure) { assertEquals(302, error.statusCode) }
        assertEquals(1, count)
        assertFalse(connection.instanceFollowRedirects)
        assertEquals("PixelReceipt-Android/0.5.1", connection.getRequestProperty("User-Agent"))
        assertTrue(connection.disconnected)
    }
    @Test fun cancellationDisconnectsAnInflightRequest() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val stop = CountDownLatch(1)
        val connection = object : StubConnection(200) {
            override fun getResponseCode(): Int { started.complete(Unit); stop.await(); return 200 }
            override fun disconnect() { super.disconnect(); stop.countDown() }
        }
        val operation = launch { HttpsMobileTransport { connection }.call(MobileRequest("v1/recognition")) }
        withTimeout(3000) { started.await() }
        operation.cancelAndJoin()
        assertTrue(connection.disconnected)
    }
    @Test fun rawModelResultIsNotRetainedInMobileStatusSnapshot() = runBlocking {
        val id = UUID.randomUUID().toString()
        val raw = "x".repeat(4 * 1024 * 1024)
        val body = """{"id":"$id","status":"succeeded","draft_id":null,"job_key":"original-key","result":{"raw":"$raw"}}"""
        val api = HttpsMobileApi(MobileTransport { body })
        val result = api.status("test-session", id)
        assertTrue(result.rawJson.length < 1024)
        assertFalse(result.rawJson.contains("result"))
        assertEquals("original-key", JsonParser.parseString(result.rawJson).asJsonObject["job_key"].asString)
    }
    @Test fun loginContainsOnlyDeviceAndPasswordAndStatusDoesNotSendPassword() = runBlocking {
        val id = UUID.randomUUID().toString()
        val requests = mutableListOf<MobileRequest>()
        val api = HttpsMobileApi(MobileTransport { request ->
            requests += request
            if (request.path == "v2/mobile/login") """{"role":"reviewer","token_type":"Bearer","device_id":"$id","access_token":"prm_test-session"}"""
            else """{"engines":{"broker":{"available":false,"reason":"recognition_not_configured"},"local":{"available":true,"reason":null}}}"""
        })
        val token = api.login(id, "fake-password")
        val login = JsonParser.parseString(requests.single().json).asJsonObject
        assertEquals(setOf("device_id", "device_name", "password"), login.keySet())
        val available = api.recognition(token)
        assertFalse(available.getValue("broker").available); assertTrue(available.getValue("local").available)
        assertNull(requests[1].json)
        assertEquals("prm_test-session", requests[1].token)
        assertTrue(requests.none { it.toString().contains("fake-password") || it.toString().contains(token) })
    }
}

internal open class StubConnection(private val code: Int, private val body: String = "{}") : HttpsURLConnection(URL(MOBILE_BASE_URL)) {
    var disconnected = false
    override fun disconnect() { disconnected = true }
    override fun usingProxy() = false
    override fun connect() = Unit
    override fun getResponseCode() = code
    override fun getInputStream() = ByteArrayInputStream(body.toByteArray())
    override fun getOutputStream() = ByteArrayOutputStream()
    override fun getCipherSuite() = "test-only"
    override fun getLocalCertificates(): Array<Certificate>? = null
    override fun getServerCertificates(): Array<Certificate> = emptyArray()
}
