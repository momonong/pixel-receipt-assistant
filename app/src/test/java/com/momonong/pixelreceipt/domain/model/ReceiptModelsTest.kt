package com.momonong.pixelreceipt.domain.model

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiptModelsTest {
    @Test
    fun `money rejects negative values`() {
        assertThrows(IllegalArgumentException::class.java) {
            Money(minorUnits = -1)
        }
    }

    @Test
    fun `money rejects a malformed currency code`() {
        assertThrows(IllegalArgumentException::class.java) {
            Money(minorUnits = 1, currencyCode = "twd")
        }
    }

    @Test
    fun `receipt rejects a blank id`() {
        assertThrows(IllegalArgumentException::class.java) {
            ReceiptDraft(
                id = " ",
                merchant = known("全聯"),
                total = known(Money(0)),
                items = emptyList(),
            )
        }
    }

    @Test
    fun `line item rejects a non-positive quantity`() {
        assertThrows(IllegalArgumentException::class.java) {
            ReceiptLineDraft(
                id = "line-1",
                rawName = known("測試商品"),
                quantity = known(0),
                printedTotal = known(Money(100)),
            )
        }
    }

    @Test
    fun `unknown printed amount is not treated as known zero`() {
        val line = ReceiptLineDraft(
            id = "line-1",
            rawName = known("測試商品"),
            quantity = known(1),
            printedTotal = Fact.Unknown(UnknownFactReason.Unreadable),
        )
        val knownZero: Fact<Money> = known(Money(0))

        assertTrue(line.printedTotal is Fact.Unknown)
        assertNotEquals(knownZero, line.printedTotal)
    }

    @Test
    fun `receipt rejects a negative revision`() {
        assertThrows(IllegalArgumentException::class.java) {
            ReceiptDraft(
                id = "receipt-1",
                merchant = known("全聯"),
                total = known(Money(0)),
                items = emptyList(),
                revision = -1,
            )
        }
    }

    private fun <T : Any> known(value: T): Fact.Known<T> = Fact.Known(
        value = value,
        provenance = FactProvenance.UserConfirmed(confirmedAtEpochMillis = 1),
    )
}
