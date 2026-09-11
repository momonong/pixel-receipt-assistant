package com.momonong.pixelreceipt.domain.rules

import com.momonong.pixelreceipt.domain.model.*
import java.math.BigInteger
import java.security.MessageDigest

data class PersonalExpenseSummary(
    val selfMinor: Long? = null,
    val giftMinor: Long? = null,
    val advanceMinor: Long? = null,
    val personalMinor: Long? = null,
    val accountedMinor: Long? = null,
    val receiptMinor: Long? = null,
    /** computed - receipt, never allocated to a person. */
    val differenceMinor: BigInteger? = null,
    val currency: String = "TWD",
    val pending: List<String> = emptyList(),
) { val ready: Boolean get() = pending.isEmpty() && personalMinor != null }

/** Integer-only analytical ledger. Does not change reconciliation, receipt amounts or scope. */
object PersonalExpenseCalculator {
    private val purposes = ExpensePurpose.entries
    private fun Long.big() = BigInteger.valueOf(this)
    private fun <T : Any> Fact<T>.known(): T? = (this as? Fact.Known)?.value

    /** Versioned, length-delimited monetary inputs; no revision/time or private photo content. */
    fun basis(draft: ReceiptDraft): String {
        val parts = mutableListOf("expense-v1", draft.total.known()?.toString() ?: "unknown")
        draft.items.forEach { parts += listOf(it.id, it.rawName.known() ?: "unknown",
            it.quantity.known()?.toString() ?: "unknown", it.printedTotal.known()?.toString() ?: "unknown") }
        draft.adjustments.forEach {
            parts += listOf(it.id, it.direction.name, it.kind.name, it.amount.known()?.toString() ?: "unknown")
            when (val scope = it.scope.known()) {
                ReceiptAdjustmentScope.Order -> parts += "order"
                is ReceiptAdjustmentScope.Line -> parts += listOf(scope.portion.receiptLineId, scope.portion.quantity.toString())
                is ReceiptAdjustmentScope.LineSet -> scope.portions.sortedBy { p -> p.receiptLineId }.forEach { p -> parts += listOf(p.receiptLineId, p.quantity.toString()) }
                null -> parts += "unknown"
            }
        }
        val bytes = parts.joinToString("") { "${it.length}:$it" }.toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }

    /** Gross line decisions depend on that line. Separate discounts invalidate their own shares. */
    fun lineBasis(draft: ReceiptDraft, lineId: String): String = basis(draft.copy(
        total = Fact.Unknown(UnknownFactReason.NotObserved), items = draft.items.filter { it.id == lineId }, adjustments = emptyList()))

    /** Adjustment approval is also tied to the line ownership, so changing it invalidates shares. */
    fun adjustmentBasis(draft: ReceiptDraft): String = basis(draft) + draft.personalExpenses
        .sortedBy { it.lineId }.joinToString("|") { d ->
            "${d.lineId}:${d.method}:${d.wholePurpose}:" + purposes.joinToString(",") { "${d.quantities[it]}:${d.amounts[it]}" }
        }

    /** Largest remainder; ties consistently go Self, Advance, Gift. All operations are exact. */
    internal fun distribute(amount: Long, weights: Map<ExpensePurpose, BigInteger>): Map<ExpensePurpose, Long> {
        require(amount >= 0 && weights.values.all { it.signum() >= 0 })
        val total = weights.values.fold(BigInteger.ZERO, BigInteger::add)
        if (amount == 0L) return purposes.associateWith { 0L }
        require(total.signum() > 0) { "分配基準為零，請自行指定金額。" }
        val qr = purposes.associateWith { amount.big().multiply(weights[it] ?: BigInteger.ZERO).divideAndRemainder(total) }
        val result = purposes.associateWith { qr.getValue(it)[0].longValueExact() }.toMutableMap()
        val left = amount.big() - result.values.fold(BigInteger.ZERO) { a, b -> a + b.big() }
        purposes.sortedWith(compareByDescending<ExpensePurpose> { qr.getValue(it)[1] }.thenBy { it.ordinal })
            .take(left.intValueExact()).forEach { result[it] = Math.addExact(result.getValue(it), 1L) }
        return result
    }

    fun calculate(draft: ReceiptDraft): PersonalExpenseSummary {
        val total = draft.total.known()
        val pending = mutableListOf<String>()
        val lineShares = mutableMapOf<String, Map<ExpensePurpose, Long>>()
        val totals = purposes.associateWith { BigInteger.ZERO }.toMutableMap()
        if (draft.items.isEmpty()) pending += "尚無品項。"
        if (total == null) pending += "整筆付款金額待核對。"
        if (draft.allocations.isNotEmpty() || draft.promotionApplications.isNotEmpty() || draft.promotionOffers.isNotEmpty())
            pending += "既存促銷／分攤關聯須先核對，本流程不推測其個人分配。"
        if (draft.personalExpenses.map { it.lineId }.distinct().size != draft.personalExpenses.size ||
            draft.personalExpenses.any { d -> draft.items.none { it.id == d.lineId } }) pending += "品項歸屬與清單不一致。"
        draft.items.forEachIndexed { index, line ->
            val label = "第 ${index + 1} 項「${line.rawName.known() ?: "品名待核對"}」"
            val decision = draft.personalExpenses.find { it.lineId == line.id }
            val quantity = line.quantity.known()
            val money = line.printedTotal.known()
            try {
                require(decision != null) { "待分類" }
                require(quantity != null && quantity > 0 && money != null && money.minorUnits >= 0) { "數量／行合計待核對" }
                require(total == null || money.currencyCode == total.currencyCode) { "幣別不同" }
                require(decision.basisKey == lineBasis(draft, line.id) && decision.confirmedAtEpochMillis != null) { "待確認歸屬；品項或金額變更後須重新確認" }
                val shares = if (decision.method == ExpenseSplitMethod.WholeLine) {
                    require(decision.wholePurpose != null) { "待分類" }
                    purposes.associateWith { if (it == decision.wholePurpose) money.minorUnits else 0L }
                } else {
                    require(decision.quantities.values.all { it >= 0 }) { "歸屬件數不可為負數" }
                    require(decision.quantities.values.fold(0L) { a, b -> a + b } == quantity.toLong()) { "歸屬件數須合計等於購買數量；仍有待分類或超額件數" }
                    if (decision.method == ExpenseSplitMethod.ByQuantity) {
                        distribute(money.minorUnits, decision.quantities.mapValues { it.value.toLong().big() })
                    } else {
                        require(purposes.all { (decision.amounts[it] ?: -1) >= 0 }) { "請明確填入各用途金額，無負擔者填 0" }
                        require(decision.amounts.values.fold(BigInteger.ZERO) { a, b -> a + b.big() } == money.minorUnits.big()) { "用途金額須合計等於行合計" }
                        require(purposes.all { (decision.quantities[it] ?: 0) > 0 || decision.amounts[it] == 0L }) { "沒有件數的用途不可分配金額" }
                        decision.amounts
                    }
                }
                lineShares[line.id] = shares
                purposes.forEach { totals[it] = totals.getValue(it) + (shares[it] ?: 0L).big() }
            } catch (error: IllegalArgumentException) { pending += "$label：${error.message}。" }
        }
        val adjustmentKey = adjustmentBasis(draft)
        if (draft.expenseAdjustments.map { it.adjustmentId }.distinct().size != draft.expenseAdjustments.size ||
            draft.expenseAdjustments.any { d -> draft.adjustments.none { it.id == d.adjustmentId } }) pending += "加減項分配與清單不一致。"
        draft.adjustments.forEachIndexed { index, adjustment ->
            try {
                val amount = adjustment.amount.known()
                val scope = adjustment.scope.known()
                require(amount != null && amount.minorUnits >= 0 && scope != null) { "金額或適用範圍待核對" }
                require(total == null || amount.currencyCode == total.currencyCode) { "幣別不同" }
                val portions = when (scope) {
                    ReceiptAdjustmentScope.Order -> draft.items.map { LinePortion(it.id, it.quantity.known() ?: 1) }
                    is ReceiptAdjustmentScope.Line -> listOf(scope.portion)
                    is ReceiptAdjustmentScope.LineSet -> scope.portions.toList()
                }
                val weights = purposes.associateWith { BigInteger.ZERO }.toMutableMap()
                var partial = false
                portions.forEach { portion ->
                    val line = draft.items.find { it.id == portion.receiptLineId }
                    val quantity = line?.quantity?.known()
                    require(quantity != null && portion.quantity in 1..quantity) { "適用數量或品項無效" }
                    if (quantity != portion.quantity) partial = true
                    val shares = lineShares[portion.receiptLineId]
                    require(shares != null) { "適用品項歸屬尚未完成" }
                    purposes.forEach { weights[it] = weights.getValue(it) + (shares[it] ?: 0).big() }
                }
                val decision = draft.expenseAdjustments.find { it.adjustmentId == adjustment.id }
                require(decision != null && decision.basisKey == adjustmentKey && decision.confirmedAtEpochMillis != null) { "請確認費用／折扣的分配方式" }
                val shares = if (decision.method == ExpenseAdjustmentMethod.ProportionalAmount) {
                    require(!partial) { "只適用部分件數，請依實際負擔自行指定金額" }
                    distribute(amount.minorUnits, weights)
                } else {
                    require(purposes.all { (decision.amounts[it] ?: -1) >= 0 }) { "請填齊各用途分配金額（包含 0）" }
                    require(decision.amounts.values.fold(BigInteger.ZERO) { a, b -> a + b.big() } == amount.minorUnits.big()) { "分配合計須等於此筆加減金額" }
                    // A zero-priced item may still bear a fee; membership follows quantities, not money.
                    val eligible = portions.flatMap { p ->
                        val d = draft.personalExpenses.single { it.lineId == p.receiptLineId }
                        if (d.method == ExpenseSplitMethod.WholeLine) listOfNotNull(d.wholePurpose)
                        else d.quantities.filterValues { it > 0 }.keys.toList()
                    }.toSet()
                    require(purposes.all { it in eligible || decision.amounts[it] == 0L }) { "不可分配給適用範圍外的用途" }
                    decision.amounts
                }
                purposes.forEach {
                    val value = (shares[it] ?: 0L).big()
                    totals[it] = totals.getValue(it) + if (adjustment.direction == AdjustmentDirection.Add) value else -value
                }
            } catch (error: IllegalArgumentException) { pending += "第 ${index + 1} 筆加減項：${error.message}。" }
        }
        if (pending.isNotEmpty()) return PersonalExpenseSummary(receiptMinor = total?.minorUnits, currency = total?.currencyCode ?: "TWD", pending = pending)
        return try {
            require(totals.values.all { it.signum() >= 0 }) { "折扣分配導致某用途負擔為負，請重新指定。" }
            val self = totals.getValue(ExpensePurpose.Self).longValueExact()
            val gift = totals.getValue(ExpensePurpose.Gift).longValueExact()
            val advance = totals.getValue(ExpensePurpose.Advance).longValueExact()
            val accounted = totals.values.fold(BigInteger.ZERO, BigInteger::add).longValueExact()
            PersonalExpenseSummary(self, gift, advance, Math.addExact(self, gift), accounted, total?.minorUnits,
                total?.let { accounted.big() - it.minorUnits.big() }, total?.currencyCode ?: "TWD")
        } catch (error: RuntimeException) {
            PersonalExpenseSummary(receiptMinor = total?.minorUnits, pending = listOf(error.message ?: "金額超出可保存範圍。"))
        }
    }
}
