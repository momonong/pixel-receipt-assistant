package com.momonong.pixelreceipt.mobile

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class ReceiptActionsHttpTest {
    private fun fixture(name: String) = javaClass.getResource("/receipt-actions/$name.json")!!.readText()
    @Test fun actualBackendDeletedDtosRetainKnownIdAndCanBeReconciled() = runBlocking {
        for (name in listOf("intake-deleted-no-draft", "intake-deleted-linked-draft")) {
            val raw = fixture(name); val id = jsonObject(raw).text("id")
            val api = HttpsMobileApi(MobileTransport { request -> assertEquals("v1/intakes/$id", request.path); raw })
            val result = api.status("prm_fixture", id)
            assertEquals("deleted", result.status)
            assertEquals(jsonObject(raw).text("draft_id").takeIf { it.isNotBlank() }, result.draftId)
        }
        val raw = fixture("draft-deleted"); val id = jsonObject(raw).text("id")
        val api = HttpsMobileApi(MobileTransport { raw })
        assertEquals("deleted", draftObject(api.draft("prm_fixture", id)).text("status"))
    }
    @Test fun deleteVoidRequestsUseExactBackendPathsFrozenBodiesAndHeaders() = runBlocking {
        val intake = fixture("intake-deleted-no-draft"); val intakeId = jsonObject(intake).text("id")
        val draft = fixture("draft-deleted"); val draftId = jsonObject(draft).text("id")
        val entry = fixture("ledger-voided"); val entryId = jsonObject(entry).text("id")
        for ((kind, id, body) in listOf(Triple("intake_delete", intakeId, "{\"expected_draft_revision\":null,\"reason\":\"未註明\"}"), Triple("draft_delete", draftId, "{\"expected_revision\":1,\"reason\":\"未註明\"}"), Triple("ledger_void", entryId, "{\"expected_revision\":1,\"reason\":\"未註明\"}"))) {
            val attempt = ReceiptMutation(kind, id, null, UUID.randomUUID().toString(), body)
            val expected = when (kind) { "intake_delete" -> "v1/intakes/$id/delete"; "draft_delete" -> "v1/drafts/$id/delete"; else -> "v1/ledger/$id/void" }
            val api = HttpsMobileApi(MobileTransport { request ->
                assertEquals(expected, request.path); assertEquals("POST", request.method)
                assertEquals(attempt.key, request.idempotencyKey); assertEquals(body, request.json)
                assertTrue(allowedMobileRequest(request.path, request.method))
                when (kind) { "intake_delete" -> intake; "draft_delete" -> draft; else -> entry }
            })
            when (kind) { "intake_delete" -> api.deleteIntake("prm_fixture", id, attempt); "draft_delete" -> api.deleteDraft("prm_fixture", id, attempt); else -> api.voidLedger("prm_fixture", id, attempt) }
        }
    }
    @Test fun actualBackendOverviewExcludesVoidedAmountWhileTheLedgerRetainsHistory() = runBlocking {
        val api = HttpsMobileApi(MobileTransport { request ->
            when (request.path) { "v1/overview" -> fixture("overview-after"); "v1/ledger?limit=20&offset=0" -> fixture("ledger-list-after"); else -> error("unexpected request") }
        })
        assertEquals(140, overviewObject(fixture("overview-before"))["personal_yuan"].asInt)
        assertEquals(45, overviewObject(api.overview("prm_fixture"))["personal_yuan"].asInt)
        val entries = jsonObject(api.ledger("prm_fixture")).array("entries")
        assertEquals(2, entries.size())
        assertEquals(1, entries.count { it.asJsonObject.text("status") == "voided" })
    }
    @Test fun allowlistRejectsOtherMutationsQueriesAndMissingExpectedRevision() {
        val id = UUID.randomUUID().toString()
        for ((path, method) in listOf("v1/intakes/$id/delete?reason=x" to "POST", "v1/ledger/$id/void" to "DELETE", "v1/overview?secret=x" to "GET", "https://other.example/v1/overview" to "GET")) assertFalse(allowedMobileRequest(path, method))
        try { validateMutation(ReceiptMutation("intake_delete", id, null, UUID.randomUUID().toString(), "{\"reason\":\"未註明\"}")); fail() } catch (_: IllegalStateException) { }
    }
}
