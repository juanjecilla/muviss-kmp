@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.settings.ui

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.onNodeWithTag
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
import kotlin.test.assertTrue

/**
 * "Your taste" sits in the What's next? section, after its rows, not under Content (#541). Positions are read at
 * the top of the scroll, where every row is laid out in its place, so the comparisons are between row positions
 * rather than what is on screen.
 */
class TasteProfileRowPlacementTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun your_taste_comes_after_the_result_row_inside_whats_next() = runComposeUiTest {
        showSettings(nextPick = NextPickSettingsViewModel(FakeFeatureFlags(), FakeNextPickPreferences()))

        val whatsNext = onNodeWithText(WHATS_NEXT_HEADER).fetchSemanticsNode().positionInRoot.y
        val result = onNodeWithText(RESULT_ROW_LABEL).fetchSemanticsNode().positionInRoot.y
        val taste = onNodeWithTag(TASTE_PROFILE_ROW_TAG).fetchSemanticsNode().positionInRoot.y
        val data = onNodeWithText(DATA_HEADER).fetchSemanticsNode().positionInRoot.y

        assertTrue(whatsNext < result, "Result row must sit under the What's next? header")
        assertTrue(result < taste, "Your taste must come after the What's next? rows")
        assertTrue(taste < data, "Your taste must stay above the Data section")
    }

    @Test
    fun your_taste_is_no_longer_under_content() = runComposeUiTest {
        showSettings(nextPick = NextPickSettingsViewModel(FakeFeatureFlags(), FakeNextPickPreferences()))

        val mutedGenres = onNodeWithText(MUTED_GENRES_LABEL).fetchSemanticsNode().positionInRoot.y
        val whatsNext = onNodeWithText(WHATS_NEXT_HEADER).fetchSemanticsNode().positionInRoot.y

        assertTrue(mutedGenres < whatsNext, "Muted genres stays in Content, above the What's next? header")
    }

    @Test
    fun your_taste_stays_under_whats_next_without_the_prompt_rows() = runComposeUiTest {
        // The screen's own tests build it with no view model; the row opens the editor either way.
        showSettings(nextPick = null)

        val whatsNext = onNodeWithText(WHATS_NEXT_HEADER).fetchSemanticsNode().positionInRoot.y
        val taste = onNodeWithTag(TASTE_PROFILE_ROW_TAG).fetchSemanticsNode().positionInRoot.y

        assertTrue(whatsNext < taste, "Your taste must sit under the What's next? header even with no prompt rows")
    }

    @Test
    fun tapping_your_taste_opens_the_editor() = runComposeUiTest {
        var opened = false
        showSettings(nextPick = null, onOpenTasteProfile = { opened = true })

        onNodeWithTag(TASTE_PROFILE_ROW_TAG).performScrollTo().performClick()
        waitForIdle()

        assertTrue(opened)
    }

    private fun ComposeUiTest.showSettings(
        nextPick: NextPickSettingsViewModel?,
        onOpenTasteProfile: () -> Unit = {},
    ) {
        // Built once, outside composition, so a recomposition cannot rebuild a view model and reset its state.
        val settings = settingsViewModel(FakeRepository(), flags = FakeFeatureFlags())
        setContent {
            MuvissTheme(darkTheme = false) {
                SettingsScreen(
                    settings,
                    onOpenLicenses = {},
                    onOpenImport = {},
                    onOpenTriage = {},
                    onOpenTasteProfile = onOpenTasteProfile,
                    nextPick = nextPick,
                )
            }
        }
        waitForIdle()
    }
}

/** Section overlines are drawn uppercase (SectionOverline), so the header is matched in capitals. */
private const val WHATS_NEXT_HEADER = "WHAT'S NEXT?"
private const val RESULT_ROW_LABEL = "Result"
private const val DATA_HEADER = "DATA"
private const val MUTED_GENRES_LABEL = "Muted genres"
