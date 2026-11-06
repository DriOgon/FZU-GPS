package com.driogon.fzucheckin.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import com.driogon.fzucheckin.core.CheckinStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreensTest {
    @get:Rule val compose = createComposeRule()

    @Test fun loginAcceptsInputAndShowsPasswordToggle() {
        var user = ""; var calls = 0
        compose.setContent { FzuTheme { FzuApp(AppState(), { u, _ -> user = u; calls++ }, {}, {}, {}) } }
        compose.onNodeWithTag("username").performTextInput("synthetic-account")
        compose.onNodeWithTag("password").performTextInput("synthetic-password")
        compose.onNodeWithContentDescription("显示密码").performClick()
        compose.onNodeWithContentDescription("隐藏密码").assertExists()
        compose.onNodeWithTag("login").performScrollTo().performClick()
        assertEquals("synthetic-account", user); assertEquals(1, calls)
    }

    @Test fun homeDoesNotSubmitUntilClickedAndProfileCanBeOpened() {
        var submissions = 0
        compose.setContent { FzuTheme { FzuApp(demoState(), { _, _ -> }, {}, { submissions++ }, {}) } }
        assertEquals(0, submissions)
        compose.onNodeWithTag("checkin").performScrollTo().performClick()
        assertEquals(1, submissions)
        compose.onNodeWithText("我的").performClick()
        compose.onNodeWithText("24*****01").assertExists()
    }

    @Test fun processingAndBusyStatesCannotSubmitAgain() {
        compose.setContent { FzuTheme { FzuApp(demoState(CheckinStatus.PROCESSING).copy(phase = BusyPhase.CONFIRM), { _, _ -> }, {}, {}, {}) } }
        compose.onNodeWithTag("checkin").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("签到处理中").assertExists()
    }

    @Test fun outsideModeRequiresAnAddress() {
        compose.setContent {
            FzuTheme { FzuApp(demoState().let { it.copy(snapshot = it.snapshot!!.copy(way = 2, status = CheckinStatus.NEEDS_ADDRESS)) }, { _, _ -> }, {}, {}, {}) }
        }
        compose.onNodeWithTag("checkin").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("address").performScrollTo().performTextInput("测试地址")
        compose.onNodeWithTag("checkin").performScrollTo().assertIsEnabled()
    }

    @Test fun smallScreenAndLargeFontKeepLoginReachable() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                FzuTheme { FzuApp(AppState(error = "学校登录要求验证码，请稍后重试"), { _, _ -> }, {}, {}, {}) }
            }
        }
        compose.onNodeWithTag("login").performScrollTo().assertIsDisplayed()
    }
}
