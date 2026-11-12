package com.momonong.pixelreceipt.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.momonong.pixelreceipt.BuildConfig
import java.util.Locale

private fun updateSize(bytes: Long) = String.format(Locale.ROOT,"%.1f MB",bytes.toDouble()/(1024*1024))

@Composable
internal fun MobileUpdateContent(state: MobileUiState, actions: MobileActions) {
    val update=state.appUpdate
    val phase=update.phase
    val release=update.release
    Text("目前版本 ${BuildConfig.VERSION_NAME}", style=MaterialTheme.typography.titleSmall)
    if (release != null && phase != AppUpdatePhase.Current) Text("可更新至 ${release.versionName} · ${updateSize(release.sizeBytes)}",style=MaterialTheme.typography.bodyMedium,color=MobileTealInk)
    Text("在 App 下載，交由 Android 確認安裝；收據與登入資料保留。",style=MaterialTheme.typography.bodyMedium,color=MobileMuted)
    if (phase==AppUpdatePhase.Downloading && release!=null) {
        val fraction=(update.downloaded.toDouble()/release.sizeBytes).coerceIn(0.0,1.0)
        LinearProgressIndicator(progress={fraction.toFloat()},modifier=Modifier.fillMaxWidth().testTag("app-update-progress"))
        Text("${updateSize(update.downloaded)} / ${updateSize(release.sizeBytes)} · ${(fraction*100).toInt()}%",style=MaterialTheme.typography.bodySmall,color=MobileMuted)
    }
    update.message?.let { Text(it,style=MaterialTheme.typography.bodyMedium,color=if(phase==AppUpdatePhase.Failed)MaterialTheme.colorScheme.error else MobileMuted) }
    if (phase==AppUpdatePhase.Ready && !update.canInstall) Text("首次更新請先允許 PixelReceipt 安裝應用程式，返回後即可繼續。",style=MaterialTheme.typography.bodySmall,color=MobileMuted)
    val active=phase in setOf(AppUpdatePhase.Checking,AppUpdatePhase.Downloading,AppUpdatePhase.Verifying)
    val label=when(phase) {
        AppUpdatePhase.Checking->"正在檢查更新…"
        AppUpdatePhase.Available->"下載更新"
        AppUpdatePhase.Downloading->"正在下載…"
        AppUpdatePhase.Verifying->"正在驗證更新檔…"
        AppUpdatePhase.Ready->if(update.canInstall)"安裝更新" else "允許安裝更新"
        AppUpdatePhase.Failed->"重新檢查更新"
        else->"檢查更新"
    }
    Button(onClick={when(phase) {
        AppUpdatePhase.Available->actions.downloadAppUpdate()
        AppUpdatePhase.Ready->actions.installAppUpdate()
        else->actions.checkAppUpdate()
    }},enabled=!active && (state.signedIn || phase==AppUpdatePhase.Ready),modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("app-update-action")) {Text(label)}
    if (!state.signedIn && phase!=AppUpdatePhase.Ready) Text("請先登入中央帳本，再檢查更新。",style=MaterialTheme.typography.bodySmall,color=MobileMuted)
    if (phase in setOf(AppUpdatePhase.Downloading,AppUpdatePhase.Checking)) TextButton(onClick=actions.cancelAppUpdate,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("app-update-cancel")) {Text("取消更新")}
}
