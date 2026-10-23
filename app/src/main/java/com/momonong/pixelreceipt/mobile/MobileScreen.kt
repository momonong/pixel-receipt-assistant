package com.momonong.pixelreceipt.mobile

import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.momonong.pixelreceipt.data.ingestion.ImageStore

internal enum class MobilePage { Inbox, Detail, Settings, Review, Ledger, LedgerDetail, Help }
internal data class MobileUiState(
    val ready: Boolean = true,
    val batches: List<MobileBatch> = emptyList(),
    val selectedId: String? = null,
    val page: MobilePage = MobilePage.Inbox,
    val signedIn: Boolean = false,
    val busy: String? = null,
    val message: String? = null,
    val engines: Map<String, EngineAvailability> = emptyMap(),
    val writable: Boolean = true,
    val edits: Map<String, MobileEdit> = emptyMap(),
    val reviewer: Boolean = true,
    val ledgerJson: String? = null,
    val ledgerUpdatedAt: Long? = null,
    val selectedLedgerId: String? = null,
    val savingReview: Boolean = false,
    val pendingReviewEdits: Set<String> = emptySet(),
    val ledgerDetailsJson: String? = null,
) {
    val batch: MobileBatch? get() = batches.firstOrNull { it.id == selectedId }
}
internal class MobileActions(
    val select: (String?) -> Unit = {},
    val settings: () -> Unit = {},
    val back: () -> Unit = {},
    val pick: (String?) -> Unit = {},
    val edit: (String, (MobileBatch) -> MobileBatch) -> Unit = { _, _ -> },
    val login: (String) -> Unit = {},
    val logout: () -> Unit = {},
    val refresh: () -> Unit = {},
    val sync: (String) -> Unit = {},
    val retrySave: (String?) -> Unit = {},
    val confirmImport: (String) -> Unit = {},
    val stop: () -> Unit = {},
    val legacy: () -> Unit = {},
    val dashboard: (String?) -> Unit = {},
    val help: () -> Unit = {},
    val ledger: () -> Unit = {},
    val ledgerDetail: (String) -> Unit = {},
    val refreshLedger: (Boolean) -> Unit = {},
    val editReview: (String, (com.google.gson.JsonObject) -> Unit) -> Unit = { _, _ -> },
    val saveReview: (String) -> Unit = {},
    val confirmReview: (String) -> Unit = {},
    val compareReview: (String) -> Unit = {},
    val resolveReview: (String, Boolean) -> Unit = { _, _ -> },
)

@Composable
fun MobileScreen(viewModel: MobileViewModel) {
    MobileSystemBars()
    val persisted by viewModel.state.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val signedIn by viewModel.signedIn.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val engines by viewModel.engines.collectAsStateWithLifecycle()
    val writable by viewModel.writable.collectAsStateWithLifecycle()
    val edits by viewModel.editState.collectAsStateWithLifecycle()
    val reviewer by viewModel.reviewer.collectAsStateWithLifecycle()
    val destination by viewModel.destination.collectAsStateWithLifecycle()
    val selectedLedger by viewModel.selectedLedger.collectAsStateWithLifecycle()
    val savingReview by viewModel.savingReview.collectAsStateWithLifecycle()
    val reviewEdits by viewModel.reviewEditState.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, viewModel) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) viewModel.foreground() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(signedIn) { if (signedIn) viewModel.foreground() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(20), viewModel::picked)
    val state = MobileUiState(persisted != null, persisted?.batches.orEmpty(), selected,
        if (settings) MobilePage.Settings else MobilePage.valueOf(destination),
        signedIn, busy, message, engines, writable, edits, reviewer, persisted?.ledgerJson, persisted?.ledgerUpdatedAt, selectedLedger, savingReview, reviewEdits.keys, persisted?.ledgerDetailsJson)
    val actions = MobileActions(
        select = viewModel::select, settings = { viewModel.openSettings(true) }, back = viewModel::back,
        pick = { id -> viewModel.pickerTarget(id); picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        edit = viewModel::edit, login = viewModel::login, logout = viewModel::logout, refresh = viewModel::refreshEngines,
        sync = viewModel::sync, retrySave = viewModel::retrySave, confirmImport = viewModel::confirmImport, stop = viewModel::stopNetwork, legacy = { viewModel.openLegacy(true) },
        dashboard = viewModel::openAccounting, help = viewModel::help, ledger = viewModel::ledger,
        ledgerDetail = viewModel::openLedger, refreshLedger = viewModel::refreshLedger,
        editReview = viewModel::editReview, saveReview = viewModel::saveReview, confirmReview = viewModel::confirmReview,
        compareReview = viewModel::compareReview, resolveReview = viewModel::resolveReview,
    )
    MobileContent(state, viewModel.images, actions)
}

@Composable
internal fun MobileSystemBars() {
    val window = LocalActivity.current?.window
    val view = LocalView.current
    DisposableEffect(window, view) {
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val originalStatus = controller?.isAppearanceLightStatusBars ?: false
        val originalNavigation = controller?.isAppearanceLightNavigationBars ?: false
        controller?.isAppearanceLightStatusBars = true
        controller?.isAppearanceLightNavigationBars = true
        onDispose {
            controller?.isAppearanceLightStatusBars = originalStatus
            controller?.isAppearanceLightNavigationBars = originalNavigation
        }
    }
}

internal fun batchTitle(batch: MobileBatch): String = batch.userNotes.lineSequence().firstOrNull { it.isNotBlank() }?.take(50)
    ?: if (batch.purpose == "reference") "${batch.images.size} 張參考照片" else "${batch.images.size} 張消費照片"
internal fun batchStatus(batch: MobileBatch): String = when {
    !batch.importComplete -> "照片未完整匯入"
    batch.ledgerStatus == "voided" || batch.draftStatus == "voided" -> "已作廢"
    batch.ledgerStatus == "active" || batch.draftStatus == "confirmed" -> "已完成"
    batch.status != null -> statusLabel(batch.status)
    batch.frozen != null -> "等待同步"
    else -> "尚未送出"
}
internal fun validationHint(batch: MobileBatch): String? = when {
    batch.userNotes.length > 4000 -> "整批說明最多 4,000 字，請縮短後送出。"
    batch.images.any { it.notes.length > 1000 } -> "每張照片的說明最多 1,000 字，請縮短後送出。"
    batch.purpose == "purchase" && batch.images.none { it.role == "receipt" } -> "消費需要至少一張收據；只有菜單或促銷請選參考資料。"
    else -> null
}
internal fun availabilityLabel(value: EngineAvailability?): String = when {
    value == null -> "尚未查詢"
    value.available -> "可使用"
    value.reason == "recognition_not_configured" -> "中央尚未啟用"
    else -> "暫時無法使用"
}
