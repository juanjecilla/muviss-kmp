@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.settings.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import com.codingpit.muviss.core.testing.FakeFeatureFlags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** EPIC 51: "Animate episode ticks" narrows the master Animations switch, so it is greyed — not hidden — while that is off. */
class TickAnimationsSettingTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun the_switch_is_live_while_animations_are_on_and_writes_the_flag() = runComposeUiTest {
        val flags = FakeFeatureFlags()
        setContent { MuvissTheme(darkTheme = false) { SettingsScreen(settingsViewModel(FakeRepository(), flags = flags), {}, {}, {}) } }
        waitForIdle()

        onNodeWithText("Animate episode ticks").performScrollTo().assertIsEnabled().performClick()
        waitForIdle()

        assertEquals(false, flags.tickAnimations.value)
    }

    @Test
    fun the_switch_is_greyed_while_the_master_is_off() = runComposeUiTest {
        val flags = FakeFeatureFlags().apply { animationsEnabled.value = false }
        setContent { MuvissTheme(darkTheme = false) { SettingsScreen(settingsViewModel(FakeRepository(), flags = flags), {}, {}, {}) } }
        waitForIdle()

        onNodeWithText("Animate episode ticks").performScrollTo().assertIsNotEnabled()
    }
}
