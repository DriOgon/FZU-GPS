package com.driogon.fzucheckin.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.driogon.fzucheckin.core.CheckinStatus
import com.driogon.fzucheckin.automation.AutomationSettings
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Renders only synthetic state in the isolated .debug application. No repository or network. */
@RunWith(AndroidJUnit4::class)
class DeviceScreensTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun capture(name: String) {
        compose.waitForIdle()
        val exported = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        val folder = if (exported != null) File(exported, "ui-previews")
            else InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir("ui-previews")!!
        check(folder.exists() || folder.mkdirs())
        File(folder, "$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
    private fun render(state: AppState = demoState()) {
        compose.setContent { FzuTheme { FzuApp(state, { _, _ -> }, {}, {}, {}) } }
    }
    @Test fun login() {
        render(AppState())
        compose.onNodeWithTag("username").assertIsDisplayed()
        compose.onNodeWithTag("login").performScrollTo().assertIsEnabled()
        capture("login")
    }
    @Test fun homeRequiresManualSubmission() {
        var submits = 0
        compose.setContent { FzuTheme { FzuApp(demoState(), { _, _ -> }, {}, { submits++ }, {}) } }
        assertEquals(0, submits)
        capture("home")
        compose.onNodeWithTag("checkin").performScrollTo().performClick()
        assertEquals(1, submits)
    }
    @Test fun profile() {
        render()
        compose.onNodeWithText("我的").performClick()
        compose.onNodeWithText("24*****01").assertExists()
        capture("profile")
    }
    @Test fun automationSettingsUseSyntheticStateOnly() {
        var saves = 0
        val state = demoState().copy(automation = AutomationSettings(enabled = true, accountId = "synthetic", lastMessage = "已签到，已跳过（示例）"))
        compose.setContent { FzuTheme { FzuApp(state, { _, _ -> }, {}, {}, {}, { _, _ -> saves++ }) } }
        compose.onNodeWithText("我的").performClick()
        compose.onNodeWithTag("autoResult").performScrollTo().assertIsDisplayed()
        capture("auto-settings")
        compose.onNodeWithTag("autoSwitch").performScrollTo().performClick()
        assertEquals(1, saves)
    }
    @Test fun success() {
        render(demoState(CheckinStatus.SUCCESS))
        compose.onNodeWithTag("checkin").performScrollTo().assertIsNotEnabled()
        capture("success")
    }
    @Test fun processing() {
        render(demoState(CheckinStatus.PROCESSING).copy(phase = BusyPhase.CONFIRM))
        compose.onNodeWithTag("checkin").performScrollTo().assertIsNotEnabled()
        capture("processing")
    }
    @Test fun failure() {
        render(demoState().copy(error = "网络连接失败，请刷新后重试"))
        capture("failure")
    }
    @Test fun largeFontAndKeyboard() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                FzuTheme { FzuApp(AppState(), { _, _ -> }, {}, {}, {}) }
            }
        }
        compose.onNodeWithTag("password").performScrollTo().performClick().performTextInput("synthetic-password")
        compose.onNodeWithTag("login").performScrollTo().assertIsDisplayed()
        capture("large-font-keyboard")
    }
}
