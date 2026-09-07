package com.momonong.pixelreceipt.domain.rules

import com.momonong.pixelreceipt.domain.model.AdjustmentDirection
import com.momonong.pixelreceipt.domain.model.Fact
import com.momonong.pixelreceipt.domain.model.FactProvenance
import com.momonong.pixelreceipt.domain.model.LinePortion
import com.momonong.pixelreceipt.domain.model.Money
import com.momonong.pixelreceipt.domain.model.PromotionApplication
import com.momonong.pixelreceipt.domain.model.PromotionApplicationStatus
import com.momonong.pixelreceipt.domain.model.PromotionEligibility
import com.momonong.pixelreceipt.domain.model.PromotionMatchStatus
import com.momonong.pixelreceipt.domain.model.PromotionOffer
import com.momonong.pixelreceipt.domain.model.PromotionParticipant
import com.momonong.pixelreceipt.domain.model.PromotionProductMention
import com.momonong.pixelreceipt.domain.model.PromotionTerms
import com.momonong.pixelreceipt.domain.model.ReceiptAdjustment
import com.momonong.pixelreceipt.domain.model.ReceiptAdjustmentKind
import com.momonong.pixelreceipt.domain.model.ReceiptAdjustmentScope
import com.momonong.pixelreceipt.domain.model.ReceiptAssemblyStatus
import com.momonong.pixelreceipt.domain.model.ReceiptDraft
import com.momonong.pixelreceipt.domain.model.ReceiptLineDraft
import com.momonong.pixelreceipt.domain.model.UnknownFactReason
import java.math.BigInteger
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PromotionFinancialBindingTest {
    private val validator = ReceiptValidator()

    @Test
    fun `confirmed promotion requires a known expected discount`() {
        assertThrows(IllegalArgumentException::class.java) {
            application(
                expectedDiscount = Fact.Unknown(UnknownFactReason.MissingEvidence),
            )
        }
    }

    @Test
    fun `confirmed promotion requires an actual adjustment`() {
        assertThrows(IllegalArgumentException::class.java) {
            application(actualAdjustmentIds = emptySet())
        }
    }

    @Test
    fun `multiple promotion adjustments may bind participant subsets`() {
        val application = application(
            participants = listOf(
                participant("line-1", quantity = 2),
                participant("line-2", quantity = 1),
            ),
            actualAdjustmentIds = setOf("adjustment-1", "adjustment-2"),
        )
        val adjustments = listOf(
            adjustment(
                id = "adjustment-1",
                amount = known(Money(6)),
                scope = known(
                    ReceiptAdjustmentScope.Line(LinePortion("line-1", quantity = 1)),
                ),
            ),
            adjustment(
                id = "adjustment-2",
                amount = known(Money(4)),
                scope = known(
                    ReceiptAdjustmentScope.Line(LinePortion("line-2", quantity = 1)),
                ),
            ),
        )

        val issues = validator.validate(
            draft(
                lines = listOf(line("line-1", quantity = 2), line("line-2", quantity = 1)),
                adjustments = adjustments,
                applications = listOf(application),
            ),
        )

        assertTrue(issues.toString(), issues.isEmpty())
    }

    @Test
    fun `order scoped promotion adjustment is accepted`() {
        val issues = validator.validate(
            draft(
                adjustments = listOf(adjustment(scope = known(ReceiptAdjustmentScope.Order))),
                applications = listOf(application()),
            ),
        )

        assertTrue(issues.toString(), issues.isEmpty())
    }

    @Test
    fun `confirmed binding reports wrong kind direction and unresolved financial facts`() {
        val adjustment = adjustment(
            kind = ReceiptAdjustmentKind.Coupon,
            direction = AdjustmentDirection.Add,
            amount = Fact.Unknown(UnknownFactReason.Unreadable),
            scope = Fact.Unknown(UnknownFactReason.Ambiguous),
        )

        val issues = validator.validate(
            draft(adjustments = listOf(adjustment), applications = listOf(application())),
        )

        assertTrue(
            issues.contains(
                ReceiptValidationIssue.PromotionAdjustmentKindMismatch(
                    applicationId = ApplicationId,
                    adjustmentId = AdjustmentId,
                    actualKind = ReceiptAdjustmentKind.Coupon,
                ),
            ),
        )
        assertTrue(
            issues.contains(
                ReceiptValidationIssue.PromotionAdjustmentDirectionMismatch(
                    applicationId = ApplicationId,
                    adjustmentId = AdjustmentId,
                    actualDirection = AdjustmentDirection.Add,
                ),
            ),
        )
        assertTrue(
            issues.contains(
                ReceiptValidationIssue.PromotionAdjustmentAmountUnresolved(
                    applicationId = ApplicationId,
                    adjustmentId = AdjustmentId,
                ),
            ),
        )
        assertTrue(
            issues.contains(
                ReceiptValidationIssue.PromotionAdjustmentScopeUnresolved(
                    applicationId = ApplicationId,
                    adjustmentId = AdjustmentId,
                ),
            ),
        )
    }

    @Test
    fun `line scoped promotion adjustments cannot exceed participant portions`() {
        val application = application(
            actualAdjustmentIds = setOf("adjustment-outside", "adjustment-too-large"),
        )
        val outside = adjustment(
            id = "adjustment-outside",
            amount = known(Money(5)),
            scope = known(
                ReceiptAdjustmentScope.Line(LinePortion("line-2", quantity = 1)),
            ),
        )
        val tooLarge = adjustment(
            id = "adjustment-too-large",
            amount = known(Money(5)),
            scope = known(
                ReceiptAdjustmentScope.Line(LinePortion("line-1", quantity = 2)),
            ),
        )

        val issues = validator.validate(
            draft(
                lines = listOf(line("line-1", quantity = 2), line("line-2", quantity = 1)),
                adjustments = listOf(outside, tooLarge),
                applications = listOf(application),
            ),
        )

        assertTrue(
            issues.contains(
                ReceiptValidationIssue.PromotionAdjustmentScopeMismatch(
                    applicationId = ApplicationId,
                    adjustmentId = "adjustment-outside",
                    scopedPortion = LinePortion("line-2", quantity = 1),
                    participantQuantity = null,
                ),
            ),
        )
        assertTrue(
            issues.contains(
                ReceiptValidationIssue.PromotionAdjustmentScopeMismatch(
                    applicationId = ApplicationId,
                    adjustmentId = "adjustment-too-large",
                    scopedPortion = LinePortion("line-1", quantity = 2),
                    participantQuantity = 1,
                ),
            ),
        )
    }

    @Test
    fun `actual promotion adjustment currency must match expected discount`() {
        val issues = validator.validate(
            draft(
                adjustments = listOf(adjustment(amount = known(Money(10, "USD")))),
                applications = listOf(application()),
            ),
        )

        assertTrue(
            issues.contains(
                ReceiptValidationIssue.PromotionAdjustmentCurrencyMismatch(
                    applicationId = ApplicationId,
                    expectedCurrencyCode = "TWD",
                    actualCurrencyCodes = setOf("USD"),
                ),
            ),
        )
    }

    @Test
    fun `actual promotion adjustment total must match expected discount without overflow`() {
        val application = application(
            actualAdjustmentIds = setOf("adjustment-max", "adjustment-one"),
            expectedDiscount = known(Money(Long.MAX_VALUE)),
        )
        val adjustments = listOf(
            adjustment(id = "adjustment-max", amount = known(Money(Long.MAX_VALUE))),
            adjustment(id = "adjustment-one", amount = known(Money(1))),
        )

        val issues = validator.validate(
            draft(adjustments = adjustments, applications = listOf(application)),
        )

        assertTrue(
            issues.contains(
                ReceiptValidationIssue.PromotionAdjustmentTotalMismatch(
                    applicationId = ApplicationId,
                    expectedMinorUnits = BigInteger.valueOf(Long.MAX_VALUE),
                    actualMinorUnits = BigInteger.valueOf(Long.MAX_VALUE) + BigInteger.ONE,
                ),
            ),
        )
    }

    @Test
    fun `actual adjustment cannot be shared by promotion applications`() {
        val issues = validator.validate(
            draft(
                adjustments = listOf(adjustment()),
                applications = listOf(
                    application(id = "application-1"),
                    application(id = "application-2"),
                ),
            ),
        )

        assertTrue(
            issues.contains(
                ReceiptValidationIssue.PromotionAdjustmentSharedAcrossApplications(
                    adjustmentId = AdjustmentId,
                    applicationIds = setOf("application-1", "application-2"),
                ),
            ),
        )
    }

    @Test
    fun `missing actual adjustment remains a typed missing reference`() {
        val issues = validator.validate(draft(applications = listOf(application())))

        assertTrue(
            issues.contains(
                ReceiptValidationIssue.MissingReference(
                    ownerId = ApplicationId,
                    missingId = AdjustmentId,
                ),
            ),
        )
    }

    private fun draft(
        lines: Collection<ReceiptLineDraft> = listOf(line("line-1", quantity = 1)),
        adjustments: Collection<ReceiptAdjustment> = emptyList(),
        applications: Collection<PromotionApplication>,
    ) = ReceiptDraft(
        id = "receipt-1",
        merchant = known("寶雅"),
        total = known(Money(100)),
        items = lines,
        adjustments = adjustments,
        promotionOffers = listOf(offer()),
        promotionApplications = applications,
        assemblyStatus = ReceiptAssemblyStatus.CompleteByUser,
    )

    private fun line(
        id: String,
        quantity: Int,
    ) = ReceiptLineDraft(
        id = id,
        rawName = known("商品-$id"),
        quantity = known(quantity),
        printedTotal = known(Money(100)),
    )

    private fun offer() = PromotionOffer(
        id = OfferId,
        title = known("折十元"),
        productMentions = listOf(
            PromotionProductMention(
                id = MentionId,
                displayName = known("指定商品"),
            ),
        ),
        eligibility = known(
            PromotionEligibility.ExplicitProducts(setOf(MentionId)),
        ),
        terms = known<PromotionTerms>(PromotionTerms.FixedDiscount(Money(10))),
    )

    private fun application(
        id: String = ApplicationId,
        participants: Collection<PromotionParticipant> = listOf(participant("line-1")),
        actualAdjustmentIds: Collection<String> = setOf(AdjustmentId),
        expectedDiscount: Fact<Money> = known(Money(10)),
    ) = PromotionApplication(
        id = id,
        offerId = OfferId,
        participants = participants,
        actualAdjustmentIds = actualAdjustmentIds,
        expectedDiscount = expectedDiscount,
        status = PromotionApplicationStatus.Confirmed,
        decisionProvenance = derived("decision-$id"),
    )

    private fun participant(
        receiptLineId: String,
        quantity: Int = 1,
    ) = PromotionParticipant(
        portion = LinePortion(receiptLineId, quantity),
        productMentionId = MentionId,
        status = PromotionMatchStatus.Confirmed,
        matchProvenance = derived("match-$receiptLineId"),
    )

    private fun adjustment(
        id: String = AdjustmentId,
        kind: ReceiptAdjustmentKind = ReceiptAdjustmentKind.Promotion,
        direction: AdjustmentDirection = AdjustmentDirection.Subtract,
        amount: Fact<Money> = known(Money(10)),
        scope: Fact<ReceiptAdjustmentScope> = known(
            ReceiptAdjustmentScope.Line(LinePortion("line-1", quantity = 1)),
        ),
    ) = ReceiptAdjustment(
        id = id,
        kind = kind,
        direction = direction,
        amount = amount,
        scope = scope,
    )

    private fun <T : Any> known(value: T): Fact.Known<T> = Fact.Known(
        value = value,
        provenance = derived("known-value"),
    )

    private fun derived(inputId: String) = FactProvenance.Derived(
        ruleName = "test-rule",
        ruleVersion = "1",
        inputFactIds = setOf(inputId),
    )

    private companion object {
        const val OfferId = "offer-1"
        const val MentionId = "mention-1"
        const val ApplicationId = "application-1"
        const val AdjustmentId = "adjustment-1"
    }
}
