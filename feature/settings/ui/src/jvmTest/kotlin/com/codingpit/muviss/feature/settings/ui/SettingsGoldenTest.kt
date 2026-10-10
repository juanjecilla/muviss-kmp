@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.settings.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.runComposeUiTest
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import com.codingpit.muviss.core.testing.GoldenSurface
import com.codingpit.muviss.core.testing.assertMatchesGolden
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * What Settings looks like on first open, in both themes: section headings,
 * value rows and switches are the tokens a theme change reaches first.
 *
 * Renders the real [SettingsScreen] over the same fakes as
 * [CrashReportsSettingTest]. `darkTheme` is passed explicitly — `MuvissTheme`
 * otherwise follows the host machine's setting — and the stored theme
 * preference is left at its default, since that is what decides the app's
 * theme on a device, not this frame.
 *
 * The default tolerance, deliberately: Ubuntu's heavier glyphs have not been
 * measured against this frame yet. If CI fails it reports the measured share,
 * and that number, with headroom, belongs in a documented constant.
 */
class SettingsGoldenTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun settings_opens_on_its_first_sections() = runComposeUiTest {
        setContent { SettingsUnderTest(darkTheme = false) }
        waitForIdle()
        assertMatchesGolden("settings-screen")
    }

    @Test
    fun settings_opens_on_its_first_sections_dark() = runComposeUiTest {
        setContent { SettingsUnderTest(darkTheme = true) }
        waitForIdle()
        assertMatchesGolden("settings-screen-dark")
    }
}

@Composable
private fun SettingsUnderTest(darkTheme: Boolean) {
    MuvissTheme(darkTheme = darkTheme) {
        GoldenSurface {
            SettingsScreen(settingsViewModel(FakeRepository()), onOpenLicenses = {}, onOpenImport = {}, onOpenTriage = {})
        }
    }
}
