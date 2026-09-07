package com.momonong.pixelreceipt.domain.rules

import com.momonong.pixelreceipt.domain.model.AdjustmentDirection
import com.momonong.pixelreceipt.domain.model.Allocation
import com.momonong.pixelreceipt.domain.model.AllocationMethod
import com.momonong.pixelreceipt.domain.model.Fact
import com.momonong.pixelreceipt.domain.model.FactProvenance
import com.momonong.pixelreceipt.domain.model.LinePortion
import com.momonong.pixelreceipt.domain.model.Money
import com.momonong.pixelreceipt.domain.model.PromotionApplication
import com.momonong.pixelreceipt.domain.model.PromotionApplicationStatus
import com.momonong.pixelreceipt.domain.model.PromotionMatchStatus
import com.momonong.pixelreceipt.domain.model.PromotionParticipant
import com.momonong.pixelreceipt.domain.model.ReceiptAdjustment
import com.momonong.pixelreceipt.domain.model.ReceiptAdjustmentKind
import com.momonong.pixelreceipt.domain.model.ReceiptAdjustmentScope
import com.momonong.pixelreceipt.domain.model.ReceiptAssemblyStatus
import com.momonong.pixelreceipt.domain.model.ReceiptDraft
import com.momonong.pixelreceipt.domain.model.ReceiptLineDraft
import com.momonong.pixelreceipt.domain.model.UnknownFactReason
import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiptValidatorTest {
    private val validator = ReceiptValidator()
    private val strictReconciler = ReceiptReconciler(roundingToleranceMinorUnits = 0)

    @Test
    fun `reconciliation is balanced when printed lines minus adjustments equal receipt total`() {
        val draft = receipt(
            receiptTotal = known(Money(189)),
            lines = listOf(line(printedTotal = known(Money(378)))),
            adjustments = listOf(adjustment(amount = known(Money(189)))),
        )

        assertTrue(validator.validate(draft).isEmpty())
        assertEquals(
            ReceiptReconciliationResult.Balanced(
                computedMinorUnits = BigInteger.valueOf(189),
                currencyCode = "TWD",
            ),
            strictReconciler.reconcile(draft),
        )
    }

    @Test
    fun `reconciliation reports an unbalanced known total`() {
        val draft = receipt(
            receiptTotal = known(Money(200)),
            lines = listOf(line(printedTotal = known(Money(378)))),
            adjustments = listOf(adjustment(amount = known(Money(189)))),
        )

        assertEquals(
            ReceiptReconciliationResult.Unbalanced(
                computedMinorUnits = BigInteger.valueOf(189),
                receiptMinorUnits = BigInteger.valueOf(200),
                differenceMinorUnits = BigInteger.valueOf(-11),
                currencyCode = "TWD",
            ),
            strictReconciler.reconcile(draft),
        )
    }

    @Test
    fun `unknown adjustment makes reconciliation indeterminate instead of acting as zero`() {
        val draft = receipt(
            receiptTotal = known(Money(100)),
            lines = listOf(line(printedTotal = known(Money(100)))),
            adjustments = listOf(
                adjustment(amount = Fact.Unknown(UnknownFactReason.Unreadable)),
            ),
        )

        assertEquals(
            ReceiptReconciliationResult.Indeterminate(
                gaps = listOf(ReconciliationGap.AdjustmentAmount("adjustment-1")),
            ),
            strictReconciler.reconcile(draft),
        )
    }

    @Test
    fun `validator flags a receipt without line items`() {
        val draft = receipt(receiptTotal = known(Money(0)), lines = emptyList())

        assertEquals(
            listOf(ReceiptValidationIssue.NoLineItems),
            validator.validate(draft),
        )
    }

    @Test
    fun `validator flags mixed currencies across receipt facts`() {
        val draft = receipt(
            receiptTotal = known(Money(100)),
            lines = listOf(
                line(printedTotal = known(Money(100, currencyCode = "USD"))),
            ),
        )

        assertEquals(
            listOf(ReceiptValidationIssue.MixedCurrencies(setOf("TWD", "USD"))),
            validator.validate(draft),
        )
    }

    @Test
    fun `validator rejects a scoped portion greater than purchased quantity`() {
        val draft = receipt(
            receiptTotal = known(Money(100)),
            lines = listOf(
                line(quantity = known(1), printedTotal = known(Money(100))),
            ),
            adjustments = listOf(
                adjustment(
                    amount = known(Money(10)),
                    scope = known(
                        ReceiptAdjustmentScope.Line(
                            LinePortion(receiptLineId = "line-1", quantity = 2),
                        ),
                    ),
                ),
            ),
        )

        assertEquals(
            listOf(
                ReceiptValidationIssue.PortionExceedsPurchasedQuantity(
                    ownerId = "adjustment-1",
                    lineItemId = "line-1",
                    purchasedQuantity = 1,
                    referencedQuantity = 2,
                ),
            ),
            validator.validate(draft),
        )
    }

    @Test
    fun `validator reports allocation total mismatch`() {
        val draft = receipt(
            receiptTotal = known(Money(90)),
            lines = listOf(line(printedTotal = known(Money(100)))),
            adjustments = listOf(adjustment(amount = known(Money(10)))),
            allocations = listOf(allocation(amount = Money(9))),
        )

        assertEquals(
            listOf(
                ReceiptValidationIssue.AllocationTotalMismatch(
                    adjustmentId = "adjustment-1",
                    expectedMinorUnits = BigInteger.TEN,
                    allocatedMinorUnits = BigInteger.valueOf(9),
                ),
            ),
            validator.validate(draft),
        )
    }

    @Test
    fun `allocation totals use BigInteger and cannot wrap Long`() {
        val draft = receipt(
            receiptTotal = known(Money(Long.MAX_VALUE)),
            lines = listOf(
                line(quantity = known(2), printedTotal = known(Money(Long.MAX_VALUE))),
            ),
            adjustments = listOf(adjustment(amount = known(Money(Long.MAX_VALUE)))),
            allocations = listOf(
                allocation(id = "allocation-1", amount = Money(Long.MAX_VALUE)),
                allocation(id = "allocation-2", amount = Money(1)),
            ),
        )

        assertEquals(
            listOf(
                ReceiptValidationIssue.AllocationTotalMismatch(
                    adjustmentId = "adjustment-1",
                    expectedMinorUnits = BigInteger.valueOf(Long.MAX_VALUE),
                    allocatedMinorUnits = BigInteger.valueOf(Long.MAX_VALUE) + BigInteger.ONE,
                ),
            ),
            validator.validate(draft),
        )
    }

    @Test
    fun `confirmed promotion must reference an offer stored with the receipt`() {
        val application = PromotionApplication(
            id = "application-1",
            offerId = "missing-offer",
            participants = listOf(
                PromotionParticipant(
                    portion = LinePortion("line-1", quantity = 1),
                    productMentionId = "mention-1",
                    status = PromotionMatchStatus.Confirmed,
                    matchProvenance = userConfirmed(),
                ),
            ),
            actualAdjustmentIds = listOf("adjustment-1"),
            expectedDiscount = known(Money(10)),
            status = PromotionApplicationStatus.Confirmed,
            decisionProvenance = userConfirmed(),
        )
        val draft = ReceiptDraft(
            id = "receipt-1",
            merchant = known("寶雅"),
            total = known(Money(100)),
            items = listOf(line(printedTotal = known(Money(100)))),
            adjustments = listOf(adjustment(amount = known(Money(10)))),
            promotionApplications = listOf(application),
            assemblyStatus = ReceiptAssemblyStatus.CompleteByUser,
        )

        assertEquals(
            listOf(ReceiptValidationIssue.MissingReference("application-1", "missing-offer")),
            validator.validate(draft),
        )
    }

    @Test
    fun `allocation cannot escape the adjustment line scope`() {
        val draft = receipt(
            receiptTotal = known(Money(190)),
            lines = listOf(
                line(id = "line-1", printedTotal = known(Money(100))),
                line(id = "line-2", printedTotal = known(Money(100))),
            ),
            adjustments = listOf(
                adjustment(
                    amount = known(Money(10)),
                    scope = known(
                        ReceiptAdjustmentScope.Line(LinePortion("line-1", quantity = 1)),
                    ),
                ),
            ),
            allocations = listOf(
                Allocation(
                    id = "allocation-1",
                    adjustmentId = "adjustment-1",
                    portion = LinePortion("line-2", quantity = 1),
                    amount = Money(10),
                    method = AllocationMethod.PromotionRule,
                    provenance = FactProvenance.Derived(
                        ruleName = "promotion-allocation",
                        ruleVersion = "1",
                        inputFactIds = setOf("adjustment-1", "line-2"),
                    ),
                ),
            ),
        )

        assertEquals(
            listOf(
                ReceiptValidationIssue.AllocationOutsideAdjustmentScope(
                    adjustmentId = "adjustment-1",
                    receiptLineId = "line-2",
                ),
            ),
            validator.validate(draft),
        )
    }

    private fun receipt(
        receiptTotal: Fact<Money>,
        lines: Collection<ReceiptLineDraft>,
        adjustments: Collection<ReceiptAdjustment> = emptyList(),
        allocations: Collection<Allocation> = emptyList(),
    ) = ReceiptDraft(
        id = "receipt-1",
        merchant = known("全聯"),
        total = receiptTotal,
        items = lines,
        adjustments = adjustments,
        allocations = allocations,
        assemblyStatus = ReceiptAssemblyStatus.CompleteByRule,
    )

    private fun line(
        id: String = "line-1",
        quantity: Fact<Int> = known(1),
        printedTotal: Fact<Money>,
    ) = ReceiptLineDraft(
        id = id,
        rawName = known("高露潔齒縫潔淨"),
        quantity = quantity,
        printedTotal = printedTotal,
    )

    private fun adjustment(
        amount: Fact<Money>,
        scope: Fact<ReceiptAdjustmentScope> = known(ReceiptAdjustmentScope.Order),
    ) = ReceiptAdjustment(
        id = "adjustment-1",
        kind = ReceiptAdjustmentKind.Promotion,
        direction = AdjustmentDirection.Subtract,
        amount = amount,
        scope = scope,
    )

    private fun allocation(
        id: String = "allocation-1",
        amount: Money,
    ) = Allocation(
        id = id,
        adjustmentId = "adjustment-1",
        portion = LinePortion(receiptLineId = "line-1", quantity = 1),
        amount = amount,
        method = AllocationMethod.ProportionalGross,
        provenance = FactProvenance.Derived(
            ruleName = "largest-remainder",
            ruleVersion = "1",
            inputFactIds = setOf("adjustment-1", "line-1"),
        ),
    )

    private fun <T : Any> known(value: T): Fact.Known<T> = Fact.Known(
        value = value,
        provenance = userConfirmed(),
    )

    private fun userConfirmed() = FactProvenance.UserConfirmed(confirmedAtEpochMillis = 1)
}
