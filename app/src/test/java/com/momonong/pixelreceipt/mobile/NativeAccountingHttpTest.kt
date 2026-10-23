package com.momonong.pixelreceipt.mobile

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class NativeAccountingHttpTest {
    @Test fun loginUsesNativeReviewerEndpointAndValidatesTheCredentialDomain() = runBlocking {
        val id = UUID.randomUUID().toString()
        val api = HttpsMobileApi(MobileTransport { request ->
            assertEquals("v2/mobile/login", request.path)
            assertEquals("POST", request.method)
            """{"role":"reviewer","token_type":"Bearer","device_id":"$id","access_token":"prm_fixture_session","expires_at":"2026-11-22T00:00:00Z"}"""
        })
        assertEquals("prm_fixture_session", api.login(id, "fixture password"))
    }
    @Test fun putActuallyWritesUtf8BodyAndTheAllowlistRejectsOtherMutationsAndUrls() = runBlocking {
        val output = java.io.ByteArrayOutputStream()
        val connection = object : StubConnection(200, "{}") { override fun getOutputStream() = output }
        val id = UUID.randomUUID().toString()
        val transport = HttpsMobileTransport { connection }
        val body = "{\"名稱\":\"牙膏\"}"
        transport.call(MobileRequest("v2/drafts/$id/review", "PUT", "prm_fixture", json = body))
        assertEquals("PUT", connection.requestMethod)
        assertTrue(connection.doOutput)
        assertEquals(body, output.toString("UTF-8"))
        for ((path, method) in listOf("https://other.example/v1/ledger" to "GET", "../v1/ledger" to "GET", "v1/ledger/$id/void" to "POST", "v2/drafts/$id/review" to "GET", "v1/ledger?limit=20&offset=0&token=secret" to "GET")) assertFalse(allowedMobileRequest(path, method))
    }
    @Test fun anUntrustedErrorBodyNeverAppearsInAnExceptionOrUserMessage() = runBlocking {
        val secret = "fake-private-body-credential"
        val connection = object : StubConnection(422) {
            override fun getErrorStream() = java.io.ByteArrayInputStream("{\"detail\":\"$secret\"}".toByteArray())
        }
        try { HttpsMobileTransport { connection }.call(MobileRequest("v1/recognition", token = "prm_fixture")); fail() }
        catch (error: MobileApiFailure) { assertNull(error.code); assertFalse(error.toString().contains(secret)); assertFalse(mobileError(error).contains(secret)) }
    }
    @Test fun intakeProjectionPreservesRecognitionAndFormalConfirmedOrVoidedState() = runBlocking {
        val intake = UUID.randomUUID().toString(); val draft = UUID.randomUUID().toString(); val ledger = UUID.randomUUID().toString()
        for ((draftStatus, ledgerStatus) in listOf("confirmed" to "active", "voided" to "voided")) {
            val api = HttpsMobileApi(MobileTransport { """{"id":"$intake","status":"succeeded","draft_id":"$draft","draft_status":"$draftStatus","ledger_id":"$ledger","ledger_status":"$ledgerStatus"}""" })
            val result = api.status("prm_fixture", intake)
            assertEquals("succeeded", result.status); assertEquals(ledger, result.ledgerId)
            assertEquals(draftStatus, result.draftStatus); assertEquals(ledgerStatus, result.ledgerStatus)
        }
    }
}
