package com.momonong.pixelreceipt.mobile

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.core.view.WindowCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real Activity/Window flags and composition disposal; no controller mocks or app data. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MobileSystemBarsTest {
    @get:Rule val ui = createEmptyComposeRule()
    private var scenario: ActivityScenario<MobileSystemBarsFixtureActivity>? = null

    @After fun close() { scenario?.close() }

    private fun launch(status: Boolean, navigation: Boolean): ActivityScenario<MobileSystemBarsFixtureActivity> {
        val context = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(context.packageManager).addActivityIfNotPresent(ComponentName(context, MobileSystemBarsFixtureActivity::class.java))
        return ActivityScenario.launch<MobileSystemBarsFixtureActivity>(Intent(context, MobileSystemBarsFixtureActivity::class.java).apply {
            putExtra("originalStatus", status)
            putExtra("originalNavigation", navigation)
        }).also { scenario = it; ui.waitForIdle() }
    }

    private fun assertAppearance(activity: MobileSystemBarsFixtureActivity, status: Boolean, navigation: Boolean) {
        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        assertEquals(status, controller.isAppearanceLightStatusBars)
        assertEquals(navigation, controller.isAppearanceLightNavigationBars)
    }

    @Test fun centralUsesDarkIconsAndLeavingRestoresMixedOriginalValues() {
        val active = launch(status = true, navigation = false)
        active.onActivity { assertAppearance(it, status = true, navigation = true); it.central.value = false }
        ui.waitForIdle()
        active.onActivity { assertAppearance(it, status = true, navigation = false); it.central.value = true }
        ui.waitForIdle()
        active.onActivity { assertAppearance(it, status = true, navigation = true); it.central.value = false }
        ui.waitForIdle()
        active.onActivity { assertAppearance(it, status = true, navigation = false) }
    }

    @Test fun recreatedActivityReappliesCentralIconsAndRestoresItsOwnWindow() {
        val active = launch(status = false, navigation = true)
        var previousActivity: MobileSystemBarsFixtureActivity? = null
        var previousWindow: Window? = null
        active.onActivity {
            previousActivity = it
            previousWindow = it.window
            assertAppearance(it, status = true, navigation = true)
        }
        active.recreate()
        ui.waitForIdle()
        active.onActivity {
            assertNotSame(previousActivity, it)
            assertNotSame(previousWindow, it.window)
            assertAppearance(it, status = true, navigation = true)
            it.central.value = false
        }
        ui.waitForIdle()
        active.onActivity { assertAppearance(it, status = false, navigation = true) }
    }
}

class MobileSystemBarsFixtureActivity : ComponentActivity() {
    val central = mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = intent.getBooleanExtra("originalStatus", false)
        controller.isAppearanceLightNavigationBars = intent.getBooleanExtra("originalNavigation", false)
        setContent {
            if (central.value) MobileSystemBars()
            Text(if (central.value) "central" else "legacy")
        }
    }
}
