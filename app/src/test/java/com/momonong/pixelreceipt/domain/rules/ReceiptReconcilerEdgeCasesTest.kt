package com.momonong.pixelreceipt.domain.rules

import com.momonong.pixelreceipt.domain.model.AdjustmentDirection
import com.momonong.pixelreceipt.domain.model.Fact
import com.momonong.pixelreceipt.domain.model.FactProvenance
import com.momonong.pixelreceipt.domain.model.LinePortion
import com.momonong.pixelreceipt.domain.model.Money
import com.momonong.pixelreceipt.domain.model.ReceiptAdjustment
import com.momonong.pixelreceipt.domain.model.ReceiptAdjustmentKind
import com.momonong.pixelreceipt.domain.model.ReceiptAdjustmentScope
import com.momonong.pixelreceipt.domain.model.ReceiptAssemblyStatus
import com.momonong.pixelreceipt.domain.model.ReceiptDraft
import com.momonong.pixelreceipt.domain.model.ReceiptLineDraft
import com.momonong.pixelreceipt.domain.model.UnknownFactReason
import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Test

class ReceiptReconcilerEdgeCasesTest {
    private val reconciler = ReceiptReconciler()

    @Test
    fun `negative computed total is never accepted within rounding tolerance`() {
        val draft = receipt(
            total = known(Money(0)),
            line = line(
                quantity = known(1),
                printedTotal = known(Money(0)),
            ),
            adjustment = adjustment(
                amount = known(Money(1)),
                scope = known(ReceiptAdjustmentScope.Order),
            ),
        )

        assertEquals(
            ReceiptReconciliationResult.Unbalanced(
                computedMinorUnits = BigInteger.valueOf(-1),
                receiptMinorUnits = BigInteger.ZERO,
                differenceMinorUnits = BigInteger.valueOf(-1),
                currencyCode = "TWD",
            ),
            reconciler.reconcile(draft),
        )
    }

    @Test
    fun `unknown purchased quantity blocks a balanced scoped adjustment`() {
        val draft = receipt(
            total = known(Money(90)),
            line = line(
                quantity = Fact.Unknown(UnknownFactReason.Unreadable),
                printedTotal = known(Money(100)),
            ),
            adjustment = adjustment(
                amount = known(Money(10)),
                scope = known(
                    ReceiptAdjustmentScope.Line(
                        LinePortion(receiptLineId = "line-1", quantity = 1),
                    ),
                ),
            ),
        )

        assertEquals(
            ReceiptReconciliationResult.Indeterminate(
                gaps = listOf(ReconciliationGap.LineQuantity("line-1")),
            ),
            reconciler.reconcile(draft),
        )
    }

    @Test
    fun `unknown export-required merchant and line name block reconciliation`() {
        val draft = ReceiptDraft(
            id = "receipt-1",
            merchant = Fact.Unknown(UnknownFactReason.Unreadable),
            total = known(Money(100)),
            items = listOf(
                line(
                    rawName = Fact.Unknown(UnknownFactReason.Unreadable),
                    quantity = known(1),
                    printedTotal = known(Money(100)),
                ),
            ),
            assemblyStatus = ReceiptAssemblyStatus.CompleteByRule,
        )

        assertEquals(
            ReceiptReconciliationResult.Indeterminate(
                gaps = listOf(
                    ReconciliationGap.Merchant,
                    ReconciliationGap.LineName("line-1"),
                ),
            ),
            reconciler.reconcile(draft),
        )
    }

    private fun receipt(
        total: Fact<Money>,
        line: ReceiptLineDraft,
        adjustment: ReceiptAdjustment,
    ) = ReceiptDraft(
        id = "receipt-1",
        merchant = known("全聯"),
        total = total,
        items = listOf(line),
        adjustments = listOf(adjustment),
        assemblyStatus = ReceiptAssemblyStatus.CompleteByRule,
    )

    private fun line(
        rawName: Fact<String> = known("測試商品"),
        quantity: Fact<Int>,
        printedTotal: Fact<Money>,
    ) = ReceiptLineDraft(
        id = "line-1",
        rawName = rawName,
        quantity = quantity,
        printedTotal = printedTotal,
    )

    private fun adjustment(
        amount: Fact<Money>,
        scope: Fact<ReceiptAdjustmentScope>,
    ) = ReceiptAdjustment(
        id = "adjustment-1",
        kind = ReceiptAdjustmentKind.Promotion,
        direction = AdjustmentDirection.Subtract,
        amount = amount,
        scope = scope,
    )

    private fun <T : Any> known(value: T): Fact.Known<T> = Fact.Known(
        value = value,
        provenance = FactProvenance.UserConfirmed(confirmedAtEpochMillis = 1),
    )
}
