package com.momonong.pixelreceipt.domain.usecase

import com.momonong.pixelreceipt.domain.model.*
import com.momonong.pixelreceipt.domain.port.*
import com.momonong.pixelreceipt.domain.rules.*
import java.time.LocalDate
import java.util.UUID

data class ExpenseInput(
    val method: ExpenseSplitMethod = ExpenseSplitMethod.ByQuantity,
    val wholePurpose: ExpensePurpose? = null,
    val quantities: Map<ExpensePurpose, String> = emptyMap(),
    val amounts: Map<ExpensePurpose, String> = emptyMap(),
    val basisKey: String? = null,
    val confirmedAtEpochMillis: Long? = null,
)

data class AdjustmentExpenseInput(
    val method: ExpenseAdjustmentMethod = ExpenseAdjustmentMethod.ProportionalAmount,
    val amounts: Map<ExpensePurpose, String> = emptyMap(),
    val basisKey: String? = null,
    val confirmedAtEpochMillis: Long? = null,
)

/** Raw editor values remain separate from persisted facts, including invalid partial input. */
data class ReviewLineInput(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val quantity: String = "",
    val amount: String = "",
    val evidenceIds: Set<String> = emptySet(),
    val expense: ExpenseInput? = null,
)

data class ReviewAdjustmentInput(
    val id: String = UUID.randomUUID().toString(),
    val amount: String = "",
    val direction: AdjustmentDirection = AdjustmentDirection.Subtract,
    val kind: ReceiptAdjustmentKind = ReceiptAdjustmentKind.Other,
    /** empty = unresolved, order = whole receipt, lines = explicit portions. */
    val scope: String = "",
    val portions: Map<String, String> = emptyMap(),
    val evidenceIds: Set<String> = emptySet(),
    val expense: AdjustmentExpenseInput? = null,
)

data class ReviewInput(
    val merchant: String = "",
    val date: String = "",
    val total: String = "",
    val complete: Boolean = false,
    val evidenceIds: Set<String> = emptySet(),
    val lines: List<ReviewLineInput> = emptyList(),
    val adjustments: List<ReviewAdjustmentInput> = emptyList(),
) {
    companion object {
        fun from(draft: ReceiptDraft) = ReviewInput(
            merchant = draft.merchant.inputText(), date = draft.transactionDate.inputText(),
            total = draft.total.inputText(),
            complete = draft.assemblyStatus in setOf(ReceiptAssemblyStatus.CompleteByUser, ReceiptAssemblyStatus.CompleteByRule),
            evidenceIds = draft.referencesFor(EvidenceLinkTarget.Receipt(draft.id)),
            lines = draft.items.map { ReviewLineInput(it.id, it.rawName.inputText(), it.quantity.inputText(),
                it.printedTotal.inputText(), draft.referencesFor(EvidenceLinkTarget.ReceiptLine(it.id)),
                draft.personalExpenses.find { d -> d.lineId == it.id }?.let { d -> ExpenseInput(d.method, d.wholePurpose,
                    d.quantities.mapValues { e -> e.value.toString() }, d.amounts.mapValues { e -> e.value.toString() }, d.basisKey, d.confirmedAtEpochMillis) }) },
            adjustments = draft.adjustments.map { adjustment ->
                val scope = (adjustment.scope as? Fact.Known)?.value
                val portions = when (scope) {
                    is ReceiptAdjustmentScope.Line -> listOf(scope.portion)
                    is ReceiptAdjustmentScope.LineSet -> scope.portions.toList()
                    else -> emptyList()
                }
                ReviewAdjustmentInput(adjustment.id, adjustment.amount.inputText(), adjustment.direction,
                    adjustment.kind, when (scope) { null -> ""; ReceiptAdjustmentScope.Order -> "order"; else -> "lines" },
                    portions.associate { it.receiptLineId to it.quantity.toString() },
                    draft.referencesFor(EvidenceLinkTarget.Adjustment(adjustment.id)),
                    draft.expenseAdjustments.find { it.adjustmentId == adjustment.id }?.let { d -> AdjustmentExpenseInput(d.method,
                        d.amounts.mapValues { e -> e.value.toString() }, d.basisKey, d.confirmedAtEpochMillis) })
            },
        )
    }
}

fun Fact<*>.inputText(): String = when (val value = (this as? Fact.Known)?.value) {
    null -> ""
    is Money -> value.minorUnits.toString()
    else -> value.toString()
}

private fun ReceiptDraft.referencesFor(target: EvidenceLinkTarget): Set<String> =
    evidenceLinks.filter { it.target == target && it.status == EvidenceLinkStatus.Confirmed }
        .mapTo(linkedSetOf()) { it.evidence.assetId }

object ReviewParsing {
    fun amount(text: String): Long {
        require(text.matches(Regex("[0-9]+"))) { "金額須為非負整數最小單位，不接受小數、符號或分隔符號。" }
        return requireNotNull(text.toLongOrNull()) { "金額超出可保存範圍。" }
    }
    fun quantity(text: String): Int {
        require(text.matches(Regex("[0-9]+"))) { "數量須為正整數。" }
        return requireNotNull(text.toIntOrNull()?.takeIf { it > 0 }) { "數量須為 1 至 2147483647。" }
    }
    fun date(text: String): String {
        require(text.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}"))) { "日期須為 YYYY-MM-DD。" }
        require(runCatching { LocalDate.parse(text) }.isSuccess) { "交易日期不存在。" }
        return text
    }
}

data class ReviewEvaluation(val draft: ReceiptDraft?, val errors: List<String>)

/** Applies only editable fields; unrelated facts and unknown original prices are preserved. */
class ManualReceiptReview(private val repository: ReceiptRepository) {
    fun evaluate(base: ReceiptDraft, input: ReviewInput, now: Long): ReviewEvaluation {
        val errors = mutableListOf<String>()
        val initial = ReviewInput.from(base)
        val links = base.evidenceLinks.toMutableList()
        fun refs(ids: Set<String>, target: EvidenceLinkTarget): Set<EvidenceReference> {
            require(ids.all { it in base.evidenceAssetIds }) { "核對圖片已不存在，請重新載入。" }
            return ids.mapTo(linkedSetOf()) { EvidenceReference(it) }.also { references ->
                references.forEach { reference ->
                    if (links.none { it.evidence == reference && it.target == target && it.status == EvidenceLinkStatus.Confirmed }) {
                        links += EvidenceLink(UUID.randomUUID().toString(), reference, target,
                            EvidenceLinkStatus.Confirmed, FactProvenance.UserConfirmed(now, references),
                            rationale = "使用者於 revision ${base.revision} 人工指定核對依據")
                    }
                }
            }
        }
        fun <T : Any> field(label: String, text: String, old: Fact<T>, evidence: Set<EvidenceReference>, parse: (String) -> T): Fact<T> {
            val trimmed = text.trim()
            if (trimmed == old.inputText()) return old
            if (trimmed.isEmpty()) return Fact.Unknown(UnknownFactReason.NotObserved, evidence)
            return try { Fact.Known(parse(trimmed), FactProvenance.UserConfirmed(now, evidence)) }
            catch (error: IllegalArgumentException) { errors += "$label：${error.message}"; old }
        }
        return try {
            require(input.lines.size <= 100 && input.adjustments.size <= 50) { "單筆最多 100 品項及 50 調整。" }
            val currency = (base.total as? Fact.Known)?.value?.currencyCode ?: "TWD"
            val receiptRefs = refs(input.evidenceIds, EvidenceLinkTarget.Receipt(base.id))
            val lines = input.lines.mapIndexed { index, line ->
                val old = base.items.find { it.id == line.id } ?: ReceiptLineDraft(line.id)
                val evidence = refs(line.evidenceIds, EvidenceLinkTarget.ReceiptLine(line.id))
                old.copy(
                    rawName = field("品項 ${index + 1} 名稱", line.name, old.rawName, evidence) { it },
                    quantity = field("品項 ${index + 1} 數量", line.quantity, old.quantity, evidence, ReviewParsing::quantity),
                    printedTotal = field("品項 ${index + 1} 金額", line.amount, old.printedTotal, evidence) { Money(ReviewParsing.amount(it), currency) },
                )
            }
            val adjustments = input.adjustments.mapIndexed { index, adjustment ->
                val old = base.adjustments.find { it.id == adjustment.id }
                val evidence = refs(adjustment.evidenceIds, EvidenceLinkTarget.Adjustment(adjustment.id))
                val oldScope = old?.scope ?: Fact.Unknown(UnknownFactReason.NotObserved)
                val previousInput = initial.adjustments.find { it.id == adjustment.id }
                val scope = if (previousInput != null && previousInput.scope == adjustment.scope && previousInput.portions == adjustment.portions) oldScope
                else try {
                    val value: ReceiptAdjustmentScope? = when (adjustment.scope) {
                        "" -> null
                        "order" -> ReceiptAdjustmentScope.Order
                        "lines" -> {
                            require(adjustment.portions.isNotEmpty()) { "請選擇至少一個品項及適用數量。" }
                            val portions = adjustment.portions.map { (id, quantity) -> LinePortion(id, ReviewParsing.quantity(quantity.trim())) }
                            if (portions.size == 1) ReceiptAdjustmentScope.Line(portions.single()) else ReceiptAdjustmentScope.LineSet(portions)
                        }
                        else -> error("Unsupported editor scope")
                    }
                    value?.let { Fact.Known(it, FactProvenance.UserConfirmed(now, evidence)) }
                        ?: Fact.Unknown(UnknownFactReason.NotObserved, evidence)
                } catch (error: IllegalArgumentException) { errors += "調整 ${index + 1} 範圍：${error.message}"; oldScope }
                ReceiptAdjustment(adjustment.id, adjustment.kind, adjustment.direction,
                    field("調整 ${index + 1} 金額", adjustment.amount, old?.amount ?: Fact.Unknown(UnknownFactReason.NotObserved), evidence) { Money(ReviewParsing.amount(it), currency) }, scope)
            }
            val deletedLines = base.items.map { it.id }.toSet() - lines.map { it.id }.toSet()
            val deletedAdjustments = base.adjustments.map { it.id }.toSet() - adjustments.map { it.id }.toSet()
            links.removeAll { (it.target is EvidenceLinkTarget.ReceiptLine && it.target.localId in deletedLines) ||
                (it.target is EvidenceLinkTarget.Adjustment && it.target.localId in deletedAdjustments) }
            val draft = base.copy(
                merchant = field("商家", input.merchant, base.merchant, receiptRefs) { it },
                transactionDate = field("交易日期", input.date, base.transactionDate, receiptRefs, ReviewParsing::date),
                total = field("收據總額", input.total, base.total, receiptRefs) { Money(ReviewParsing.amount(it), currency) },
                items = lines, adjustments = adjustments, evidenceLinks = links,
                personalExpenses = input.lines.mapNotNull { line -> line.expense?.let { e ->
                    LineExpenseDecision(line.id, e.method, e.wholePurpose,
                        e.quantities.filterValues { it.isNotBlank() }.mapValues { (_, text) ->
                            ReviewParsing.amount(text.trim()).also { require(it <= Int.MAX_VALUE) { "歸屬件數超出範圍。" } }.toInt()
                        }, e.amounts.filterValues { it.isNotBlank() }.mapValues { ReviewParsing.amount(it.value.trim()) }, e.basisKey, e.confirmedAtEpochMillis)
                } },
                expenseAdjustments = input.adjustments.mapNotNull { a -> a.expense?.let { e ->
                    ExpenseAdjustmentDecision(a.id, e.method,
                        e.amounts.filterValues { it.isNotBlank() }.mapValues { ReviewParsing.amount(it.value.trim()) }, e.basisKey, e.confirmedAtEpochMillis)
                } },
                assemblyStatus = if (input.complete == initial.complete) base.assemblyStatus
                    else if (input.complete) ReceiptAssemblyStatus.CompleteByUser else ReceiptAssemblyStatus.Collecting,
            )
            // Do not silently delete promotion/allocation dependencies when removing an entity.
            if (deletedLines.isNotEmpty() || deletedAdjustments.isNotEmpty()) {
                ReceiptValidator().validate(draft).filterIsInstance<ReceiptValidationIssue.MissingReference>()
                    .forEach { errors += "${it.ownerId} 仍引用 ${it.missingId}，請先移除相關調整；促銷／分攤需其他流程處理。" }
            }
            ReviewEvaluation(draft.takeIf { errors.isEmpty() }, errors)
        } catch (error: IllegalArgumentException) { ReviewEvaluation(null, errors + (error.message ?: "輸入無效")) }
    }

    suspend fun save(base: ReceiptDraft, input: ReviewInput, now: Long): ReviewSaveResult {
        if (base.stage != ReceiptStage.NeedsReview) return ReviewSaveResult.Rejected(listOf("目前狀態不可編輯。"))
        val evaluated = evaluate(base, input, now)
        val draft = evaluated.draft ?: return ReviewSaveResult.Rejected(evaluated.errors)
        if (base.revision == Long.MAX_VALUE) return ReviewSaveResult.Rejected(listOf("版本已達上限。"))
        val next = draft.copy(revision = base.revision + 1)
        return when (repository.compareAndSetDraft(next, base.revision)) {
            is DraftWriteResult.Written -> ReviewSaveResult.Saved(next)
            DraftWriteResult.Conflict -> ReviewSaveResult.Conflict
            DraftWriteResult.NotFound -> ReviewSaveResult.Rejected(listOf("草稿已不存在。"))
        }
    }
}

sealed interface ReviewSaveResult {
    data class Saved(val draft: ReceiptDraft) : ReviewSaveResult
    data class Rejected(val reasons: List<String>) : ReviewSaveResult
    data object Conflict : ReviewSaveResult
}
