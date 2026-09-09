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

class InboxViewModel(application: Application, private val saved: SavedStateHandle) : AndroidViewModel(application) {
    private val graph = application as ReceiptApplication
    val images get() = graph.images
    val review = ReviewSession(graph.repository, saved, viewModelScope)
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
        if (review.state.value.busy || review.state.value.base != null) return
        saved["selected"] = id
    }
    fun pickerLaunched() { saved["pickerTarget"] = selectedId.value }
    fun picked(uris: List<Uri>) {
        if (uris.isEmpty()) { _error.value = "已取消選取，草稿未變更。"; return }
        ingest(UUID.randomUUID().toString(), saved["pickerTarget"], uris, EvidenceImportSource.PhotoPicker)
    }
    fun shared(id: String, uris: List<Uri>) = ingest(id, null, uris, EvidenceImportSource.ShareSheet)
    fun showError(message: String) { _error.value = message }

    fun createDraft() {
        if (_progress.value != null || review.state.value.busy || review.state.value.base != null) return
        viewModelScope.launch {
            try { select(graph.importer.createEmptyDraft()) } catch (error: Exception) {
                if (error is CancellationException) throw error
                _error.value = readError(error)
            }
        }
    }

    private fun ingest(id: String, target: String?, uris: List<Uri>, source: EvidenceImportSource) {
        if (id in accepted) return
        if (review.state.value.base != null || review.state.value.busy) {
            _error.value = "請先離開人工核對，再重新分享或選取圖片。"
            return
        }
        if (_progress.value != null) {
            _error.value = "已有圖片正在匯入，請完成後重新分享或選取。"
            return
        }
        accepted += id
        _error.value = null
        _progress.value = 0 to uris.size
        importJob = viewModelScope.launch {
            try {
                val result = graph.importer.import(id, target,
                    uris.map { uri -> ImportInput { graph.contentResolver.openSharedImage(uri) } }, source,
                ) { done, total -> _progress.value = done to total }
                result.draftId?.let(::select)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                _error.value = readError(error)
            } finally { _progress.value = null }
        }
    }
}
