package com.momonong.pixelreceipt.mobile

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowSigningInfo
import org.robolectric.util.ReflectionHelpers
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class MobileUpdateCleanupAndroidTest {
    private lateinit var app: Application
    private lateinit var apk: File
    private lateinit var receipt: File

    @Before fun setup() {
        app = ApplicationProvider.getApplicationContext()
        apk = File(app.cacheDir, "app-updates/update.apk").apply { parentFile!!.mkdirs(); writeText("synthetic cache APK") }
        receipt = File(app.noBackupFilesDir, "mobile-intake/cleanup-sentinel").apply { parentFile!!.mkdirs(); writeText("original synthetic receipt") }
    }
    @After fun cleanup() { apk.delete(); receipt.delete() }

    @Suppress("DEPRECATION")
    private fun info(code: Int, name: String, signer: String = "001122"): PackageInfo {
        val signing = ReflectionHelpers.callConstructor(SigningInfo::class.java)
        Shadow.extract<ShadowSigningInfo>(signing).setSignatures(arrayOf(Signature(signer)))
        return PackageInfo().apply {
            packageName = app.packageName
            applicationInfo = ApplicationInfo(app.applicationInfo)
            versionCode = code
            versionName = name
            signingInfo = signing
        }
    }

    @Test fun androidHostChecksInstalledPackageBeforeDeletingOnlyTheApk() {
        val pm = shadowOf(app.packageManager)
        pm.installPackage(info(16, "0.7.2"))
        pm.setPackageArchiveInfo(apk.absolutePath, info(16, "0.7.2"))
        assertEquals(UpdateCacheCleanupResult.Removed, AndroidMobileUpdateHost(app).cleanupInstalledUpdate())
        assertFalse(apk.exists())
        assertEquals("original synthetic receipt", receipt.readText())
    }

    @Test fun androidHostKeepsAnUninstalledOrDifferentlySignedArchive() {
        val pm = shadowOf(app.packageManager)
        pm.installPackage(info(15, "0.7.1"))
        pm.setPackageArchiveInfo(apk.absolutePath, info(16, "0.7.2"))
        assertEquals(UpdateCacheCleanupResult.Unchanged, AndroidMobileUpdateHost(app).cleanupInstalledUpdate())
        assertTrue(apk.isFile)
        pm.installPackage(info(16, "0.7.2"))
        pm.setPackageArchiveInfo(apk.absolutePath, info(16, "0.7.2", "334455"))
        assertEquals(UpdateCacheCleanupResult.Unchanged, AndroidMobileUpdateHost(app).cleanupInstalledUpdate())
        assertTrue(apk.isFile)
    }

    @Suppress("DEPRECATION")
    @Test fun installedUpdateNotificationIsRegisteredOnAPrivateReceiver() {
        val component = ComponentName(app, UpdateInstalledReceiver::class.java)
        val receiver = app.packageManager.getReceiverInfo(component, 0)
        assertFalse(receiver.exported)
        val own = Intent(Intent.ACTION_MY_PACKAGE_REPLACED).setPackage(app.packageName)
        val other = Intent(Intent.ACTION_PACKAGE_REPLACED).setPackage(app.packageName)
        assertTrue(app.packageManager.queryBroadcastReceivers(own, 0).any { it.activityInfo.name == component.className })
        assertFalse(app.packageManager.queryBroadcastReceivers(other, 0).any { it.activityInfo.name == component.className })
    }
}
