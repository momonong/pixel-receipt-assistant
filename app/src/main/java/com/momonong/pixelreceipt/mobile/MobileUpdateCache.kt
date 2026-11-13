package com.momonong.pixelreceipt.mobile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CancellationException

internal enum class UpdateCacheCleanupResult { Unchanged, Removed, Deferred }

/** Shared with the downloader's final rename; a newer APK must not replace one being inspected. */
internal object UpdateApkFiles { val lock = Any() }

internal fun cleanupInstalledUpdateApk(
    cacheDir: File,
    installed: UpdateApkIdentity,
    readArchive: (File) -> UpdateApkIdentity?,
): UpdateCacheCleanupResult = synchronized(UpdateApkFiles.lock) {
    try {
        val directory = File(cacheDir, "app-updates")
        val apk = File(directory, "update.apk")
        if (!directory.isDirectory || !apk.isFile || Files.isSymbolicLink(directory.toPath()) || Files.isSymbolicLink(apk.toPath()) ||
            directory.canonicalFile.parentFile != cacheDir.canonicalFile || apk.canonicalFile.parentFile != directory.canonicalFile ||
            installed.packageName != UPDATE_PACKAGE || installed.versionCode <= 0 || installed.signers.isEmpty()) {
            return@synchronized UpdateCacheCleanupResult.Unchanged
        }
        val archive = readArchive(apk) ?: return@synchronized UpdateCacheCleanupResult.Unchanged
        if (archive.packageName != installed.packageName || archive.signers != installed.signers || archive.versionCode <= 0 ||
            archive.versionCode > installed.versionCode ||
            (archive.versionCode == installed.versionCode && archive.versionName != installed.versionName)) {
            return@synchronized UpdateCacheCleanupResult.Unchanged
        }
        if (apk.delete()) UpdateCacheCleanupResult.Removed else UpdateCacheCleanupResult.Deferred
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        UpdateCacheCleanupResult.Deferred
    }
}

/** Only Android's own-package replacement notification can reach this unexported receiver. */
class UpdateInstalledReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync() ?: return
        val application = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try { AndroidMobileUpdateHost(application).cleanupInstalledUpdate() }
            finally { pending.finish() }
        }
    }
}
