package com.momonong.pixelreceipt.data

import com.momonong.pixelreceipt.data.ingestion.AssistantReceiptText
import org.junit.Assert.*
import org.junit.Test

class AssistantReceiptTextTest {
    private val valid = """{"schema":"pixelreceipt-1","currency":"TWD","merchant":"測試商店","date":"2026-09-14","totalMinor":450,"items":[{"name":"茶","quantity":null,"lineTotalMinor":300},{"name":"茶","quantity":1,"lineTotalMinor":150}],"adjustments":[]}"""
    private fun reject(value: String) { assertThrows(value.take(90), IllegalArgumentException::class.java) { AssistantReceiptText.parse(value) } }

    @Test fun acceptsWholeCodeBlockAndPreservesUnknownAndRepeatedRows() {
        val input = AssistantReceiptText.parse("```json\n$valid\n```")
        assertEquals(450L, input.totalMinor)
        assertEquals(2, input.items.size)
        assertNull(input.items[0].quantity)
        assertEquals("茶", input.items[1].name)
    }
    @Test fun zeroIsDifferentFromUnknownAndLongAmountsRemainExact() {
        val input = AssistantReceiptText.parse(valid.replace("\"totalMinor\":450", "\"totalMinor\":9223372036854775807")
            .replace("\"lineTotalMinor\":300", "\"lineTotalMinor\":0").replace("\"lineTotalMinor\":150", "\"lineTotalMinor\":null"))
        assertEquals(Long.MAX_VALUE, input.totalMinor)
        assertEquals(0L, input.items[0].lineTotalMinor)
        assertNull(input.items[1].lineTotalMinor)
    }
    @Test fun rejectsDecimalExponentQuotedNegativeAndOverflowAmounts() {
        listOf("450.0", "45e1", "\"450\"", "-1", "9223372036854775808").forEach {
            reject(valid.replace("\"totalMinor\":450", "\"totalMinor\":$it"))
        }
    }
    @Test fun rejectsInvalidQuantityAndCalendarDateWithoutInventingValues() {
        listOf("0", "-1", "2147483648", "1.5").forEach { reject(valid.replace("\"quantity\":1", "\"quantity\":$it")) }
        reject(valid.replace("2026-09-14", "2026-02-30"))
        reject(valid.replace("2026-09-14", "2026-9-14"))
    }
    @Test fun rejectsWrongUnknownCurrencyAndPrivateLedgerFields() {
        reject(valid.replace("TWD", "USD")); reject(valid.replace("\"TWD\"", "null"))
        listOf("\"stage\":\"Confirmed\"", "\"id\":\"existing\"", "\"personalExpenses\":[]", "\"revision\":99")
            .forEach { reject(valid.replace("\"schema\":", "$it,\"schema\":")) }
    }
    @Test fun duplicateKeysTruncationTrailingPayloadAndNonJsonNeverReachLedger() {
        reject(valid.replace("\"totalMinor\":450", "\"totalMinor\":450,\"totalMinor\":0"))
        reject(valid.dropLast(1)); reject("$valid $valid"); reject("https://gemini.google.com/share/example")
        reject("// hello\n$valid"); reject(valid.replace("\"merchant\"", "merchant"))
    }
    @Test fun rejectsExcessiveDepthSizeAndLineCountBeforeMapping() {
        reject("[".repeat(10_000))
        reject("{\"items\":" + "[".repeat(20) + "0" + "]".repeat(20) + "}")
        reject(valid + " ".repeat(64_000))
        reject("""{"schema":"pixelreceipt-1","currency":"TWD","items":[${List(101) { """{"name":null}""" }.joinToString(",")}]}""")
    }
    @Test fun separateDiscountIsAcceptedButNotGivenAnInferredScope() {
        val input = AssistantReceiptText.parse(valid.replace("\"adjustments\":[]", "\"adjustments\":[{\"label\":\"折扣\",\"direction\":\"subtract\",\"amountMinor\":10}]"))
        assertEquals("subtract", input.adjustments.single().direction)
        assertEquals(10L, input.adjustments.single().amountMinor)
        reject(valid.replace("\"adjustments\":[]", "\"adjustments\":[{\"direction\":\"other\",\"amountMinor\":10}]"))
    }
}
