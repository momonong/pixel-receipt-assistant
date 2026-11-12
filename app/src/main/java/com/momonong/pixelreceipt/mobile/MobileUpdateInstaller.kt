package com.momonong.pixelreceipt.mobile

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import androidx.core.net.toUri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.security.MessageDigest

class AppUpdateFileProvider : FileProvider()

internal data class UpdateApkIdentity(val packageName: String, val versionCode: Long, val versionName: String, val signers: Set<String>)
internal fun verifyUpdateIdentity(release: AppRelease, installed: UpdateApkIdentity, apk: UpdateApkIdentity) {
    require(installed.packageName == UPDATE_PACKAGE && apk.packageName == UPDATE_PACKAGE)
    require(apk.versionCode == release.versionCode && apk.versionName == release.versionName && apk.versionCode > installed.versionCode)
    require(installed.signers == setOf(release.signerSha256) && apk.signers == installed.signers)
}

internal fun updateInstallIntent(context: Context, file: File): Intent = Intent(Intent.ACTION_VIEW).apply {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
    setDataAndType(uri, "application/vnd.android.package-archive")
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
}

internal interface MobileUpdateHost {
    fun file(release: AppRelease): File
    fun canInstall(): Boolean
    fun verify(release: AppRelease)
    fun permission()
    fun install(release: AppRelease)
}

internal class AndroidMobileUpdateHost(private val context: Context) : MobileUpdateHost {
    override fun file(release: AppRelease) = File(context.cacheDir, "app-updates/update.apk")
    override fun canInstall() = context.packageManager.canRequestPackageInstalls()
    @Suppress("DEPRECATION")
    private fun identity(info: PackageInfo): UpdateApkIdentity {
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        return UpdateApkIdentity(info.packageName, PackageInfoCompat.getLongVersionCode(info), info.versionName.orEmpty(), signatures.orEmpty().map { sha256(it.toByteArray()) }.toSet())
    }
    @Suppress("DEPRECATION")
    override fun verify(release: AppRelease) {
        val apk = file(release)
        require(apk.isFile && apk.length() == release.sizeBytes) { "更新檔已遺失，請重新下載。" }
        val digest = MessageDigest.getInstance("SHA-256")
        apk.inputStream().use { input -> val buffer=ByteArray(64*1024);while(true) {val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n)} }
        require(digest.digest().joinToString("") { "%02x".format(it) } == release.sha256) { "更新檔驗證失敗，請重新下載。" }
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val pm=context.packageManager
        val installed=identity(pm.getPackageInfo(context.packageName, flags))
        val archive=pm.getPackageArchiveInfo(apk.absolutePath, flags) ?: throw IllegalArgumentException("更新檔無法安裝。")
        verifyUpdateIdentity(release,installed,identity(archive))
    }
    override fun permission() {
        context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    override fun install(release: AppRelease) { context.startActivity(updateInstallIntent(context,file(release))) }
}
