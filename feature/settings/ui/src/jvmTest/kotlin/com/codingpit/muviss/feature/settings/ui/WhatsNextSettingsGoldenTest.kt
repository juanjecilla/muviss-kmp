@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.settings.ui

import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
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
import kotlin.test.assertEquals

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
        assertLabelOnOneLine(TASTE_ROW_LABEL)
        assertMatchesGolden("settings-your-taste", tolerance = SECTION_TOLERANCE)
    }

    /** #810: at 200% font scale the label still fits on one line, the value is what gives way. */
    @Test
    fun your_taste_label_stays_on_one_line_at_double_font_scale() = runComposeUiTest {
        showSettings(darkTheme = false, fontScale = 2f)
        onNodeWithText(TASTE_ROW_LABEL).performScrollTo()
        waitForIdle()
        assertLabelOnOneLine(TASTE_ROW_LABEL)
        assertMatchesGolden("settings-your-taste-font-200", tolerance = SECTION_TOLERANCE)
    }

    @Test
    fun your_taste_row_dark() = runComposeUiTest {
        showSettings(darkTheme = true)
        onNodeWithText(TASTE_ROW_LABEL).performScrollTo()
        waitForIdle()
        assertMatchesGolden("settings-your-taste-dark", tolerance = SECTION_TOLERANCE)
    }

    /** One text layout, so the label has not wrapped: a second line would make the count two. */
    private fun ComposeUiTest.assertLabelOnOneLine(label: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        onNodeWithText(label).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        assertEquals(1, layouts.single().lineCount, "\"$label\" should stay on one line")
    }

    private fun ComposeUiTest.showSettings(darkTheme: Boolean, fontScale: Float = 1f) {
        val flags = FakeFeatureFlags()
        // Built once, outside composition, so a recomposition cannot rebuild a view model and reset its state.
        val settings = settingsViewModel(FakeRepository(), flags = flags)
        val nextPick = NextPickSettingsViewModel(flags, FakeNextPickPreferences())
        setContent {
            // Scales text only, as the user's font setting does: density and every other size stay the same.
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
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
