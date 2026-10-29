package com.momonong.pixelreceipt.mobile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.momonong.pixelreceipt.data.ingestion.ImageStore
import com.momonong.pixelreceipt.feature.inbox.EvidencePhoto
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Production UI; fixtures replace state, original images, actions and keyboard insets. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MobileContent(state: MobileUiState, images: ImageStore, actions: MobileActions, imeInsets: WindowInsets = WindowInsets.ime) {
    val colors = lightColorScheme(
        primary = MobileTeal, onPrimary = Color.White, primaryContainer = MobileTealSoft, onPrimaryContainer = MobileTealInk,
        surface = Color.White, background = MobileGround, onBackground = MobileInk, onSurface = MobileInk,
        secondary = MobileTealInk, onSecondary = Color.White, secondaryContainer = MobileTealSoft, onSecondaryContainer = MobileTealInk,
        tertiary = MobileTeal, surfaceTint = MobileTeal, surfaceVariant = MobileGround, onSurfaceVariant = MobileMuted,
        outline = Color(0xFF7C918B), outlineVariant = MobileRule, error = Color(0xFF963C36),
    )
    val inboxScroll = rememberLazyListState()
    val detailScroll = rememberLazyListState()
    val reviewScroll = rememberLazyListState()
    val ledgerScroll = rememberLazyListState()
    val ledgerDetailScroll = rememberLazyListState()
    val helpScroll = rememberLazyListState()
    LaunchedEffect(state.selectedId) { detailScroll.scrollToItem(0) }
    var password by remember { mutableStateOf("") }
    DisposableEffect(state.page) { onDispose { password = "" } }
    LaunchedEffect(state.signedIn) { password = "" }
    BackHandler(state.duplicateShare == null && state.page != MobilePage.Inbox) { actions.back() }
    MaterialTheme(colorScheme = colors, typography = MobileTypography) {
        state.duplicateShare?.let { DuplicateShareDialog(it, state, actions) }
        // Reserve the keyboard once for the whole viewport, rather than measuring it as part of the action bar.
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(imeInsets)) {
            val compactInput = imeInsets.getBottom(LocalDensity.current) > 0 && maxHeight < 360.dp
            Scaffold(containerColor = MobileGround,
                topBar = {
                    if (!compactInput) {
                        TopAppBar(title = {
                            if (state.page == MobilePage.Inbox) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ReceiptMark()
                                Text("PixelReceipt", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            } else Text(when (state.page) {
                                MobilePage.Settings -> "設定"
                                MobilePage.Review -> "核對收據"
                                MobilePage.Ledger -> "帳本"
                                MobilePage.LedgerDetail -> "帳目明細"
                                MobilePage.Help -> "使用說明"
                                else -> if (state.batch?.editable == true) "照片與說明" else "收件進度"
                            },
                                style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }, navigationIcon = {
                            if (state.page != MobilePage.Inbox) TextButton(onClick = actions.back, modifier = Modifier.heightIn(min = 48.dp).testTag("mobile-back")) { Text("‹ 返回") }
                        }, actions = {
                            if (state.page != MobilePage.Help) TextButton(onClick = actions.help, modifier = Modifier.heightIn(min = 48.dp).testTag("mobile-help")) { Text("說明") }
                            if (state.page != MobilePage.Settings) TextButton(onClick = actions.settings, modifier = Modifier.heightIn(min = 48.dp).testTag("mobile-settings")) { Text("設定") }
                        }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MobileGround))
                    }
                },
                bottomBar = { MobilePrimaryBar(state, password, { input -> password = ""; actions.login(input) }, actions, compactInput) },
            ) { padding ->
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
                    // At 390: 20 + 350 + 20. At 840: 20 + 800 + 20; a shared edge for each screen.
                    val width = Modifier.widthIn(max = 840.dp).fillMaxSize()
                    when (state.page) {
                        MobilePage.Inbox -> MobileInbox(state, images, actions, width, inboxScroll)
                        MobilePage.Detail -> MobileDetail(state, images, actions, width, detailScroll)
                        MobilePage.Settings -> MobileSettings(state, password, { password = it }, actions, width)
                        MobilePage.Review -> MobileReviewScreen(state, images, actions, width, reviewScroll)
                        MobilePage.Ledger -> MobileLedger(state, actions, width, ledgerScroll)
                        MobilePage.LedgerDetail -> MobileLedgerDetail(state, images, actions, width, ledgerDetailScroll)
                        MobilePage.Help -> MobileHelp(width, helpScroll)
                    }
                }
            }
        }
    }
}

@Composable
private fun MobilePrimaryBar(state: MobileUiState, password: String, login: (String) -> Unit, actions: MobileActions, compactInput: Boolean) {
    if (state.page in setOf(MobilePage.Review, MobilePage.Ledger, MobilePage.LedgerDetail, MobilePage.Help)) {
        NativePrimaryBar(state, actions, compactInput)
        return
    }
    val batch = state.batch
    val edit = batch?.let { state.edits[it.id] }
    val pending = state.mutations.lastOrNull { it.batchId == batch?.id && it.phase == "pending" }?.takeIf { state.page == MobilePage.Detail }
    // Completing an import is a picker action, so submission validation must not trap the user.
    val hint = batch?.takeIf { state.page == MobilePage.Detail && it.editable && it.importComplete }?.let(::validationHint)
    val localUnavailable = batch?.engine == "local" && batch.intakeId == null && state.engines["local"]?.available != true
    val label = when {
        state.busy != null -> state.busy
        !state.writable -> "重試本機儲存"
        state.page == MobilePage.Detail && batch?.deleted == true -> "返回收件匣"
        pending != null -> "查詢${pending.resultLabel}結果"
        edit != null -> if (edit.error != null) "重試儲存目前編輯" else "正在保存編輯…"
        state.page == MobilePage.Settings -> if (state.signedIn && state.reviewer) "返回收件" else "登入此裝置"
        state.page == MobilePage.Inbox || batch == null -> "新增收件・選照片"
        !batch.importComplete -> "補齊照片"
        batch.ledgerId != null -> if (batch.ledgerStatus == "voided") "查看作廢紀錄" else "查看已完成帳目"
        batch.intakeId != null && batch.status == "succeeded" -> if (batch.draftId != null) "前往帳本核對" else "開啟中央帳本"
        !state.signedIn -> if (batch.intakeId != null) "登入後更新進度" else "登入後送出"
        batch.intakeId != null -> "更新進度"
        localUnavailable -> "至設定確認辨識來源"
        batch.frozen != null -> "繼續同步這筆收件"
        else -> "送出 ${batch.images.size} 張照片"
    }
    val enabled = (state.ready || !state.writable) && state.busy == null && (hint == null || !state.writable) &&
        (edit == null || edit.error != null) &&
        (!state.writable || state.page != MobilePage.Settings || (state.signedIn && state.reviewer) || password.length in 1..1024)
    Surface(color = Color.White) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            HorizontalDivider(color = MobileRule)
            Column(Modifier.widthIn(max = 840.dp).fillMaxWidth().align(Alignment.CenterHorizontally).padding(horizontal = 20.dp, vertical = if (compactInput) 4.dp else 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val caption = when {
                    !state.writable -> "現有收件可檢視；保存恢復後才可新增、修改或送出。"
                    edit != null -> edit.error ?: "正在保存目前編輯，完成前不會送出。"
                    hint != null -> hint
                    state.page == MobilePage.Settings -> if (state.signedIn && state.reviewer) "可在 App 內核對、入帳與查看帳本。" else "使用既有帳本密碼，送出後清空輸入。"
                    state.page == MobilePage.Inbox -> "一批照片，一筆收件；由你核對後入帳。"
                    batch?.deleted == true -> "收據已刪除，原圖與歷史資料保留。"
                    pending != null -> "${pending.resultLabel}結果尚未確認；將查詢原 ID，沿用原請求。"
                    batch?.importComplete == false -> "請重新選取完整批次，保留已匯入原圖。"
                    batch?.ledgerId != null -> if (batch.ledgerStatus == "voided") "中央已作廢，原入帳內容保留。" else "中央已確認入帳，可查看品項與用途。"
                    batch?.intakeId != null && batch.status == "succeeded" -> "在 App 內核對金額與用途，再確認入帳。"
                    batch?.frozen != null -> "中央已確認原圖 ${batch.uploadedHashes.size} / ${batch.images.size} 張"
                    else -> "送出原圖與說明；送出後鎖定本批內容。"
                }
                if (!compactInput) Text(caption, style = MaterialTheme.typography.bodySmall, color = if (hint != null) MaterialTheme.colorScheme.error else MobileMuted)
                Button(onClick = {
                    when {
                        !state.writable || edit != null -> actions.retrySave(batch?.id)
                        state.page == MobilePage.Detail && batch?.deleted == true -> actions.select(null)
                        pending != null -> actions.retryReceiptMutation(pending)
                        state.page == MobilePage.Settings -> if (state.signedIn && state.reviewer) actions.back() else login(password)
                        state.page == MobilePage.Inbox || batch == null -> actions.pick(null)
                        !batch.importComplete -> actions.pick(batch.id)
                        batch.ledgerId != null -> actions.ledgerDetail(batch.ledgerId)
                        batch.intakeId != null && batch.status == "succeeded" -> actions.dashboard(batch.draftId)
                        !state.signedIn || localUnavailable -> actions.settings()
                        else -> actions.sync(batch.id)
                    }
                }, enabled = enabled, shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(disabledContainerColor = Color(0xFFE4EBE8), disabledContentColor = MobileMuted),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("mobile-primary")) {
                    Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                }
            }
            if (state.page == MobilePage.Inbox) MobileNavigation(state, actions)
        }
    }
}

@Composable
private fun MobileInbox(state: MobileUiState, images: ImageStore, actions: MobileActions, modifier: Modifier, list: LazyListState) {
    LazyColumn(modifier.testTag("mobile-inbox"), state = list, contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp, top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(Modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("收件匣", style = MaterialTheme.typography.headlineMedium)
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("中央收件匣 · ${state.batches.count { !it.deleted }} 筆收件", style = MaterialTheme.typography.bodyMedium, color = MobileMuted)
                    Text(if (state.signedIn) "裝置已登入" else "尚未登入", style = MaterialTheme.typography.bodySmall, color = MobileMuted)
                }
            }
        }
        state.message?.let { item { MobileNotice(it) } }
        state.accountingError?.let { item { MobileNotice(it) } }
        if (!state.ready) item {
            Surface(color = Color.White, shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("正在讀取收件…", style = MaterialTheme.typography.titleMedium)
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = MobileTeal, trackColor = MobileTealSoft)
                }
            }
        }
        if (state.ready && state.batches.none { !it.deleted }) item {
            Surface(color = Color.White, shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    ReceiptMark(Modifier.size(44.dp).padding(bottom = 2.dp))
                    Text("把這筆消費帶回帳本", style = MaterialTheme.typography.headlineSmall)
                    Text("收據、價標與促銷，放在同一批。補上代買等說明，讓核對時有原圖可循。", color = MobileMuted, style = MaterialTheme.typography.bodyLarge)
                    HorizontalDivider(color = MobileRule)
                    Text("選照片 → 補說明 → 送出辨識", style = MaterialTheme.typography.labelLarge, color = MobileTealInk)
                    Text("也能從相簿分享過來。完成辨識後，直接在 App 內核對並確認入帳。", style = MaterialTheme.typography.bodyMedium, color = MobileMuted)
                }
            }
        }
        items(state.batches.filter { !it.deleted }, key = { it.id }) { batch ->
            Surface(shape = RoundedCornerShape(20.dp), color = Color.White, modifier = Modifier.fillMaxWidth()
                .clickable { actions.select(batch.id) }.testTag("mobile-batch-${batch.id}")) {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    ReceiptThumb(batch.images.firstOrNull(), images, Modifier.width(76.dp).height(106.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        StatusTag(state.mutations.lastOrNull { it.batchId == batch.id && it.phase == "pending" }?.let { "${it.resultLabel}結果待確認" } ?: mobileStatus(batch), problem = batch.status in setOf("failed", "unknown", "unavailable") || !batch.importComplete)
                        Text(batchTitle(batch), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("${batch.images.size} 張照片 · ${SimpleDateFormat("M/d HH:mm", Locale.TAIWAN).format(Date(batch.createdAt))}", style = MaterialTheme.typography.bodySmall, color = MobileMuted)
                        Text(mobileNextStep(batch), style = MaterialTheme.typography.labelLarge, color = MobileTealInk)
                    }
                    ReceiptActionMenu(batch, null, state, actions)
                }
            }
        }
    }
}

@Composable
internal fun ReceiptThumb(image: MobileImage?, images: ImageStore, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xFFEEF2F0)), contentAlignment = Alignment.Center) {
        if (image != null) EvidencePhoto(image.hash, images, Modifier.fillMaxSize())
        else ReceiptMark(Modifier.size(30.dp), MobileMuted)
    }
}
@Composable internal fun StatusTag(text: String, problem: Boolean = false) {
    Surface(color = if (problem) MobileWarningSoft else MobileTealSoft, shape = RoundedCornerShape(6.dp)) {
        Text(text, Modifier.padding(horizontal = 9.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium,
            color = if (problem) MobileWarning else MobileTealInk)
    }
}
@Composable internal fun MobileNotice(text: String) {
    Surface(color = Color.White, shape = RoundedCornerShape(20.dp)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.width(3.dp).height(24.dp).background(MobileWarning, RoundedCornerShape(2.dp)))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MobileInk)
        }
    }
}
