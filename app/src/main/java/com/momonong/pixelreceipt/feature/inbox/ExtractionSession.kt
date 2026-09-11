package com.momonong.pixelreceipt.feature.inbox

import androidx.lifecycle.SavedStateHandle
import com.momonong.pixelreceipt.domain.model.ReceiptDraft
import com.momonong.pixelreceipt.domain.usecase.ExtractReceipt
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class ExtractionState(val busy: Boolean = false, val message: String? = null, val draftId: String? = null)

class ExtractionSession(private val useCase: ExtractReceipt, private val saved: SavedStateHandle,
    private val scope: CoroutineScope, private val openReview: (String) -> Unit) {
    private val _state = MutableStateFlow(ExtractionState(message =
        if (saved.get<Boolean>("extract.running") == true) "上次辨識已中斷；請查看已保存草稿，並重新選取圖片重試。" else null,
        draftId = saved["extract.draftId"]))
    val state = _state.asStateFlow()
    private val _traditionalOcr = MutableStateFlow(saved.get<Boolean>("extract.traditionalOcr") ?: false)
    val traditionalOcr = _traditionalOcr.asStateFlow()
    fun chooseTraditionalOcr(value: Boolean) {
        if (_state.value.busy) return
        _traditionalOcr.value = value
        saved["extract.traditionalOcr"] = value
    }
    private var job: Job? = null
    private var foreground = false

    init { saved["extract.running"] = false }

    fun foreground(value: Boolean) {
        foreground = value
        if (!value) cancel()
    }

    fun start(base: ReceiptDraft, ids: Set<String>, replaceApproved: Boolean) {
        if (_state.value.busy) return
        if (!foreground) { _state.value = ExtractionState(message = "請回到前景再辨識。", draftId = base.id); return }
        saved["extract.running"] = true
        saved["extract.draftId"] = base.id
        val useOcr = _traditionalOcr.value
        _state.value = ExtractionState(true, if (useOcr) "正在使用傳統 OCR 辨識 ${ids.size} 張收據…"
            else "正在準備 Gemini Nano 並辨識 ${ids.size} 張收據；首次可能需要下載裝置模型，請保持前景…", base.id)
        job = scope.launch {
            var completed: ReceiptDraft? = null
            try {
                completed = withTimeout(240_000) { useCase.run(base, ids, replaceApproved, useOcr) { foreground } }
                _state.value = ExtractionState(message = "已建立 ${completed.items.size} 個待核對品項，請檢查未知值、差額與原圖。", draftId = base.id)
            } catch (_: TimeoutCancellationException) {
                _state.value = ExtractionState(message = "辨識逾時，請查看草稿並重試，或改用人工核對。", draftId = base.id)
            } catch (error: CancellationException) {
                _state.value = ExtractionState(message = "辨識已取消或離開前景；未完成結果不會套用，已保存草稿保留。", draftId = base.id)
                throw error
            } catch (error: Exception) {
                _state.value = ExtractionState(message = "${error.message ?: "辨識失敗"} 草稿保留，可重試或人工輸入。", draftId = base.id)
            } finally {
                saved["extract.running"] = false
                _state.value = _state.value.copy(busy = false)
            }
            completed?.let { openReview(it.id) }
        }
    }

    fun cancel() { job?.cancel() }
}
