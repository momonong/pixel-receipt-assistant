package com.momonong.pixelreceipt.mobile

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28], application=Application::class)
class MobileUpdateFlowTest {
    private val release=AppRelease(100,"1.0.0",3,"a".repeat(64),"b".repeat(64))
    @Test fun installerOnlyAcceptsNewerSameAppSameSignerAndMatchingMetadata() {
        val installed=UpdateApkIdentity(UPDATE_PACKAGE,12,"0.5.5",setOf(release.signerSha256))
        val apk=installed.copy(versionCode=100,versionName="1.0.0")
        verifyUpdateIdentity(release,installed,apk)
        for (bad in listOf(apk.copy(packageName="other.app"),apk.copy(versionCode=101),apk.copy(versionName="wrong"),apk.copy(signers=setOf("c".repeat(64))))) {
            try { verifyUpdateIdentity(release,installed,bad); fail() } catch (_: IllegalArgumentException) { }
        }
        try { verifyUpdateIdentity(release,installed.copy(versionCode=100),apk); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun installerSharesOnlyAppUpdateFilesAsContentUrisNotWebUrls() {
        val context=ApplicationProvider.getApplicationContext<Application>()
        val dir=File(context.cacheDir,"app-updates").apply { mkdirs() };val file=File(dir,"a.apk").apply { writeText("test") }
        try {
            val intent=updateInstallIntent(context,file)
            assertEquals(Intent.ACTION_VIEW,intent.action)
            assertEquals("content",intent.data!!.scheme)
            assertEquals("application/vnd.android.package-archive",intent.type)
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertEquals(0,intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            context.contentResolver.openInputStream(intent.data!!)!!.use { assertEquals("test",it.readBytes().decodeToString()) }
            val outside=File(context.cacheDir,"private-receipt.txt").apply { writeText("not-an-update") }
            try { updateInstallIntent(context,outside); fail() } catch (_: IllegalArgumentException) { } finally { outside.delete() }
        } finally { file.delete() }
    }
    @Test fun manualCheckDownloadPermissionReturnAndInstallNeverClaimsSuccess() = runBlocking {
        val dir=Files.createTempDirectory("update-flow").toFile();val host=UpdateHostFixture(dir);val source=UpdateSourceFixture(release)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try {
            val update=MobileUpdateController(scope,source,host,12)
            assertEquals(0,source.checks)
            update.check(); update.awaitIdle(); assertEquals(AppUpdatePhase.Available,update.state.value.phase)
            update.download(); update.awaitIdle(); assertEquals(AppUpdatePhase.Ready,update.state.value.phase)
            update.install(); update.awaitIdle(); assertEquals(1,host.permissions);assertEquals(0,host.installs)
            host.allowed=true;update.resumed();update.install();update.awaitIdle()
            assertEquals(1,host.installs);assertEquals(AppUpdatePhase.Ready,update.state.value.phase)
            assertTrue(update.state.value.message!!.contains("系統"));assertFalse(update.state.value.message!!.contains("更新成功"))
        } finally { scope.cancel();dir.deleteRecursively() }
    }
    @Test fun duplicateDownloadsCancelAndLateProgressCannotChangeTheNewState() = runBlocking {
        val dir=Files.createTempDirectory("update-cancel").toFile();val host=UpdateHostFixture(dir);val source=UpdateSourceFixture(release)
        val entered=CompletableDeferred<Unit>();var callback:((Long)->Unit)?=null
        source.transfer={ _,progress -> callback=progress;entered.complete(Unit);awaitCancellation() }
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try {
            val update=MobileUpdateController(scope,source,host,12)
            update.check();update.awaitIdle();update.download();entered.await();update.download();assertEquals(1,source.downloads)
            update.cancel();update.awaitIdle();val cancelled=update.state.value
            callback!!(3);assertEquals(cancelled,update.state.value);assertEquals(0,host.installs)
        } finally { scope.cancel();dir.deleteRecursively() }
    }
    @Test fun connectionTimeoutLeavesARecoverableFailureInsteadOfAnIndefiniteSpinner() = runBlocking {
        val dir=Files.createTempDirectory("update-timeout").toFile();val host=UpdateHostFixture(dir)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try {
            val source=object:MobileUpdateSource {
                var checkingTimesOut=true
                override suspend fun appRelease():AppRelease = if(checkingTimesOut) withTimeout(1) {awaitCancellation()} else release
                override suspend fun downloadAppUpdate(release:AppRelease,file:File,progress:(Long)->Unit):Unit = withTimeout(1) {awaitCancellation()}
            }
            val update=MobileUpdateController(scope,source,host,12)
            update.check();update.awaitIdle();assertEquals(AppUpdatePhase.Failed,update.state.value.phase)
            assertTrue(update.state.value.message!!.contains("逾時"))
            source.checkingTimesOut=false;update.check();update.awaitIdle();update.download();update.awaitIdle()
            assertEquals(AppUpdatePhase.Failed,update.state.value.phase);assertTrue(update.state.value.message!!.contains("逾時"));assertEquals(0,host.installs)
        } finally {scope.cancel();dir.deleteRecursively()}
    }
    @Test fun restartingTheUpdateControllerRevalidatesTheCompleteCachedApk() = runBlocking {
        val dir=Files.createTempDirectory("update-restart").toFile();val host=UpdateHostFixture(dir);val source=UpdateSourceFixture(release)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try {
            val first=MobileUpdateController(scope,source,host,12)
            first.check();first.awaitIdle();first.download();first.awaitIdle()
            val restored=MobileUpdateController(scope,source,host,12)
            assertEquals(AppUpdatePhase.Idle,restored.state.value.phase)
            restored.check();restored.awaitIdle();assertEquals(AppUpdatePhase.Ready,restored.state.value.phase);assertEquals(1,source.downloads)
        } finally {scope.cancel();dir.deleteRecursively()}
    }
    @Test fun anOlderServerReleaseIsNotOfferedAsAnUpdate() = runBlocking {
        val dir=Files.createTempDirectory("update-no-downgrade").toFile();val source=UpdateSourceFixture(release.copy(versionCode=10));val host=UpdateHostFixture(dir)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try {
            val update=MobileUpdateController(scope,source,host,12)
            update.check();update.awaitIdle();assertEquals(AppUpdatePhase.Current,update.state.value.phase)
            update.download();update.install();update.awaitIdle();assertEquals(0,source.downloads);assertEquals(0,host.installs)
        } finally {scope.cancel();dir.deleteRecursively()}
    }
    @Test fun expiredSessionAndRemovedReadyFileDoNotOpenInstaller() = runBlocking {
        val dir=Files.createTempDirectory("update-expiry").toFile();val host=UpdateHostFixture(dir);val source=UpdateSourceFixture(release)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined);var auth=0
        try {
            val update=MobileUpdateController(scope,source,host,12){auth++}
            source.failure=MobileApiFailure(401);update.check();update.awaitIdle();assertEquals(1,auth);assertEquals(AppUpdatePhase.Failed,update.state.value.phase)
            source.failure=null;update.check();update.awaitIdle();update.download();update.awaitIdle();host.allowed=true;host.file(release).delete()
            update.install();update.awaitIdle();assertEquals(0,host.installs);assertEquals(AppUpdatePhase.Failed,update.state.value.phase)
        } finally { scope.cancel();dir.deleteRecursively() }
    }
}

internal class UpdateSourceFixture(val release:AppRelease):MobileUpdateSource {
    var checks=0;var downloads=0;var failure:Exception?=null
    var transfer:suspend(File,(Long)->Unit)->Unit={file,progress ->file.parentFile!!.mkdirs();file.writeText("apk");progress(3)}
    override suspend fun appRelease():AppRelease { checks++;failure?.let {throw it};return release }
    override suspend fun downloadAppUpdate(release:AppRelease,file:File,progress:(Long)->Unit) {downloads++;transfer(file,progress)}
}
internal class UpdateHostFixture(private val dir:File):MobileUpdateHost {
    var allowed=false;var permissions=0;var installs=0
    override fun file(release:AppRelease)=File(dir,"${release.sha256}.apk")
    override fun canInstall()=allowed
    override fun verify(release:AppRelease) {require(file(release).readText()=="apk")}
    override fun permission() {permissions++}
    override fun install(release:AppRelease) {installs++}
}
