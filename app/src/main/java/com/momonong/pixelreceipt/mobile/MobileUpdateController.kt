package com.momonong.pixelreceipt.mobile

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.concurrent.atomic.AtomicLong

interface MobileUpdateSource {
    suspend fun appRelease(): AppRelease
    suspend fun downloadAppUpdate(release: AppRelease, file: File, progress: (Long) -> Unit)
}
internal enum class AppUpdatePhase { Idle, Checking, Available, Current, Downloading, Verifying, Ready, Failed }
internal data class AppUpdateState(val phase: AppUpdatePhase = AppUpdatePhase.Idle, val release: AppRelease? = null, val downloaded: Long = 0, val canInstall: Boolean = false, val message: String? = null)

/** User-initiated update work is independent of receipt writes and never marks installation successful. */
internal class MobileUpdateController(
    private val scope: CoroutineScope, private val source: MobileUpdateSource, private val host: MobileUpdateHost,
    private val currentVersion: Int, private val authenticationRequired: () -> Unit = {},
) {
    private val mutable=MutableStateFlow(AppUpdateState())
    val state=mutable.asStateFlow()
    private val generation=AtomicLong()
    private var job:Job?=null
    private fun start(action: suspend (Long) -> Unit) {
        if (job?.isActive == true) return
        val attempt=generation.incrementAndGet()
        job=scope.launch {
            try { action(attempt) }
            catch (error: Exception) {
                if (error is CancellationException && error !is TimeoutCancellationException) throw error
                if (generation.get() == attempt) {
                    if (error is MobileApiFailure && error.statusCode == 401) authenticationRequired()
                    mutable.update { it.copy(phase=AppUpdatePhase.Failed, message=when {
                        error is TimeoutCancellationException -> "連線逾時，請重新檢查更新。原本的 App 與收據資料保留。"
                        error is MobileApiFailure && error.statusCode == 404 -> "中央尚未提供更新檔，請稍後再試。"
                        error is MobileApiFailure && error.statusCode == 401 -> "請先重新登入，再檢查 App 更新。"
                        else -> "更新未完成，請重新檢查。原本的 App 與收據資料保留。"
                    }) }
                }
            }
        }
    }
    fun check() = start {
        mutable.value=AppUpdateState(AppUpdatePhase.Checking)
        val release=source.appRelease()
        if (release.versionCode <= currentVersion) {
            mutable.value=AppUpdateState(AppUpdatePhase.Current,release,message=if(release.versionCode < currentVersion) "目前使用的版本較新，無需降版。" else "已是最新版本。")
        } else {
            val cached=withContext(Dispatchers.IO) { host.file(release).isFile && runCatching { host.verify(release) }.isSuccess }
            mutable.value=AppUpdateState(if(cached)AppUpdatePhase.Ready else AppUpdatePhase.Available,release,canInstall=host.canInstall())
        }
    }
    fun download() {
        val release=state.value.release ?: return
        if (state.value.phase !in setOf(AppUpdatePhase.Available,AppUpdatePhase.Failed)) return
        start { attempt ->
            mutable.value=AppUpdateState(AppUpdatePhase.Downloading,release)
            source.downloadAppUpdate(release,host.file(release)) { bytes ->
                mutable.update { current -> if(generation.get()==attempt && current.phase==AppUpdatePhase.Downloading) current.copy(downloaded=bytes.coerceIn(0,release.sizeBytes)) else current }
            }
            mutable.update { it.copy(phase=AppUpdatePhase.Verifying) }
            withContext(Dispatchers.IO) { host.verify(release) }
            mutable.value=AppUpdateState(AppUpdatePhase.Ready,release,release.sizeBytes,host.canInstall(),"下載完成，可安裝更新。")
        }
    }
    fun install() {
        val release=state.value.release ?: return
        if (state.value.phase != AppUpdatePhase.Ready) return
        start {
            if (!host.canInstall()) {
                host.permission()
                mutable.update { it.copy(canInstall=false,message="請允許 PixelReceipt 安裝更新，再返回 App 繼續。") }
            } else {
                mutable.update { it.copy(phase=AppUpdatePhase.Verifying) }
                withContext(Dispatchers.IO) { host.verify(release) }
                host.install(release)
                mutable.update { it.copy(phase=AppUpdatePhase.Ready,canInstall=true,message="已開啟系統安裝畫面；請在 Android 確認更新。取消後可再按安裝。") }
            }
        }
    }
    fun sessionChanged() {
        generation.incrementAndGet();job?.cancel();mutable.value=AppUpdateState()
    }
    fun resumed() { if (state.value.phase==AppUpdatePhase.Ready) mutable.update { it.copy(canInstall=host.canInstall()) } }
    fun cancel() {
        if (state.value.phase !in setOf(AppUpdatePhase.Downloading,AppUpdatePhase.Checking,AppUpdatePhase.Verifying)) return
        generation.incrementAndGet();job?.cancel()
        mutable.update { it.copy(phase=if(it.release==null)AppUpdatePhase.Idle else AppUpdatePhase.Available,downloaded=0,message="已取消更新，原本的 App 與收據資料保留。") }
    }
    internal suspend fun awaitIdle() { job?.join() }
}
