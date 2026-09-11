package com.momonong.pixelreceipt.feature.inbox

import androidx.lifecycle.SavedStateHandle
import com.google.gson.Gson
import com.momonong.pixelreceipt.data.local.DraftCodec
import com.momonong.pixelreceipt.domain.model.*
import com.momonong.pixelreceipt.domain.port.ReceiptRepository
import com.momonong.pixelreceipt.domain.rules.*
import com.momonong.pixelreceipt.domain.usecase.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class ReviewState(
    val base: ReceiptDraft? = null,
    val input: ReviewInput = ReviewInput(),
    val busy: Boolean = false,
    val message: String? = null,
    val latest: ReceiptDraft? = null,
    val conflict: Boolean = false,
    val operation: String? = null,
) {
    val dirty: Boolean get() = base?.let { input != ReviewInput.from(it) } ?: false
    val editable: Boolean get() = base?.stage == ReceiptStage.NeedsReview && !busy && !conflict
}

/** Owned by InboxViewModel. Raw edits and their original CAS snapshot survive saved-state restore. */
class ReviewSession(
    private val repository: ReceiptRepository,
    private val saved: SavedStateHandle,
    private val scope: CoroutineScope,
) {
    private val codec = DraftCodec()
    private val gson = Gson()
    private val useCase = ManualReceiptReview(repository)
    private val _state = MutableStateFlow(restore())
    val state = _state.asStateFlow()

    private fun restore(): ReviewState = try {
        val base = saved.get<String>("review.base")?.let(codec::decode)
        val input = saved.get<String>("review.input")?.let { gson.fromJson(it, ReviewInput::class.java) }
        ReviewState(base, input ?: base?.let(ReviewInput::from) ?: ReviewInput())
    } catch (_: RuntimeException) {
        ReviewState(message = "無法復原編輯緩衝，已保存的草稿仍在；請重新開啟核對。")
    }

    private fun publish(value: ReviewState) {
        saved["review.base"] = value.base?.let(codec::encode)
        saved["review.input"] = value.base?.let { gson.toJson(value.input) }
        _state.value = value
    }

    fun open(id: String) {
        if (_state.value.busy || _state.value.base != null) return
        action {
            var base = repository.observeDraft(id).first() ?: error("草稿不存在。")
            if (base.stage == ReceiptStage.Captured) {
                when (val result = TransitionReceiptStage(repository)(base, ReceiptStage.NeedsReview)) {
                    is TransitionReceiptStageResult.Updated -> base = result.draft
                    else -> error("開啟時資料已更新，請重新進入核對。")
                }
            }
            publish(ReviewState(base, ReviewInput.from(base), busy = true))
        }
    }

    fun edit(input: ReviewInput) {
        if (!_state.value.editable) return
        val previous = _state.value.input
        // Changed contents must be checked again; never carry a stale completeness assertion.
        fun contents(value: ReviewInput) = value.copy(complete = false,
            lines = value.lines.map { it.copy(expense = null) }, adjustments = value.adjustments.map { it.copy(expense = null) })
        val next = if (previous.complete && contents(input) != contents(previous))
            input.copy(complete = false) else input
        publish(_state.value.copy(input = next, message = null))
    }

    /** Lazy UI callbacks can outlive their rendered snapshot. Merge the action into current input. */
    fun update(change: (ReviewInput) -> ReviewInput) = edit(change(_state.value.input))
    fun editLine(line: ReviewLineInput) = update { current ->
        current.copy(lines = current.lines.map { if (it.id == line.id) line else it })
    }
    fun editAdjustment(adjustment: ReviewAdjustmentInput) = update { current ->
        current.copy(adjustments = current.adjustments.map { if (it.id == adjustment.id) adjustment else it })
    }

    fun close() {
        if (!_state.value.busy) publish(ReviewState())
    }

    fun save(afterSaved: () -> Unit = {}) {
        val state = _state.value
        if (!state.editable || !state.dirty) return
        action("正在保存草稿…") {
            when (val result = useCase.save(requireNotNull(state.base), state.input, System.currentTimeMillis())) {
                is ReviewSaveResult.Saved -> {
                    publish(ReviewState(result.draft, ReviewInput.from(result.draft), message = "修改已保存。"))
                    afterSaved()
                }
                is ReviewSaveResult.Rejected -> publish(_state.value.copy(message = result.reasons.joinToString("\n")))
                ReviewSaveResult.Conflict -> conflict(requireNotNull(state.base).id)
            }
        }
    }

    /** Refresh only an unchanged buffer after a user-requested photo append. */
    fun refreshAfterImport() {
        if (!_state.value.dirty && !_state.value.busy) reload()
    }

    internal fun attachmentBusy(busy: Boolean) {
        _state.value = _state.value.copy(busy = busy, operation = if (busy) "正在保存照片…" else null)
    }

    fun confirm() {
        val state = _state.value
        val base = state.base ?: return
        if (!state.editable || state.dirty) return
        action("正在確認記帳…") {
            when (val result = TransitionReceiptStage(repository)(base, ReceiptStage.Confirmed)) {
                is TransitionReceiptStageResult.Updated -> publish(ReviewState(result.draft, ReviewInput.from(result.draft), busy = true, message = "已確認記帳，此交易唯讀。"))
                is TransitionReceiptStageResult.ConfirmationBlocked -> publish(_state.value.copy(message = reconciliationText(result.reconciliation)))
                is TransitionReceiptStageResult.ExpenseBlocked -> publish(_state.value.copy(message = result.reasons.joinToString("\n")))
                TransitionReceiptStageResult.Conflict -> conflict(base.id)
                else -> publish(_state.value.copy(message = "目前狀態無法確認，請重新載入草稿。"))
            }
        }
    }

    /** Only called after the user explicitly chooses to discard their retained local buffer. */
    fun reload() {
        val id = _state.value.base?.id ?: return
        if (_state.value.busy) return
        action {
            val latest = repository.observeDraft(id).first() ?: error("草稿已不存在。")
            publish(ReviewState(latest, ReviewInput.from(latest), busy = true, message = "已載入最新版，請重新核對。"))
        }
    }

    fun evaluation(): ReviewEvaluation = _state.value.let { state ->
        state.base?.let { useCase.evaluate(it, state.input, System.currentTimeMillis()) }
            ?: ReviewEvaluation(null, emptyList())
    }

    private suspend fun conflict(id: String) {
        publish(_state.value.copy(conflict = true, message = "版本衝突：本地輸入已保留，沒有覆蓋資料。"))
        publish(_state.value.copy(latest = repository.observeDraft(id).first()))
    }

    private fun action(label: String = "正在開啟消費…", block: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true, message = null, operation = label)
        scope.launch {
            try { block() }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                publish(_state.value.copy(message = "操作失敗，輸入已保留：${error.message ?: "請重試"}"))
            } finally { _state.value = _state.value.copy(busy = false, operation = null) }
        }
    }
}

fun stageText(stage: ReceiptStage): String = when (stage) {
    ReceiptStage.Captured -> "草稿已保存 · 待填寫"
    ReceiptStage.NeedsReview -> "草稿已保存 · 待完成"
    ReceiptStage.Confirmed -> "已確認記帳 · 唯讀"
    ReceiptStage.PendingAnalysis -> "等待分析"
    ReceiptStage.Analyzing -> "分析中"
    ReceiptStage.AnalysisFailed -> "分析失敗"
    else -> "${stage.name} · 唯讀"
}

fun reconciliationText(result: ReceiptReconciliationResult): String = when (result) {
    is ReceiptReconciliationResult.Balanced -> "完全平衡：計算總額 ${result.computedMinorUnits} ${result.currencyCode}，差額 0。"
    is ReceiptReconciliationResult.WithinTolerance -> "容差內：計算總額 ${result.computedMinorUnits}，收據總額 ${result.receiptMinorUnits}，差額 ${result.differenceMinorUnits} ${result.currencyCode}（允許 ±1 最小單位）。"
    is ReceiptReconciliationResult.Unbalanced -> "無法確認：計算總額 ${result.computedMinorUnits}，收據總額 ${result.receiptMinorUnits}，差額 ${result.differenceMinorUnits} ${result.currencyCode}；需非負總額且差額在 ±1 最小單位內。"
    is ReceiptReconciliationResult.Indeterminate -> result.gaps.joinToString("\n") { gap -> when (gap) {
        is ReconciliationGap.IncompleteReceipt -> "尚未確認收據／品項已完整。"
        ReconciliationGap.Merchant -> "商家未知或有衝突。"
        ReconciliationGap.ReceiptTotal -> "收據總額未知或有衝突。"
        is ReconciliationGap.LineName -> "品項 ${gap.lineItemId}：名稱未知或有衝突。"
        is ReconciliationGap.LineQuantity -> "品項 ${gap.lineItemId}：數量未知或有衝突。"
        is ReconciliationGap.LineAmount -> "品項 ${gap.lineItemId}：收據行金額未知或有衝突。"
        is ReconciliationGap.AdjustmentAmount -> "調整 ${gap.adjustmentId}：金額未知或有衝突。"
        is ReconciliationGap.AdjustmentScope -> "調整 ${gap.adjustmentId}：適用範圍未知或有衝突。"
        is ReconciliationGap.PromotionApplication -> "有既存促銷尚未確認；請先完成相關促銷核對。"
    } }
    is ReceiptReconciliationResult.Invalid -> result.issues.joinToString("\n") { issue -> when (issue) {
        ReceiptValidationIssue.NoLineItems -> "至少需要一個品項。"
        is ReceiptValidationIssue.MixedCurrencies -> "不可混用幣別：${issue.currencyCodes}。"
        is ReceiptValidationIssue.MissingReference -> "${issue.ownerId} 引用不存在的 ${issue.missingId}。"
        is ReceiptValidationIssue.PortionExceedsPurchasedQuantity -> "品項 ${issue.lineItemId} 的調整適用數量 ${issue.referencedQuantity} 超過購買數量 ${issue.purchasedQuantity}。"
        else -> "既存促銷或分攤資料不一致，請保留草稿；相關資料修正後才能確認記帳。"
    } }
}
