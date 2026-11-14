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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
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
    private val automaticProgress = MobileAutoProgress(viewModelScope, repository,
        { signedIn.value && writable.value && busy.value == null && importing == 0 && !savingReview.value },
        { selected.value }, { enginesMutable.value = it }, { signedInMutable.value = false; reviewerMutable.value = false })
    internal val autoProgress = automaticProgress.enabled
    internal val autoProgressPaused = automaticProgress.paused
    private val photoStorageMutable = MutableStateFlow(PhotoStorageState())
    internal val photoStorage = photoStorageMutable.asStateFlow()
    private var photoWork: Job? = null
    private fun cancelPhotoWork() { photoWork?.cancel(); photoWork = null; photoStorageMutable.value = PhotoStorageState(revision = photoStorageMutable.value.revision + 1) }
    internal fun photoPageChanged(page: MobilePage, id: String?) {
        cancelPhotoWork()
        if (page == MobilePage.Settings) refreshPhotoStorage()
        else if (page in setOf(MobilePage.Detail, MobilePage.Review, MobilePage.LedgerDetail)) id?.let(::recoverPhotos)
    }
    internal fun refreshPhotoStorage() {
        if (photoWork?.isActive == true) return
        photoWork = viewModelScope.launch {
            photoStorageMutable.value = photoStorageMutable.value.copy(busy = true, message = null)
            try {
                val local = withContext(Dispatchers.IO) { repository.localPhotoStorage() }
                photoStorageMutable.value = local.copy(busy = true)
                val central = if (signedIn.value) repository.centralPhotoStorage() else null
                photoStorageMutable.value = local.copy(central = central)
            } catch (error: Exception) {
                if (error is CancellationException && error !is kotlinx.coroutines.TimeoutCancellationException) throw error
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (error is MobileApiFailure && error.statusCode == 401 && repository.nativeToken() == null) { signedInMutable.value = false; reviewerMutable.value = false }
                photoStorageMutable.value = photoStorageMutable.value.copy(busy = false, message = "中央空間尚未更新；連線後可重試。")
            }
        }
    }
    internal fun reclaimPhotos() {
        if (photoWork?.isActive == true || !signedIn.value || !writable.value) return
        photoWork = viewModelScope.launch {
            photoStorageMutable.value = photoStorageMutable.value.copy(busy = true, message = "正在核對中央原圖…")
            try {
                val bytes = repository.reclaimPhotos()
                val local = withContext(Dispatchers.IO) { repository.localPhotoStorage() }
                photoStorageMutable.value = local.copy(central = photoStorageMutable.value.central, message = "已回收 ${photoSize(bytes)}。需要查看時會重新下載。")
            } catch (error: Exception) {
                if (error is CancellationException && error !is kotlinx.coroutines.TimeoutCancellationException) throw error
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                photoStorageMutable.value = photoStorageMutable.value.copy(busy = false, message = "回收尚未完成；未通過核對的照片保留。${mobileError(error)}")
            }
        }
    }
    internal fun recoverPhotos(id: String) {
        if (photoWork?.isActive == true) return
        val batch = repository.store.state.value?.batches?.firstOrNull { it.id == id } ?: return
        if (batch.images.none { !images.file(it.hash).isFile } || batch.intakeId == null) return
        if (!signedIn.value) { photoStorageMutable.value = photoStorageMutable.value.copy(message = "請登入並連線後取回照片；核對內容仍保留。") ; return }
        photoWork = viewModelScope.launch {
            photoStorageMutable.value = photoStorageMutable.value.copy(busy = true, message = "正在取回原圖…")
            try { repository.recoverPhotos(id); photoStorageMutable.value = photoStorageMutable.value.copy(busy = false, message = null, revision = photoStorageMutable.value.revision + 1) }
            catch (error: Exception) {
                if (error is CancellationException && error !is kotlinx.coroutines.TimeoutCancellationException) throw error
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (error is MobileApiFailure && error.statusCode == 401 && repository.nativeToken() == null) { signedInMutable.value = false; reviewerMutable.value = false }
                photoStorageMutable.value = photoStorageMutable.value.copy(busy = false, message = "照片尚未取回；請確認網路後重試。核對內容與帳目仍保留。")
            }
        }
    }
    private val acceptedShares = mutableSetOf<String>()
    private val appUpdater = MobileUpdateController(viewModelScope, repository, AndroidMobileUpdateHost(application), com.momonong.pixelreceipt.BuildConfig.VERSION_CODE) {
        signedInMutable.value = false; reviewerMutable.value = false
    }
    private var nativeScopeGeneration = 0L
    private val nativeScopeHints = InsightScopeHintStore(application.noBackupFilesDir)
    private fun cachedNativeScope(): String? = repository.nativeToken()?.let { token -> runCatching { nativeScopeHints.read(token) }.getOrNull() }
    private var verifiedNativeScope: Pair<String, String>? = null
    private fun nativeScope(): String? = verifiedNativeScope?.takeIf { it.first == repository.nativeToken() }?.second
    private fun clearNativeScope() { nativeScopeGeneration++; verifiedNativeScope = null }
    private suspend fun verifyNativeScope() {
        val expected = repository.nativeToken() ?: return
        val generation = nativeScopeGeneration
        try {
            val scope = withContext(Dispatchers.IO) { repository.nativeApi().ledgerScope(expected) }
            UUID.fromString(scope)
            if (generation == nativeScopeGeneration && repository.nativeToken() == expected) {
                verifiedNativeScope = expected to scope
                withContext(Dispatchers.IO) { runCatching { nativeScopeHints.write(expected, scope) } }
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (generation == nativeScopeGeneration && repository.nativeToken() == expected) {
                if (error is MobileApiFailure && error.statusCode == 401 && repository.nativeUnauthorized(expected)) { signedInMutable.value = false; reviewerMutable.value = false }
                messageMutable.value = "帳本身份尚未驗證；可檢視資料，連線後再新增或保存分析。"
            }
        }
    }
    private fun restoreNativePage() {
        if (destination.value == MobilePage.InsightEditor.name) saved.get<String>("mobileInsightTarget")?.let { insightController.openMetadata(it, saved.get<Boolean>("mobileInsightLedger") == true, saved.get<Boolean>("mobileInsightReadOnly") == true) }
        if (destination.value == MobilePage.Prices.name) priceController.open(saved.get<String>("mobilePriceLedger"))
    }
    internal val insightController = MobileInsightController(repository.nativeApi(), viewModelScope,
        repository::nativeToken, { expected -> if (repository.nativeUnauthorized(expected)) { signedInMutable.value = false; reviewerMutable.value = false } },
        { spendingController.invalidate(); refreshLedger() }, application.noBackupFilesDir,
        runCatching { saved.get<String>("mobileInsightQuery")?.let(::insightQueryFromJson) }.getOrNull() ?: InsightQuery.month(),
        { query -> saved["mobileInsightQuery"] = query.filters().toString() }, accountScope = ::nativeScope, cachedScope = ::cachedNativeScope)
    internal val insightState = insightController.state
    internal val priceController = MobilePriceController(repository.nativeApi(), viewModelScope,
        repository::nativeToken, { expected -> if (repository.nativeUnauthorized(expected)) { signedInMutable.value = false; reviewerMutable.value = false } },
        { insightController.invalidate(); spendingController.invalidate(); refreshLedger() }, application.noBackupFilesDir, accountScope = ::nativeScope)
    internal fun recoverInsightPending() { val pending=insightState.value.pending ?: return; if(pending.kind=="manual")manual() else pending.target?.let{editInsight(it,pending.ledger,false)} }
    internal fun insightQuery(query: InsightQuery) = insightController.select(query)
    internal fun refreshInsights() { if (signedIn.value) viewModelScope.launch { if (nativeScope() == null) verifyNativeScope(); insightController.restorePending(); insightController.refresh() } else openSettings(true) }
    internal fun submitManual(body: String) = online("正在確認帳本身份…") { if (nativeScope() == null) verifyNativeScope(); insightController.restorePending(); insightController.submitManual(body) }
    internal fun saveInsightMetadata(document: com.google.gson.JsonObject) = online("正在確認帳本身份…") { if (nativeScope() == null) verifyNativeScope(); insightController.restorePending(); insightController.saveMetadata(document) }
    internal fun moreInsights() = insightController.loadEntries(true)
    internal fun manual() { viewModelScope.launch { if (nativeScope() == null) verifyNativeScope(); insightController.restorePending() }; insightController.newManual(); saved["mobileManualReturn"] = destination.value; saved["mobileDestination"] = MobilePage.Manual.name; insightController.restorePending() }
    internal fun editInsight(id: String, ledger: Boolean, readOnly: Boolean) { if (destination.value != MobilePage.InsightEditor.name) saved["mobileInsightReturn"] = destination.value; saved["mobileInsightTarget"] = id; saved["mobileInsightLedger"] = ledger; saved["mobileInsightReadOnly"] = readOnly; saved["mobileDestination"] = MobilePage.InsightEditor.name; insightController.openMetadata(id, ledger, readOnly) }
    internal fun prices(ledgerId: String?) { saved["mobilePriceLedger"] = ledgerId; saved["mobilePriceReturnLedger"] = selectedLedger.value; saved["mobilePriceReturn"] = destination.value; saved["mobileDestination"] = MobilePage.Prices.name; viewModelScope.launch { if (nativeScope() == null) verifyNativeScope(); priceController.open(ledgerId) } }
    private val spendingSaved = MobileSpendingSavedState(saved)
    private val spendingController = MobileSpendingController(viewModelScope, object : MobileSpendingSource {
        override fun marker() = spendingMarker(repository.store.state.value ?: error("Receipt state not loaded"))
        override fun cached() = repository.cachedSpending()
        override suspend fun report(query: SpendingQuery) = repository.spendingRead(query.summaryPath())
        override suspend fun entries(query: SpendingQuery, selection: SpendingSelection, offset: Int) = repository.spendingRead(query.ledgerPath(selection, offset))
        override suspend fun saveCache(value: MobileSpendingCache, expectedMarker: String, stillCurrent: () -> Boolean) = repository.cacheSpending(value, expectedMarker, stillCurrent)
    }, spendingSaved.period, spendingSaved.query, spendingSaved.selection, spendingSaved::save) {
        signedInMutable.value = false; reviewerMutable.value = false
    }
    internal val spendingState = spendingController.state
    internal fun spending() {
        duplicateMutable.value = null; saved["mobileSettings"] = false; saved["mobileDestination"] = MobilePage.Spending.name
        if (signedIn.value) { insightController.restorePending(); insightController.refresh() }
    }
    internal fun refreshSpending() { if (signedIn.value) activateSpending(force = true) else openSettings(true) }
    private fun activateSpending(force: Boolean = false) {
        val current = spendingState.value
        if (current.period != SpendingPeriod.Custom && current.query != SpendingQuery.forPeriod(current.period)) spendingController.show(current.period)
        else if (force) spendingController.refresh() else spendingController.ensureLoaded()
    }
    internal fun spendingPeriod(period: SpendingPeriod) { if (signedIn.value) spendingController.show(period) }
    internal fun spendingRange(from: java.time.LocalDate, to: java.time.LocalDate) { if (signedIn.value) spendingController.custom(from, to) }
    internal fun selectSpending(selection: SpendingSelection) { if (signedIn.value) spendingController.select(selection) }
    internal fun moreSpending() { if (signedIn.value) spendingController.loadMore() }
    internal val appUpdate = appUpdater.state
    fun checkAppUpdate() = appUpdater.check()
    fun downloadAppUpdate() = appUpdater.download()
    fun installAppUpdate() = appUpdater.install()
    fun cancelAppUpdate() = appUpdater.cancel()

    init {
        viewModelScope.launch {
            try {
                val hasCredential = repository.initialize(); reviewerMutable.value = repository.reviewerSession(); if (hasCredential) verifyNativeScope(); insightController.restorePending()
                val initializedSignedIn = hasCredential && repository.nativeToken() != null
                if (initializedSignedIn) {
                    restoreNativePage()
                    withContext(Dispatchers.IO) { spendingController.restore() }
                    if (destination.value == MobilePage.Spending.name) insightController.refresh()
                }
                signedInMutable.value = initializedSignedIn
            }
            catch (error: Exception) { if (error is CancellationException) throw error; messageMutable.value = mobileError(error) }
        }
    }
    // Receipt edits and photo imports do not invalidate a derived financial report.
    private var observedSpendingMarker: String? = null
    init {
        viewModelScope.launch {
            repository.store.state.filterNotNull().map { spendingMarker(it) }.distinctUntilChanged().collect { marker ->
                val prior = observedSpendingMarker; observedSpendingMarker = marker
                if (prior != null && prior != marker) {
                    spendingController.invalidate(); insightController.invalidate()
                    if (signedIn.value && destination.value == MobilePage.Spending.name && !settings.value) insightController.refresh()
                }
            }
        }
    }
    fun openLegacy(open: Boolean) { duplicateMutable.value = null; legacyMutable.value = open }
    fun select(id: String?) { duplicateMutable.value = null; saved["mobileSelected"] = id; saved["mobileSettings"] = false; saved["mobileDestination"] = if (id == null) MobilePage.Inbox.name else MobilePage.Detail.name
        id?.let { current -> if (signedIn.value && repository.store.state.value?.batches?.firstOrNull { it.id == current }?.intakeId != null) refreshBatch(current) }
    }
    fun openReceipt(id: String) {
        val child = repository.store.state.value?.batches?.firstOrNull { it.id == id && it.parentBatchId == selected.value } ?: return
        duplicateMutable.value = null
        saved["mobileSelected"] = child.id
        saved["mobileSettings"] = false
        saved["mobileDestination"] = MobilePage.Detail.name
        if (!child.deleted && child.draftId != null) openAccounting(child.draftId)
        else if (signedIn.value && !child.deleted) refreshBatch(id)
    }
    fun openSettings(open: Boolean) { duplicateMutable.value = null; saved["mobileSettings"] = open }
    fun back() {
        if (destination.value == MobilePage.Prices.name) { priceController.close(); saved["mobileLedger"] = saved.get<String>("mobilePriceReturnLedger") }
        if (duplicateMutable.value != null) { duplicateMutable.value = null; return }
        if (settings.value) saved["mobileSettings"] = false
        else when (destination.value) {
            MobilePage.Manual.name -> saved["mobileDestination"] = saved.get<String>("mobileManualReturn") ?: MobilePage.Ledger.name
            MobilePage.InsightEditor.name -> saved["mobileDestination"] = saved.get<String>("mobileInsightReturn") ?: MobilePage.Ledger.name
            MobilePage.Prices.name -> saved["mobileDestination"] = saved.get<String>("mobilePriceReturn") ?: MobilePage.Spending.name
            MobilePage.Help.name -> saved["mobileDestination"] = saved.get<String>("mobileHelpReturn") ?: MobilePage.Inbox.name
            MobilePage.Review.name -> {
                val parent = repository.store.state.value?.batches?.firstOrNull { it.id == selected.value }?.parentBatchId
                if (parent != null) select(parent) else saved["mobileDestination"] = MobilePage.Detail.name
            }
            MobilePage.Detail.name -> {
                val parent = repository.store.state.value?.batches?.firstOrNull { it.id == selected.value }?.parentBatchId
                select(parent)
            }
            MobilePage.LedgerDetail.name -> saved["mobileDestination"] = saved.get<String>("mobileLedgerReturn") ?: MobilePage.Ledger.name
            else -> select(null)
        }
        if (!settings.value && destination.value == MobilePage.Spending.name && signedIn.value && insightState.value.stale) insightController.refresh()
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
        appUpdater.resumed()
        automaticProgress.resume()
        val now = System.currentTimeMillis()
        if (!signedIn.value || busy.value != null || now - refreshedAt < 30_000) return
        refreshedAt = now
        if (destination.value == MobilePage.Spending.name && !settings.value) activateSpending(force = true)
        // Receipt progress has one owner and never takes the full-screen busy state.
        if (destination.value !in setOf(MobilePage.Ledger.name, MobilePage.LedgerDetail.name)) return
        online("正在更新已保存狀態…") {
            repository.ledger(detailId = selectedLedger.value.takeIf { destination.value == MobilePage.LedgerDetail.name })
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
        automaticProgress.sessionChanged()
        cancelPhotoWork(); clearNativeScope(); appUpdater.sessionChanged(); spendingController.sessionChanged(); insightController.accountChanged(); priceController.accountChanged()
        repository.login(password)
        signedInMutable.value = true
        verifyNativeScope()
        insightController.accountChanged(); priceController.accountChanged(); restoreNativePage()
        reviewerMutable.value = repository.reviewerSession()
        messageMutable.value = "已登入，可在 App 內核對與入帳。"
        enginesMutable.value = repository.availability()
        if (destination.value == MobilePage.Spending.name) insightController.refresh()
    }
    fun logout() = online("正在登出…") {
        automaticProgress.stop()
        cancelPhotoWork(); clearNativeScope(); appUpdater.sessionChanged(); spendingController.sessionChanged(); insightController.accountChanged(); priceController.accountChanged()
        try {
            val revoked = repository.logout()
            messageMutable.value = if (revoked) "已登出；照片與待傳資料保留。" else "已清除本機登入；中央撤銷尚未確認，請在網頁的裝置管理撤銷此裝置。"
        } finally {
            insightController.accountChanged(); priceController.accountChanged()
            signedInMutable.value = false
            reviewerMutable.value = false
            enginesMutable.value = emptyMap()
        }
    }
    fun refreshEngines() = online("正在查詢辨識來源…") { enginesMutable.value = repository.availability() }
    fun sync(id: String) { automaticProgress.manualRetry(); syncWithDuplicateDecision(id, null) }
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
        catch (error: Exception) {
            if (error is CancellationException) throw error
            if (retryableSyncFailure(error) && repository.store.batch(id).syncRetryable) messageMutable.value = null
            else throw error
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
    fun updateItemNames(id: String, names: Map<Int, String?>) {
        online("正在確認補品名結果…") {
            repository.updateItemNames(id, names)
            messageMutable.value = "品名已保存；原入帳金額與用途保留。"
        }
    }
    fun stopNetwork() {
        automaticProgress.stop(); network?.cancel()
        commands.launch {
            try { repository.pauseAutomaticSync() }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                messageMutable.value = "本次自動同步已停止，但停止設定尚未保存；請先恢復本機儲存空間。"
            }
        }
    }
    fun background() { automaticProgress.background(); refreshedAt = 0; network?.cancel() }
}
