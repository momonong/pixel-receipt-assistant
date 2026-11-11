package com.momonong.pixelreceipt.mobile

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class NativeItemNamesTest {
    private val id = UUID.randomUUID().toString()
    private fun mutation(body: String) = ReceiptMutation("ledger_names", id, null, UUID.randomUUID().toString(), body)
    @Test fun namesOnlyEndpointAndFrozenIntentAreAllowedWhileFinancialFieldsAreRejected() {
        assertTrue(allowedMobileRequest("v1/ledger/$id/item-names", "POST"))
        assertFalse(allowedMobileRequest("v1/ledger/$id/item-names?revision=1", "POST"))
        validateMutation(mutation("""{"expected_revision":1,"item_names":[{"item_position":0,"name":"飯"},{"item_position":1,"name":null}]}"""))
        for (body in listOf(
            """{"expected_revision":1,"item_names":[{"item_position":0,"name":"飯","amount_yuan":1}]}""",
            """{"expected_revision":1,"item_names":[{"item_position":0,"name":"飯"}],"printed_total_yuan":1}""",
            """{"expected_revision":1,"item_names":[{"item_position":0,"name":"飯"},{"item_position":0,"name":null}]}"""
        )) {
            try { validateMutation(mutation(body)); fail("Must reject $body") } catch (_: IllegalStateException) { }
        }
    }
    @Test fun httpsApiPostsOnlyTheFrozenMetadataIntentAndKeepsTheSameKey() = kotlinx.coroutines.runBlocking {
        val entry = ReceiptActionsApi().entry()
        val target = ledgerObject(entry).text("id")
        val body = """{"expected_revision":1,"item_names":[{"item_position":0,"name":"飯"}]}"""
        val attempt = ReceiptMutation("ledger_names", target, null, UUID.randomUUID().toString(), body)
        val api = HttpsMobileApi(MobileTransport { request ->
            assertEquals("v1/ledger/$target/item-names", request.path)
            assertEquals("POST", request.method); assertEquals(body, request.json)
            assertEquals(attempt.key, request.idempotencyKey)
            entry
        })
        assertEquals(target, ledgerObject(api.updateItemNames("prm_fixture", target, attempt)).text("id"))
    }
    @Test fun metadataNamesTakePrecedenceAndOldPayloadFallsBackToSnapshot() {
        val entry = ledgerObject(ReceiptActionsApi().entry())
        assertEquals(listOf("牙膏"), ledgerItemNames(entry))
        entry.add("display_item_names", com.google.gson.JsonArray().apply { add(com.google.gson.JsonNull.INSTANCE) })
        assertEquals(listOf<String?>(null), ledgerItemNames(entry))
        entry.add("display_item_names", com.google.gson.JsonArray().apply { add("已補品名") })
        assertEquals(listOf("已補品名"), ledgerItemNames(entry))
        assertEquals("牙膏", entry.getAsJsonObject("snapshot").getAsJsonObject("candidate").array("items")[0].asJsonObject.text("raw_name"))
    }
    @Test fun pendingNamesAndVoidShareOneLedgerMutualExclusionBoundary() {
        val names = mutation("""{"expected_revision":1,"item_names":[{"item_position":0,"name":null}]}""")
        assertEquals(names, MobileState(UUID.randomUUID().toString(), mutations = listOf(names)).pendingLedgerMutation(id))
    }
}
