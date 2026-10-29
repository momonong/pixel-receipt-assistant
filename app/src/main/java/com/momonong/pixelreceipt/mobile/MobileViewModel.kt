package com.momonong.pixelreceipt.mobile

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.momonong.pixelreceipt.app.ReceiptApplication
import com.momonong.pixelreceipt.data.ingestion.ImportInput
import com.momonong.pixelreceipt.data.ingestion.openSharedImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.util.UUID

class MobileViewModel @JvmOverloads constructor(
    application: Application,
    private val saved: SavedStateHandle,
    private val repository: MobileRepository = (application as ReceiptApplication).mobile,
) : AndroidViewModel(application) {
    private val graph = application
    val images = repository.images
    private val edits = MobileEdits(saved)
    private val reviewEdits = MobileReviewEdits(saved)
    internal val reviewEditState = reviewEdits.state
    internal val editState = edits.state
    val writable = repository.store.writable
    val state = combine(repository.store.state, edits.state, reviewEdits.state) { persisted, _, _ -> reviewEdits.overlay(edits.overlay(persisted)) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, edits.overlay(repository.store.state.value))
    val selected = saved.getStateFlow<String?>("mobileSelected", null)
    val settings = saved.getStateFlow("mobileSettings", false)
    val destination = saved.getStateFlow("mobileDestination", MobilePage.Inbox.name)
    val selectedLedger = saved.getStateFlow<String?>("mobileLedger", null)
    private val reviewerMutable = MutableStateFlow(false)
    val reviewer = reviewerMutable.asStateFlow()
    private val savingReviewMutable = MutableStateFlow(false)
    val savingReview = savingReviewMutable.asStateFlow()
    private var reviewSave: Job? = null
    private var refreshedAt = 0L
    private var refreshCursor = 0
    private val legacyMutable = MutableStateFlow(false)
    val legacy = legacyMutable.asStateFlow()
    private val signedInMutable = MutableStateFlow(false)
    val signedIn = signedInMutable.asStateFlow()
    private val busyMutable = MutableStateFlow<String?>(null)
    val busy = busyMutable.asStateFlow()
    private val duplicateMutable = MutableStateFlow<DuplicateShareReminder?>(null)
    internal val duplicateShare = duplicateMutable.asStateFlow()
    private val messageMutable = MutableStateFlow<String?>(null)
    val message = messageMutable.asStateFlow()
    private val enginesMutable = MutableStateFlow<Map<String, EngineAvailability>>(emptyMap())
    val engines = enginesMutable.asStateFlow()
    private val commands = MobileCommands(viewModelScope)
    private var network: Job? = null
    private var importing = 0
    private val acceptedShares = mutableSetOf<String>()

    init {
        viewModelScope.launch {
            try { signedInMutable.value = repository.initialize(); reviewerMutable.value = repository.reviewerSession() }
            catch (error: Exception) { if (error is CancellationException) throw error; messageMutable.value = mobileError(error) }
        }
    }
    fun openLegacy(open: Boolean) { duplicateMutable.value = null; legacyMutable.value = open }
    fun select(id: String?) { duplicateMutable.value = null; saved["mobileSelected"] = id; saved["mobileSettings"] = false; saved["mobileDestination"] = if (id == null) MobilePage.Inbox.name else MobilePage.Detail.name
        id?.let { current -> if (signedIn.value && repository.store.state.value?.batches?.firstOrNull { it.id == current }?.intakeId != null) refreshBatch(current) }
    }
    fun openSettings(open: Boolean) { duplicateMutable.value = null; saved["mobileSettings"] = open }
    fun back() {
        if (duplicateMutable.value != null) { duplicateMutable.value = null; return }
        if (settings.value) saved["mobileSettings"] = false
        else when (destination.value) {
            MobilePage.Help.name -> saved["mobileDestination"] = saved.get<String>("mobileHelpReturn") ?: MobilePage.Inbox.name
            MobilePage.Review.name -> saved["mobileDestination"] = MobilePage.Detail.name
            MobilePage.LedgerDetail.name -> saved["mobileDestination"] = saved.get<String>("mobileLedgerReturn") ?: MobilePage.Ledger.name
            else -> select(null)
        }
    }
    fun help() { duplicateMutable.value = null; saved["mobileHelpReturn"] = destination.value; saved["mobileDestination"] = MobilePage.Help.name }
    fun ledger() { duplicateMutable.value = null; saved["mobileSettings"] = false; saved["mobileDestination"] = MobilePage.Ledger.name; refreshLedger() }
    fun openLedger(id: String) { duplicateMutable.value = null; saved["mobileLedgerReturn"] = destination.value; saved["mobileLedger"] = id; saved["mobileDestination"] = MobilePage.LedgerDetail.name
        online("正在更新帳目…") { repository.ledgerDetail(id) }
    }
    fun openAccounting(draftId: String?) {
        val batch = repository.store.state.value?.batches?.firstOrNull { it.id == selected.value } ?: return ledger()
        if (batch.ledgerId != null) return openLedger(batch.ledgerId)
        if (draftId == null) return ledger()
        saved["mobileDestination"] = MobilePage.Review.name
        if (!reviewerMutable.value) { messageMutable.value = "請在設定登入一次，即可在 App 內核對。"; return }
        online("正在載入核對內容…") {
            val result = repository.openReview(batch.id)
            if (result.ledgerId != null) showFormalLedger(result)
            else repository.saveReview(batch.id)
        }
    }
    private fun refreshBatch(id: String) = online("正在更新進度…") {
        val result = repository.sync(id)
        if (destination.value == MobilePage.Review.name && selected.value == id && result.ledgerId != null) { showFormalLedger(result); repository.ledgerDetail(result.ledgerId) }
    }
    private fun showFormalLedger(batch: MobileBatch) {
        saved["mobileLedger"] = batch.ledgerId; saved["mobileLedgerReturn"] = MobilePage.Detail.name; saved["mobileDestination"] = MobilePage.LedgerDetail.name
    }
    fun refreshLedger(more: Boolean = false) = online("正在更新帳本…") {
        val count = repository.store.state.value?.ledgerNextOffset ?: 0
        repository.ledger(if (more) count else 0)
    }
    fun foreground() {
        val now = System.currentTimeMillis()
        if (!signedIn.value || busy.value != null || now - refreshedAt < 30_000) return
        refreshedAt = now
        online("正在更新已保存狀態…") {
            val batches = repository.store.state.value?.batches.orEmpty().filter { it.intakeId != null && !it.deleted }
            val chosen = if (batches.isEmpty()) emptyList() else List(minOf(8, batches.size)) { batches[(refreshCursor + it) % batches.size] }
            refreshCursor += chosen.size
            (listOfNotNull(batches.firstOrNull { it.id == selected.value }) + chosen).distinctBy { it.id }.forEach {
                val result = repository.sync(it.id)
                if (destination.value == MobilePage.Review.name && selected.value == it.id && result.ledgerId != null) { showFormalLedger(result); repository.ledgerDetail(result.ledgerId) }
            }
            if (destination.value in setOf(MobilePage.Ledger.name, MobilePage.LedgerDetail.name)) repository.ledger(detailId = selectedLedger.value.takeIf { destination.value == MobilePage.LedgerDetail.name })
        }
    }
    fun editReview(id: String, change: (com.google.gson.JsonObject) -> Unit) {
        if (!writable.value || busy.value != null) return
        val persisted = repository.store.state.value?.batches?.firstOrNull { it.id == id } ?: return
        if (persisted.deleted || repository.store.state.value?.pendingMutation(id) != null || persisted.ledgerId != null || persisted.draftStatus in setOf("confirmed", "voided")) return
        reviewEdits.edit(persisted, change)
        commands.launch { persistReviewEdits(id) }
        scheduleReviewSave(id)
    }
    private suspend fun persistReviewEdits(id: String, retry: Boolean = false) {
        reviewEdits.persist(id, retry) { patch -> repository.persistReview(id, patch.value, patch.expectedRevision) }
    }
    private fun scheduleReviewSave(id: String) {
        reviewSave?.cancel()
        reviewSave = viewModelScope.launch { delay(700); saveReview(id) }
    }
    fun saveReview(id: String) {
        commands.launch {
            savingReviewMutable.value = true
            try {
                persistReviewEdits(id, retry = true)
                if (reviewEdits.pending(id)) return@launch
                repository.saveReview(id)
                val current = repository.store.batch(id).review
                if (current?.dirty == true && current.error == null && prepareReview(current).body != null) scheduleReviewSave(id)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (error is MobileApiFailure && error.statusCode == 401) { signedInMutable.value = false; reviewerMutable.value = false }
                if (error is MobileApiFailure && error.statusCode == 403) reviewerMutable.value = false
                messageMutable.value = mobileError(error)
            }
            finally { savingReviewMutable.value = false }
        }
    }
    fun confirmReview(id: String) = online("正在確認入帳…") {
        persistReviewEdits(id)
        if (reviewEdits.pending(id)) throw MobileFailure("目前編輯尚未保存，請先重試。")
        val result = repository.confirmReview(id)
        if (result.ledgerId != null) { saved["mobileLedger"] = result.ledgerId; saved["mobileLedgerReturn"] = MobilePage.Detail.name; saved["mobileDestination"] = MobilePage.LedgerDetail.name }
    }
    fun compareReview(id: String) = online("正在讀取中央版本…") { repository.compareReview(id) }
    fun resolveReview(id: String, useLocal: Boolean) = online("正在保存選擇…") {
        repository.resolveReview(id, useLocal); repository.saveReview(id)
    }
    fun pickerTarget(id: String?) { saved["mobilePickerTarget"] = id }
    fun picked(uris: List<Uri>) {
        if (uris.isEmpty() || !writable.value) return
        val target = saved.get<String>("mobilePickerTarget") ?: UUID.randomUUID().toString()
        acceptedShares.remove(target) // Explicit re-selection may complete an interrupted import.
        receive(target, uris, "")
        saved["mobilePickerTarget"] = null
    }
    fun receive(id: String, uris: List<Uri>, notes: String) {
        if (!writable.value) { report("本機暫時無法保存，請重試儲存後再分享；來源照片未更動。"); return }
        if (!acceptedShares.add(id)) return // Same ViewModel survives rotation; do not interrupt its upload.
        legacyMutable.value = false
        select(id)
        network?.cancel()
        importing++
        commands.launch {
                busyMutable.value = "正在保存原圖…"
                try {
                    repository.import(id, notes, uris.map { uri -> ImportInput { graph.contentResolver.openSharedImage(uri) } })
                    messageMutable.value = null
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    messageMutable.value = mobileError(error)
                } finally { busyMutable.value = null; importing-- }
        }
    }
    fun report(message: String) { messageMutable.value = message }
    fun edit(id: String, change: (MobileBatch) -> MobileBatch) {
        if (!writable.value || busyMutable.value != null) return
        val persisted = repository.store.state.value?.batches?.firstOrNull { it.id == id } ?: return
        edits.edit(persisted, change)
        commands.launch { persistEdits(id) }
    }
    private suspend fun persistEdits(id: String, retry: Boolean = false) {
        edits.persist(id, retry) { batch, revision -> repository.edit(id, revision) { batch } }
    }
    fun retrySave(id: String?) {
        commands.launch {
            busyMutable.value = "正在重試本機儲存…"
            try {
                if (id != null && edits.pending(id)) persistEdits(id, retry = true)
                else { repository.retryStorage(); signedInMutable.value = repository.initialize() }
                if ((id == null || !edits.pending(id)) && writable.value) messageMutable.value = null
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                messageMutable.value = mobileError(error)
            } finally { busyMutable.value = null }
        }
    }
    fun confirmImport(id: String) {
        if (busyMutable.value != null || !writable.value) return
        busyMutable.value = "正在保存照片核對…"
        commands.launch {
            try {
                if (edits.pending(id)) throw MobileFailure("目前編輯尚未保存，請先重試儲存。")
                val current = repository.store.state.value?.batches?.firstOrNull { it.id == id } ?: return@launch
                repository.confirmImport(id, current.revision)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                messageMutable.value = mobileError(error)
            } finally { busyMutable.value = null }
        }
    }
    private fun online(label: String, action: suspend () -> Unit) {
        if (network?.isActive == true || importing > 0) return
        busyMutable.value = label // Freeze UI intent before any queued IO or Compose recomposition.
        network = commands.launch {
                messageMutable.value = null
                try { action() }
                catch (error: Exception) {
                    if (error is CancellationException) { messageMutable.value = "同步已停止；資料已保留，可重新開啟後手動同步。"; throw error }
                    if (error is MobileApiFailure && error.statusCode == 401) { signedInMutable.value = false; reviewerMutable.value = false }
                    if (error is MobileApiFailure && error.statusCode == 403) reviewerMutable.value = false
                    messageMutable.value = mobileError(error)
                } finally { busyMutable.value = null }
        }
    }
    fun login(password: String) = online("正在登入…") {
        repository.login(password)
        signedInMutable.value = true
        reviewerMutable.value = repository.reviewerSession()
        messageMutable.value = "已登入，可在 App 內核對與入帳。"
        enginesMutable.value = repository.availability()
    }
    fun logout() = online("正在登出…") {
        try {
            val revoked = repository.logout()
            messageMutable.value = if (revoked) "已登出；照片與待傳資料保留。" else "已清除本機登入；中央撤銷尚未確認，請在網頁的裝置管理撤銷此裝置。"
        } finally {
            signedInMutable.value = false
            reviewerMutable.value = false
            enginesMutable.value = emptyMap()
        }
    }
    fun refreshEngines() = online("正在查詢辨識來源…") { enginesMutable.value = repository.availability() }
    fun sync(id: String) = syncWithDuplicateDecision(id, null)
    private fun syncWithDuplicateDecision(id: String, approval: DuplicateShareApproval?) = online("正在同步這筆收件…") {
        if (edits.pending(id)) throw MobileFailure("目前編輯尚未保存，請先重試儲存；這次未上傳。")
        val current = repository.store.state.value?.batches?.firstOrNull { it.id == id } ?: throw MobileFailure("找不到這筆本機收件。")
        try { repository.syncWithDuplicateApproval(id, current.revision, approval) }
        catch (required: DuplicateShareRequired) {
            if (selected.value == id && destination.value == MobilePage.Detail.name && !settings.value && !legacy.value) duplicateMutable.value = required.reminder
        }
        catch (_: DuplicateShareDecisionExpired) {
            if (selected.value == id && destination.value == MobilePage.Detail.name) messageMutable.value = "照片或舊收件已變更；請重新按送出確認。"
        }
    }
    internal fun cancelDuplicateShare(reminder: DuplicateShareReminder) {
        if (duplicateMutable.value === reminder) duplicateMutable.value = null
    }
    internal fun sendSeparateDuplicate(reminder: DuplicateShareReminder) {
        val approval = reminder.approval
        if (busy.value != null || !writable.value || duplicateMutable.value !== reminder || selected.value != approval.incoming.id || destination.value != MobilePage.Detail.name) return
        duplicateMutable.value = null // Consume this exact displayed choice before another tap/callback.
        syncWithDuplicateDecision(approval.incoming.id, approval)
    }
    internal fun openDuplicateShare(reminder: DuplicateShareReminder, id: String) {
        if (duplicateMutable.value !== reminder) return
        if (busy.value != null || reminder.candidates.none { it.id == id }) return
        val current = repository.store.state.value?.batches?.firstOrNull { it.id == id } ?: return
        duplicateMutable.value = null
        if (current.ledgerId != null && !current.deleted) openLedger(current.ledgerId)
        else {
            // Deliberately bypass select(): opening an unknown original must never submit it.
            saved["mobileSelected"] = id; saved["mobileSettings"] = false; saved["mobileDestination"] = MobilePage.Detail.name
        }
        messageMutable.value = "新照片與說明仍保留在收件匣。"
    }
    fun deleteBatch(id: String, reason: String) {
        reviewSave?.cancel()
        online("正在確認刪除結果…") {
            if (edits.pending(id) || reviewEdits.pending(id)) throw MobileFailure("目前編輯尚未保存，請先重試儲存，再刪除。")
            repository.deleteBatch(id, reason)
            val deleted = repository.store.batch(id).deleted
            messageMutable.value = if (deleted) "收據已刪除；原圖與歷史資料保留。" else "請檢查這筆收據的最新中央狀態。"
            if (deleted && selected.value == id && destination.value in setOf(MobilePage.Detail.name, MobilePage.Review.name)) {
                saved["mobileSelected"] = null; saved["mobileDestination"] = MobilePage.Inbox.name
            }
        }
    }
    fun voidEntry(id: String, reason: String) {
        reviewSave?.cancel()
        online("正在確認作廢結果…") {
            repository.voidEntry(id, reason)
            messageMutable.value = "帳目已作廢，不再計入有效支出；原帳目與原因保留。"
        }
    }
    fun stopNetwork() { network?.cancel() }
    fun background() { network?.cancel() }
}
