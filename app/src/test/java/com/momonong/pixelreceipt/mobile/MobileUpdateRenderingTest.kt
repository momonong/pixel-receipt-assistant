package com.momonong.pixelreceipt.mobile

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.momonong.pixelreceipt.core.ui.theme.PixelReceiptTheme
import com.momonong.pixelreceipt.data.ingestion.ImageStore
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class,qualifiers="w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MobileUpdateRenderingTest {
    @get:Rule val ui=createEmptyComposeRule()
    private lateinit var scenario:ActivityScenario<ComponentActivity>
    private lateinit var dir:File
    private val update=mutableStateOf(AppUpdateState())
    private val scale=mutableStateOf(1f)
    private var checked=0;private var downloaded=0;private var installed=0;private var cancelled=0
    private val release=AppRelease(100,"1.0.0",60L*1024*1024,"a".repeat(64),"b".repeat(64))
    @Before fun setup() {
        val context=ApplicationProvider.getApplicationContext<Application>()
        dir=File(context.cacheDir,UUID.randomUUID().toString()).apply {mkdirs()}
        shadowOf(context.packageManager).addActivityIfNotPresent(ComponentName(context,ComponentActivity::class.java))
        scenario=ActivityScenario.launch(Intent(context,ComponentActivity::class.java))
        scenario.onActivity { it.setContent { PixelReceiptTheme(darkTheme=false,dynamicColor=false) {
            CompositionLocalProvider(LocalDensity provides Density(1f,scale.value)) { MobileContent(MobileUiState(page=MobilePage.Settings,signedIn=true,appUpdate=update.value),ImageStore(dir),MobileActions(checkAppUpdate={checked++},downloadAppUpdate={downloaded++},installAppUpdate={installed++},cancelAppUpdate={cancelled++})) }
        } } }
    }
    @After fun cleanup() {scenario.close();dir.deleteRecursively()}
    private fun show() {ui.onNodeWithTag("mobile-settings-page").performScrollToNode(hasTestTag("app-update-action"));ui.onNodeWithTag("app-update-action").performScrollTo()}
    private fun capture(name:String) {
        ui.waitForIdle();val image=ui.onRoot().captureToImage().asAndroidBitmap();val file=File(System.getProperty("pixelreceipt.screenshots"),"$name.png")
        file.parentFile!!.mkdirs();file.outputStream().use {assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,it))}
    }
    @Test fun settingsOffersCheckThenDownloadThenNativeInstallWithoutAWebLink() {
        show();ui.onNodeWithTag("app-update-action").assertTextContains("檢查更新").performClick();assertEquals(1,checked)
        ui.runOnIdle {update.value=AppUpdateState(AppUpdatePhase.Available,release)}
        show();ui.onNodeWithText("可更新至 1.0.0 · 60.0 MB").assertExists()
        capture("app-update-available-390")
        ui.onNodeWithTag("app-update-action").assertTextContains("下載更新").performClick();assertEquals(1,downloaded)
        ui.runOnIdle {update.value=AppUpdateState(AppUpdatePhase.Ready,release,canInstall=true)}
        show();ui.onNodeWithTag("app-update-action").assertTextContains("安裝更新").performClick();assertEquals(1,installed)
        capture("app-update-ready-390")
    }
    @Test fun downloadShowsProgressAndCancellationWhilePermissionIsASeparateSystemStep() {
        ui.runOnIdle {update.value=AppUpdateState(AppUpdatePhase.Downloading,release,30L*1024*1024)}
        show();ui.onNodeWithText("30.0 MB / 60.0 MB · 50%").assertExists()
        ui.onNodeWithTag("app-update-action").assertIsNotEnabled()
        ui.onNodeWithTag("app-update-cancel").performScrollTo().performClick();assertEquals(1,cancelled)
        ui.runOnIdle {update.value=AppUpdateState(AppUpdatePhase.Ready,release,canInstall=false)}
        show();ui.onNodeWithTag("app-update-action").assertTextContains("允許安裝更新").performClick();assertEquals(1,installed)
    }
    @Config(qualifiers="w740dp-h360dp-mdpi")
    @Test fun landscapeLargeTextKeepsTheDownloadActionReachable() {
        ui.runOnIdle {scale.value=1.5f;update.value=AppUpdateState(AppUpdatePhase.Available,release)}
        show();ui.onNodeWithTag("app-update-action").assertIsDisplayed().performClick();assertEquals(1,downloaded)
        capture("app-update-landscape-font150")
    }
    @Config(qualifiers="w320dp-h740dp-mdpi")
    @Test fun largeTextCanReachUpdateActionAndInlineFailure() {
        ui.runOnIdle {scale.value=1.5f;update.value=AppUpdateState(AppUpdatePhase.Failed,release,message="更新未完成，請重新檢查。原本的 App 與收據資料保留。")}
        show();ui.onNodeWithTag("app-update-action").assertIsDisplayed().performClick();assertEquals(1,checked)
        capture("app-update-failed-320-font150")
    }
}
