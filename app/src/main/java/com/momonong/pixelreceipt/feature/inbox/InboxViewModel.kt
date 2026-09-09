package com.momonong.pixelreceipt.feature.inbox

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.momonong.pixelreceipt.app.ReceiptApplication
import com.momonong.pixelreceipt.data.ingestion.*
import com.momonong.pixelreceipt.domain.model.EvidenceImportSource
import com.momonong.pixelreceipt.domain.model.EvidenceAsset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID

data class InboxEvidence(val draftId: String?, val assets: List<EvidenceAsset> = emptyList())
data class PendingSharedPhotos(val operationId: String, val uris: List<Uri>)

class InboxViewModel(application: Application, private val saved: SavedStateHandle) : AndroidViewModel(application) {
    private val graph = application as ReceiptApplication
    val images get() = graph.images
    val review = ReviewSession(graph.repository, saved, viewModelScope)
    val extraction = ExtractionSession(graph.extraction, saved, viewModelScope) { id ->
        if (review.state.value.base?.id == id) review.refreshAfterImport() else openTransaction(id)
    }
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private val _progress = MutableStateFlow<Pair<Int, Int>?>(null)
    val progress = _progress.asStateFlow()
    val selectedId = saved.getStateFlow<String?>("selected", null)
    val drafts = graph.repository.drafts.catch { _error.value = "草稿讀取失敗，請重新啟動 App。" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val evidence = selectedId.flatMapLatest { id ->
        if (id == null) flowOf(InboxEvidence(null)) else graph.repository.evidence(id).map { InboxEvidence(id, it) }
    }.catch { _error.value = "圖片資料讀取失敗，請重新啟動 App。" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), InboxEvidence(null))
    val latestImport = graph.database.receipts().observeLatestImport()
        .catch { _error.value = "匯入紀錄讀取失敗。" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    private val accepted = mutableSetOf<String>()
    private var importJob: Job? = null
    private var creating = false
    private val _pendingShare = MutableStateFlow<PendingSharedPhotos?>(null)
    val pendingShare = _pendingShare.asStateFlow()
    fun cancelImport() { importJob?.cancel() }

    init {
        viewModelScope.launch {
            try { graph.importer.recover() } catch (error: Exception) {
                if (error is CancellationException) throw error
                _error.value = readError(error)
            }
        }
    }

    fun select(id: String?) {
        if (extraction.state.value.busy || review.state.value.busy || review.state.value.base != null) return
        saved["selected"] = id
    }
    fun openTransaction(id: String) { select(id); review.open(id) }
    fun pickerLaunched(target: String? = null) { saved["pickerTarget"] = target }
    fun picked(uris: List<Uri>) {
        if (uris.isEmpty()) { _error.value = "已取消選取，草稿未變更。"; return }
        ingest(UUID.randomUUID().toString(), saved["pickerTarget"], uris, EvidenceImportSource.PhotoPicker)
    }
    fun shared(id: String, uris: List<Uri>) = ingest(id, null, uris, EvidenceImportSource.ShareSheet)
    fun showError(message: String) { _error.value = message }

    fun cancelPendingShare() { _pendingShare.value = null }
    fun acceptPendingShare() {
        val pending = _pendingShare.value ?: return
        if (review.state.value.busy || extraction.state.value.busy || _progress.value != null) return
        val start = {
            review.close()
            _pendingShare.value = null
            shared(pending.operationId, pending.uris)
        }
        if (review.state.value.dirty) review.save(start) else start()
    }

    fun createDraft() {
        if (creating) return
        if (extraction.state.value.busy) return
        if (_progress.value != null || review.state.value.busy || review.state.value.base != null) return
        creating = true
        viewModelScope.launch {
            try { openTransaction(graph.importer.createEmptyDraft()) } catch (error: Exception) {
                if (error is CancellationException) throw error
                _error.value = readError(error)
            } finally { creating = false }
        }
    }

    private fun ingest(id: String, target: String?, uris: List<Uri>, source: EvidenceImportSource) {
        if (id in accepted) return
        if (source == EvidenceImportSource.ShareSheet && review.state.value.base != null) {
            if (_pendingShare.value == null) _pendingShare.value = PendingSharedPhotos(id, uris)
            else _error.value = "已有一批分享照片等待處理；請稍後重新分享這一批。"
            return
        }
        if (extraction.state.value.busy) { _error.value = "辨識中，請先完成或取消，再重新匯入圖片。"; return }
        val current = review.state.value
        val appendInReview = target != null && target == current.base?.id && current.editable && !current.dirty
        if ((current.base != null || current.busy) && !appendInReview) {
            _error.value = "目前交易仍在編輯；請先保存並返回消費紀錄，再重新分享新交易的照片。"
            return
        }
        if (_progress.value != null) {
            _error.value = "已有圖片正在匯入，請完成後重新分享或選取。"
            return
        }
        accepted += id
        _error.value = null
        _progress.value = 0 to uris.size
        if (appendInReview) review.attachmentBusy(true)
        importJob = viewModelScope.launch {
            try {
                val result = graph.importer.import(id, target,
                    uris.map { uri -> ImportInput { graph.contentResolver.openSharedImage(uri) } }, source,
                ) { done, total -> _progress.value = done to total }
                if (!appendInReview) result.draftId?.let(::openTransaction)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                _error.value = readError(error)
            } finally {
                _progress.value = null
                if (appendInReview) {
                    review.attachmentBusy(false)
                    review.refreshAfterImport()
                }
            }
        }
    }
}
