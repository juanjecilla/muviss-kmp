@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.settings.ui

import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import com.codingpit.muviss.core.designsystem.layout.ScreenInsets
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import com.codingpit.muviss.core.testing.FakeFeatureFlags
import com.codingpit.muviss.core.testing.GoldenSurface
import com.codingpit.muviss.core.testing.TestSafeAreaInsets
import com.codingpit.muviss.core.testing.assertMatchesGolden
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * The "What's next?" section and the "Your taste" row of Settings (EPIC 51, #542, #587), in both themes.
 * [SettingsGoldenTest] opens Settings at the top, so neither is in its frame. These scroll to each one, so
 * the switch, its two pickers and the row are drawn where a person meets them.
 *
 * Rendered through the real [SettingsScreen] over the same fakes as [NextPickSettingsRowTest]. `darkTheme` is
 * passed explicitly, as CLAUDE.md asks: the host's setting must not change the frame.
 */
class WhatsNextSettingsGoldenTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun whats_next_section_light() = runComposeUiTest {
        showSettings(darkTheme = false)
        onNodeWithText(RESULT_ROW_LABEL).performScrollTo()
        waitForIdle()
        assertMatchesGolden("settings-whats-next", tolerance = SECTION_TOLERANCE)
    }

    @Test
    fun whats_next_section_dark() = runComposeUiTest {
        showSettings(darkTheme = true)
        onNodeWithText(RESULT_ROW_LABEL).performScrollTo()
        waitForIdle()
        assertMatchesGolden("settings-whats-next-dark", tolerance = SECTION_TOLERANCE)
    }

    @Test
    fun your_taste_row_light() = runComposeUiTest {
        showSettings(darkTheme = false)
        onNodeWithText(TASTE_ROW_LABEL).performScrollTo()
        waitForIdle()
        assertMatchesGolden("settings-your-taste", tolerance = SECTION_TOLERANCE)
    }

    @Test
    fun your_taste_row_dark() = runComposeUiTest {
        showSettings(darkTheme = true)
        onNodeWithText(TASTE_ROW_LABEL).performScrollTo()
        waitForIdle()
        assertMatchesGolden("settings-your-taste-dark", tolerance = SECTION_TOLERANCE)
    }

    private fun ComposeUiTest.showSettings(darkTheme: Boolean) {
        val flags = FakeFeatureFlags()
        // Built once, outside composition, so a recomposition cannot rebuild a view model and reset its state.
        val settings = settingsViewModel(FakeRepository(), flags = flags)
        val nextPick = NextPickSettingsViewModel(flags, FakeNextPickPreferences())
        setContent {
            MuvissTheme(darkTheme = darkTheme) {
                GoldenSurface {
                    // The shell's safe area, without the bottom inset: the nav bar pads that side, as in the app.
                    ScreenInsets(insets = TestSafeAreaInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)) {
                        SettingsScreen(
                            settings,
                            onOpenLicenses = {},
                            onOpenImport = {},
                            onOpenTriage = {},
                            onOpenTasteProfile = {},
                            nextPick = nextPick,
                        )
                    }
                }
            }
        }
        waitForIdle()
    }
}

/** The row's label in English: the test JVMs are pinned to en-US (CLAUDE.md, UI copy is localized). */
private const val TASTE_ROW_LABEL = "Your taste"

/** The last picker row: scrolling to it brings both pickers into the section frame. */
private const val RESULT_ROW_LABEL = "Result"

/**
 * The section is a switch and two pickers over a Compose scroll; the same text-edge tolerance as the
 * Settings golden. Ubuntu's heavier glyphs are measured by CI, not here: a failure reports the share.
 */
private const val SECTION_TOLERANCE = 0.04
