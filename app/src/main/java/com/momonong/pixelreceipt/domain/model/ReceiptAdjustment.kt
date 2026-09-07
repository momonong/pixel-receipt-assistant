package com.momonong.pixelreceipt.domain.model

/** A quantity from one printed receipt line participating in a scoped adjustment or promotion. */
data class LinePortion(
    val receiptLineId: String,
    val quantity: Int,
) {
    init {
        require(receiptLineId.isNotBlank()) { "Receipt line id cannot be blank." }
        require(quantity > 0) { "Line portion quantity must be greater than zero." }
    }
}

/** The scope printed on, or safely derived for, a receipt adjustment. */
sealed interface ReceiptAdjustmentScope {
    data class Line(
        val portion: LinePortion,
    ) : ReceiptAdjustmentScope

    class LineSet(
        portions: Collection<LinePortion>,
    ) : ReceiptAdjustmentScope {
        val portions: Set<LinePortion> = portions.toSet()

        init {
            require(this.portions.isNotEmpty()) { "A line-set adjustment requires participants." }
            requireUniqueReceiptLines(this.portions)
        }

        override fun equals(other: Any?): Boolean =
            other is LineSet && portions == other.portions

        override fun hashCode(): Int = portions.hashCode()

        override fun toString(): String = "LineSet(portions=$portions)"
    }

    data object Order : ReceiptAdjustmentScope
}

/**
 * An adjustment remains separate from receipt lines until its scope and allocation are known.
 * [amount] is always unsigned; [direction] determines its effect on the ledger.
 */
data class ReceiptAdjustment(
    val id: String,
    val kind: ReceiptAdjustmentKind,
    val direction: AdjustmentDirection,
    val amount: Fact<Money>,
    val scope: Fact<ReceiptAdjustmentScope>,
) {
    init {
        require(id.isNotBlank()) { "Receipt adjustment id cannot be blank." }
        require(amount !is Fact.NotApplicable) {
            "An existing receipt adjustment must have a known, unknown, or conflicting amount."
        }
        require(scope !is Fact.NotApplicable) {
            "An existing receipt adjustment must have a known, unknown, or conflicting scope."
        }
    }
}

enum class ReceiptAdjustmentKind {
    Promotion,
    Coupon,
    Tax,
    Fee,
    Credit,
    Rounding,
    Other,
}

enum class AdjustmentDirection {
    Add,
    Subtract,
}

/**
 * A derived analytical allocation. It never changes the scope printed on the receipt adjustment.
 */
data class Allocation(
    val id: String,
    val adjustmentId: String,
    val portion: LinePortion,
    val amount: Money,
    val method: AllocationMethod,
    val provenance: FactProvenance,
) {
    init {
        require(id.isNotBlank()) { "Allocation id cannot be blank." }
        require(adjustmentId.isNotBlank()) { "Adjustment id cannot be blank." }
        require(
            provenance is FactProvenance.Derived ||
                provenance is FactProvenance.UserConfirmed,
        ) {
            "An allocation must be derived or explicitly confirmed by the user."
        }
    }
}

enum class AllocationMethod {
    PromotionRule,
    ProportionalGross,
    EqualShare,
    UserDecision,
}

internal fun requireUniqueReceiptLines(portions: Collection<LinePortion>) {
    require(portions.map(LinePortion::receiptLineId).distinct().size == portions.size) {
        "A receipt line can appear only once in the same participant set."
    }
}
