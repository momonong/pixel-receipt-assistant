package com.momonong.pixelreceipt.domain.port

import com.momonong.pixelreceipt.domain.model.Money
import com.momonong.pixelreceipt.domain.model.Fact
import com.momonong.pixelreceipt.domain.model.FactProvenance
import com.momonong.pixelreceipt.domain.model.UnknownFactReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayContractsTest {
    @Test
    fun `local image id rejects a blank value`() {
        assertThrows(IllegalArgumentException::class.java) {
            LocalImageId(" ")
        }
    }

    @Test
    fun `analysis retry policy is derived from typed error kind`() {
        assertTrue(AnalysisError(AnalysisErrorKind.Network).retryable)
        assertFalse(AnalysisError(AnalysisErrorKind.InvalidOutput).retryable)
    }

    @Test
    fun `export batch takes an immutable snapshot of rows`() {
        val mutableRows = mutableListOf(exportRow())
        val batch = exportBatch(rows = mutableRows)

        mutableRows.clear()

        assertEquals(1, batch.rows.size)
    }

    @Test
    fun `export batch rejects a row in another currency`() {
        assertThrows(IllegalArgumentException::class.java) {
            exportBatch(
                rows = listOf(exportRow(currencyCode = "USD")),
            )
        }
    }

    @Test
    fun `export row preserves unknown savings instead of replacing them with zero`() {
        val row = exportRow(
            referenceOriginalTotal = Fact.Unknown(UnknownFactReason.MissingEvidence),
            discountSaved = Fact.Unknown(UnknownFactReason.MissingEvidence),
        )

        assertTrue(row.referenceOriginalTotal is Fact.Unknown)
        assertTrue(row.discountSaved is Fact.Unknown)
    }

    private fun exportBatch(rows: List<ReceiptExportRow>) = ReceiptExportBatch(
        idempotencyKey = "receipt-1:revision-4",
        receiptId = "receipt-1",
        receiptRevision = 4,
        merchant = "全聯",
        total = Money(100),
        rows = rows,
    )

    private fun exportRow(
        currencyCode: String = "TWD",
        referenceOriginalTotal: Fact<Money> = known(Money(100, currencyCode)),
        discountSaved: Fact<Money> = known(Money(0, currencyCode)),
    ) = ReceiptExportRow(
        lineItemId = "line-1",
        rawName = "測試商品",
        quantity = 1,
        referenceOriginalTotal = referenceOriginalTotal,
        discountSaved = discountSaved,
        netTotal = Money(100, currencyCode),
    )

    private fun <T : Any> known(value: T): Fact.Known<T> = Fact.Known(
        value = value,
        provenance = FactProvenance.UserConfirmed(confirmedAtEpochMillis = 1),
    )
}
