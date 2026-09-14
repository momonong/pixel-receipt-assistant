package com.momonong.pixelreceipt.appfunctions

import androidx.appfunctions.AppFunctionSerializable

/** Observed receipt fields. Unknown values must be null; no monetary arithmetic is requested. */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class AssistantReceiptParameters(
    /** Stable token for this one user request; reuse only for retries with exactly the same input. */
    val requestId: String,
    /** Must be explicitly known to be TWD. Ask the user if unknown; never infer a currency. */
    val currency: String,
    /** Observed merchant name; null if unreadable. */
    val merchant: String?,
    /** Actual transaction date, YYYY-MM-DD; null if unknown, never substitute today's date. */
    val date: String?,
    /** Printed final receipt total in whole TWD dollars, not cents; null if unknown. */
    val totalMinor: Long?,
    /** Each printed product row in order. Preserve identical rows; never include notes or change. */
    val items: List<AssistantItemParameters>,
    /** Separately printed discounts or fees, never inferred to make the totals agree. */
    val adjustments: List<AssistantAdjustmentParameters>,
)

/** One receipt product. No personal ownership or confirmed ledger amounts. */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class AssistantItemParameters(
    /** Original product name; null if unknown. */
    val name: String?,
    /** Positive printed count; null if not visible. Never default to one. */
    val quantity: Int?,
    /** Amount for the entire row in TWD dollars, not unit price; null if unknown. */
    val lineTotalMinor: Long?,
)

/** A separately printed adjustment whose allocation the user must review. */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class AssistantAdjustmentParameters(
    /** Printed label, or null. */
    val label: String?,
    /** Exactly add or subtract. */
    val direction: String,
    /** Nonnegative adjustment amount in TWD dollars; null if unknown. */
    val amountMinor: Long?,
)
