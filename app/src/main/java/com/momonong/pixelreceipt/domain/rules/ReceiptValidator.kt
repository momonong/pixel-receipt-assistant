package com.momonong.pixelreceipt.domain.rules

import com.momonong.pixelreceipt.domain.model.AdjustmentDirection
import com.momonong.pixelreceipt.domain.model.EvidenceLink
import com.momonong.pixelreceipt.domain.model.EvidenceLinkTarget
import com.momonong.pixelreceipt.domain.model.Allocation
import com.momonong.pixelreceipt.domain.model.Fact
import com.momonong.pixelreceipt.domain.model.LinePortion
import com.momonong.pixelreceipt.domain.model.Money
import com.momonong.pixelreceipt.domain.model.PromotionApplication
import com.momonong.pixelreceipt.domain.model.PromotionApplicationStatus
import com.momonong.pixelreceipt.domain.model.PromotionOffer
import com.momonong.pixelreceipt.domain.model.PromotionParticipant
import com.momonong.pixelreceipt.domain.model.ReceiptAdjustment
import com.momonong.pixelreceipt.domain.model.ReceiptAdjustmentKind
import com.momonong.pixelreceipt.domain.model.ReceiptAdjustmentScope
import com.momonong.pixelreceipt.domain.model.ReceiptDraft
import com.momonong.pixelreceipt.domain.model.ReceiptLineDraft
import com.momonong.pixelreceipt.domain.model.candidateValues
import java.math.BigInteger

/** Validates references and exact allocation invariants without inventing missing facts. */
class ReceiptValidator(
    private val promotionApplicationValidator: PromotionApplicationValidator =
        PromotionApplicationValidator(),
) {
    fun validate(draft: ReceiptDraft): List<ReceiptValidationIssue> = buildList {
        if (draft.items.isEmpty()) add(ReceiptValidationIssue.NoLineItems)

        val linesById = draft.items.associateBy(ReceiptLineDraft::id)
        val adjustmentsById = draft.adjustments.associateBy(ReceiptAdjustment::id)
        val promotionOffersById = draft.promotionOffers.associateBy(PromotionOffer::id)

        draft.evidenceLinks.forEach { link ->
            validateEvidenceLink(
                draft,
                link,
                linesById,
                adjustmentsById,
                promotionOffersById,
            )
        }

        validateCurrencies(draft)
        draft.adjustments.forEach { adjustment ->
            adjustment.scope.candidateValues().forEach { scope ->
                validatePortions(
                    ownerId = adjustment.id,
                    portions = scope.portions(),
                    linesById = linesById,
                )
            }
        }
        draft.promotionApplications.forEach { application ->
            validateApplication(
                application,
                promotionOffersById,
                adjustmentsById,
                linesById,
            )
        }
        validateExclusivePromotionAdjustments(draft.promotionApplications)
        draft.allocations.forEach { allocation ->
            if (allocation.adjustmentId !in adjustmentsById) {
                add(
                    ReceiptValidationIssue.MissingReference(
                        ownerId = allocation.id,
                        missingId = allocation.adjustmentId,
                    ),
                )
            }
            validatePortions(
                ownerId = allocation.id,
                portions = listOf(allocation.portion),
                linesById = linesById,
            )
        }

        draft.allocations.groupBy { it.adjustmentId }.forEach { (adjustmentId, allocations) ->
            val adjustment = adjustmentsById[adjustmentId] ?: return@forEach
            validateAllocationScope(adjustment, allocations)
            val knownAmount = (adjustment.amount as? Fact.Known)?.value ?: return@forEach
            val allocated = allocations.fold(BigInteger.ZERO) { total, allocation ->
                total + allocation.amount.minorUnits.toBigInteger()
            }
            if (allocated != knownAmount.minorUnits.toBigInteger()) {
                add(
                    ReceiptValidationIssue.AllocationTotalMismatch(
                        adjustmentId = adjustmentId,
                        expectedMinorUnits = knownAmount.minorUnits.toBigInteger(),
                        allocatedMinorUnits = allocated,
                    ),
                )
            }
        }
    }

    private fun MutableList<ReceiptValidationIssue>.validateAllocationScope(
        adjustment: ReceiptAdjustment,
        allocations: List<Allocation>,
    ) {
        val scope = (adjustment.scope as? Fact.Known)?.value ?: return
        if (scope == ReceiptAdjustmentScope.Order) return

        val allowedQuantities = scope.portions().associate {
            it.receiptLineId to it.quantity.toBigInteger()
        }
        allocations.groupBy { it.portion.receiptLineId }.forEach { (lineId, lineAllocations) ->
            val allowed = allowedQuantities[lineId]
            if (allowed == null) {
                add(
                    ReceiptValidationIssue.AllocationOutsideAdjustmentScope(
                        adjustmentId = adjustment.id,
                        receiptLineId = lineId,
                    ),
                )
                return@forEach
            }
            val allocatedQuantity = lineAllocations.fold(BigInteger.ZERO) { total, allocation ->
                total + allocation.portion.quantity.toBigInteger()
            }
            if (allocatedQuantity > allowed) {
                add(
                    ReceiptValidationIssue.AllocatedQuantityExceedsAdjustmentScope(
                        adjustmentId = adjustment.id,
                        receiptLineId = lineId,
                        scopedQuantity = allowed,
                        allocatedQuantity = allocatedQuantity,
                    ),
                )
            }
        }
    }

    private fun MutableList<ReceiptValidationIssue>.validateEvidenceLink(
        draft: ReceiptDraft,
        link: EvidenceLink,
        linesById: Map<String, ReceiptLineDraft>,
        adjustmentsById: Map<String, ReceiptAdjustment>,
        promotionOffersById: Map<String, PromotionOffer>,
    ) {
        if (link.evidence.assetId !in draft.evidenceAssetIds) {
            add(ReceiptValidationIssue.MissingReference(link.id, link.evidence.assetId))
        }
        val targetExists = when (val target = link.target) {
            is EvidenceLinkTarget.Receipt -> target.localId == draft.id
            is EvidenceLinkTarget.ReceiptLine -> target.localId in linesById
            is EvidenceLinkTarget.Adjustment -> target.localId in adjustmentsById
            is EvidenceLinkTarget.PromotionOffer -> target.localId in promotionOffersById
        }
        if (!targetExists) {
            add(ReceiptValidationIssue.MissingReference(link.id, link.target.localId))
        }
    }

    private fun MutableList<ReceiptValidationIssue>.validateCurrencies(draft: ReceiptDraft) {
        val currencies = buildSet {
            addAll(draft.total.moneyCandidates().map(Money::currencyCode))
            draft.items.forEach { line ->
                addAll(line.printedTotal.moneyCandidates().map(Money::currencyCode))
                addAll(line.referenceOriginalTotal.moneyCandidates().map(Money::currencyCode))
            }
            draft.adjustments.forEach { adjustment ->
                addAll(adjustment.amount.moneyCandidates().map(Money::currencyCode))
            }
            draft.allocations.forEach { add(it.amount.currencyCode) }
        }
        if (currencies.size > 1) add(ReceiptValidationIssue.MixedCurrencies(currencies))
    }

    private fun MutableList<ReceiptValidationIssue>.validateApplication(
        application: PromotionApplication,
        promotionOffersById: Map<String, PromotionOffer>,
        adjustmentsById: Map<String, ReceiptAdjustment>,
        linesById: Map<String, ReceiptLineDraft>,
    ) {
        val offer = promotionOffersById[application.offerId]
        if (offer == null) {
            add(ReceiptValidationIssue.MissingReference(application.id, application.offerId))
        } else if (application.status == PromotionApplicationStatus.Confirmed) {
            val promotionIssues = promotionApplicationValidator.validate(
                offer = offer,
                application = application,
                receiptLines = linesById.values,
            )
            if (promotionIssues.isNotEmpty()) {
                add(
                    ReceiptValidationIssue.InvalidPromotionApplication(
                        applicationId = application.id,
                        issues = promotionIssues,
                    ),
                )
            }
        }
        if (application.status == PromotionApplicationStatus.Confirmed) {
            validatePromotionFinancialBinding(application, adjustmentsById)
        }
        application.actualAdjustmentIds.forEach { adjustmentId ->
            if (adjustmentId !in adjustmentsById) {
                add(
                    ReceiptValidationIssue.MissingReference(
                        ownerId = application.id,
                        missingId = adjustmentId,
                    ),
                )
            }
        }
        validatePortions(
            application.id,
            application.participants.map(PromotionParticipant::portion),
            linesById,
        )
    }

    private fun MutableList<ReceiptValidationIssue>.validatePromotionFinancialBinding(
        application: PromotionApplication,
        adjustmentsById: Map<String, ReceiptAdjustment>,
    ) {
        val expectedDiscount = (application.expectedDiscount as? Fact.Known)?.value ?: return
        val participantQuantities = application.participants.associate {
            it.portion.receiptLineId to it.portion.quantity
        }
        val knownAmounts = mutableListOf<Money>()
        var allAmountsAvailable = true

        application.actualAdjustmentIds.forEach { adjustmentId ->
            val adjustment = adjustmentsById[adjustmentId]
            if (adjustment == null) {
                allAmountsAvailable = false
                return@forEach
            }

            if (adjustment.kind != ReceiptAdjustmentKind.Promotion) {
                add(
                    ReceiptValidationIssue.PromotionAdjustmentKindMismatch(
                        applicationId = application.id,
                        adjustmentId = adjustmentId,
                        actualKind = adjustment.kind,
                    ),
                )
            }
            if (adjustment.direction != AdjustmentDirection.Subtract) {
                add(
                    ReceiptValidationIssue.PromotionAdjustmentDirectionMismatch(
                        applicationId = application.id,
                        adjustmentId = adjustmentId,
                        actualDirection = adjustment.direction,
                    ),
                )
            }

            val amount = (adjustment.amount as? Fact.Known)?.value
            if (amount == null) {
                allAmountsAvailable = false
                add(
                    ReceiptValidationIssue.PromotionAdjustmentAmountUnresolved(
                        applicationId = application.id,
                        adjustmentId = adjustmentId,
                    ),
                )
            } else {
                knownAmounts += amount
            }

            val scope = (adjustment.scope as? Fact.Known)?.value
            if (scope == null) {
                add(
                    ReceiptValidationIssue.PromotionAdjustmentScopeUnresolved(
                        applicationId = application.id,
                        adjustmentId = adjustmentId,
                    ),
                )
            } else if (scope != ReceiptAdjustmentScope.Order) {
                scope.portions().forEach { scopedPortion ->
                    val participantQuantity = participantQuantities[scopedPortion.receiptLineId]
                    if (participantQuantity == null || scopedPortion.quantity > participantQuantity) {
                        add(
                            ReceiptValidationIssue.PromotionAdjustmentScopeMismatch(
                                applicationId = application.id,
                                adjustmentId = adjustmentId,
                                scopedPortion = scopedPortion,
                                participantQuantity = participantQuantity,
                            ),
                        )
                    }
                }
            }
        }

        if (!allAmountsAvailable) return
        val actualCurrencyCodes = knownAmounts.mapTo(linkedSetOf(), Money::currencyCode)
        if (actualCurrencyCodes.any { it != expectedDiscount.currencyCode }) {
            add(
                ReceiptValidationIssue.PromotionAdjustmentCurrencyMismatch(
                    applicationId = application.id,
                    expectedCurrencyCode = expectedDiscount.currencyCode,
                    actualCurrencyCodes = actualCurrencyCodes,
                ),
            )
            return
        }

        val actualTotal = knownAmounts.fold(BigInteger.ZERO) { total, amount ->
            total + amount.minorUnits.toBigInteger()
        }
        val expectedTotal = expectedDiscount.minorUnits.toBigInteger()
        if (actualTotal != expectedTotal) {
            add(
                ReceiptValidationIssue.PromotionAdjustmentTotalMismatch(
                    applicationId = application.id,
                    expectedMinorUnits = expectedTotal,
                    actualMinorUnits = actualTotal,
                ),
            )
        }
    }

    private fun MutableList<ReceiptValidationIssue>.validateExclusivePromotionAdjustments(
        applications: Collection<PromotionApplication>,
    ) {
        applications
            .asSequence()
            .flatMap { application ->
                application.actualAdjustmentIds.asSequence().map { it to application.id }
            }
            .groupBy(
                keySelector = { (adjustmentId, _) -> adjustmentId },
                valueTransform = { (_, applicationId) -> applicationId },
            )
            .forEach { (adjustmentId, applicationIds) ->
                val distinctApplicationIds = applicationIds.toSet()
                if (distinctApplicationIds.size > 1) {
                    add(
                        ReceiptValidationIssue.PromotionAdjustmentSharedAcrossApplications(
                            adjustmentId = adjustmentId,
                            applicationIds = distinctApplicationIds,
                        ),
                    )
                }
            }
    }

    private fun MutableList<ReceiptValidationIssue>.validatePortions(
        ownerId: String,
        portions: Collection<LinePortion>,
        linesById: Map<String, ReceiptLineDraft>,
    ) {
        portions.forEach { portion ->
            val line = linesById[portion.receiptLineId]
            if (line == null) {
                add(
                    ReceiptValidationIssue.MissingReference(
                        ownerId = ownerId,
                        missingId = portion.receiptLineId,
                    ),
                )
                return@forEach
            }
            val purchasedQuantity = (line.quantity as? Fact.Known)?.value ?: return@forEach
            if (portion.quantity > purchasedQuantity) {
                add(
                    ReceiptValidationIssue.PortionExceedsPurchasedQuantity(
                        ownerId = ownerId,
                        lineItemId = portion.receiptLineId,
                        purchasedQuantity = purchasedQuantity,
                        referencedQuantity = portion.quantity,
                    ),
                )
            }
        }
    }
}

private fun ReceiptAdjustmentScope.portions(): Collection<LinePortion> = when (this) {
    is ReceiptAdjustmentScope.Line -> listOf(portion)
    is ReceiptAdjustmentScope.LineSet -> portions
    ReceiptAdjustmentScope.Order -> emptyList()
}

private fun Fact<Money>.moneyCandidates(): List<Money> = candidateValues()

sealed interface ReceiptValidationIssue {
    data object NoLineItems : ReceiptValidationIssue

    data class MixedCurrencies(
        val currencyCodes: Set<String>,
    ) : ReceiptValidationIssue

    data class MissingReference(
        val ownerId: String,
        val missingId: String,
    ) : ReceiptValidationIssue

    data class PortionExceedsPurchasedQuantity(
        val ownerId: String,
        val lineItemId: String,
        val purchasedQuantity: Int,
        val referencedQuantity: Int,
    ) : ReceiptValidationIssue

    data class AllocationTotalMismatch(
        val adjustmentId: String,
        val expectedMinorUnits: BigInteger,
        val allocatedMinorUnits: BigInteger,
    ) : ReceiptValidationIssue

    data class InvalidPromotionApplication(
        val applicationId: String,
        val issues: List<PromotionApplicationIssue>,
    ) : ReceiptValidationIssue

    data class PromotionAdjustmentKindMismatch(
        val applicationId: String,
        val adjustmentId: String,
        val actualKind: ReceiptAdjustmentKind,
    ) : ReceiptValidationIssue

    data class PromotionAdjustmentDirectionMismatch(
        val applicationId: String,
        val adjustmentId: String,
        val actualDirection: AdjustmentDirection,
    ) : ReceiptValidationIssue

    data class PromotionAdjustmentAmountUnresolved(
        val applicationId: String,
        val adjustmentId: String,
    ) : ReceiptValidationIssue

    data class PromotionAdjustmentScopeUnresolved(
        val applicationId: String,
        val adjustmentId: String,
    ) : ReceiptValidationIssue

    data class PromotionAdjustmentScopeMismatch(
        val applicationId: String,
        val adjustmentId: String,
        val scopedPortion: LinePortion,
        val participantQuantity: Int?,
    ) : ReceiptValidationIssue

    data class PromotionAdjustmentCurrencyMismatch(
        val applicationId: String,
        val expectedCurrencyCode: String,
        val actualCurrencyCodes: Set<String>,
    ) : ReceiptValidationIssue

    data class PromotionAdjustmentTotalMismatch(
        val applicationId: String,
        val expectedMinorUnits: BigInteger,
        val actualMinorUnits: BigInteger,
    ) : ReceiptValidationIssue

    data class PromotionAdjustmentSharedAcrossApplications(
        val adjustmentId: String,
        val applicationIds: Set<String>,
    ) : ReceiptValidationIssue

    data class AllocationOutsideAdjustmentScope(
        val adjustmentId: String,
        val receiptLineId: String,
    ) : ReceiptValidationIssue

    data class AllocatedQuantityExceedsAdjustmentScope(
        val adjustmentId: String,
        val receiptLineId: String,
        val scopedQuantity: BigInteger,
        val allocatedQuantity: BigInteger,
    ) : ReceiptValidationIssue
}
