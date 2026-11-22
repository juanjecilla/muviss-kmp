@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.settings.ui

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import com.codingpit.muviss.core.testing.FakeFeatureFlags
import com.codingpit.muviss.feature.nextpick.api.NextPickPreferences
import com.codingpit.muviss.feature.nextpick.api.ResultMode
import com.codingpit.muviss.feature.nextpick.api.SessionLength
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The "What's next?" section of Settings (EPIC 51, #403): the rows show the stored values, and each one writes its
 * setting through the view model. Driven through the real screen, as the other Settings row tests are.
 */
class NextPickSettingsRowTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun the_rows_show_the_defaults_on_a_fresh_install() = runComposeUiTest {
        showSettings(FakeNextPickPreferences(), FakeFeatureFlags())

        onNodeWithTag(NEXT_PICK_PROMPT_TAG).performScrollTo().assertIsOn()
        onNodeWithText("Standard (up to 4)").performScrollTo()
        onNodeWithText("One pick").performScrollTo()
    }

    @Test
    fun the_completion_prompt_switch_writes_the_flag() = runComposeUiTest {
        val flags = FakeFeatureFlags()
        showSettings(FakeNextPickPreferences(), flags)

        onNodeWithTag(NEXT_PICK_PROMPT_TAG).performScrollTo().performClick()
        waitForIdle()

        assertEquals(false, flags.completionPromptEnabled.value)
        onNodeWithTag(NEXT_PICK_PROMPT_TAG).assertIsOff()
    }

    @Test
    fun a_turned_off_prompt_is_shown_off() = runComposeUiTest {
        showSettings(FakeNextPickPreferences(), FakeFeatureFlags().apply { completionPromptEnabled.value = false })

        onNodeWithTag(NEXT_PICK_PROMPT_TAG).performScrollTo().assertIsOff()
    }

    @Test
    fun choosing_no_questions_writes_the_session_length() = runComposeUiTest {
        val preferences = FakeNextPickPreferences()
        showSettings(preferences, FakeFeatureFlags())

        onNodeWithText("Questions").performScrollTo().performClick()
        waitForIdle()
        onNodeWithText("No questions").performClick()
        waitForIdle()

        assertEquals(SessionLength.NONE, preferences.length.value)
    }

    @Test
    fun choosing_deck_writes_the_result_mode() = runComposeUiTest {
        val preferences = FakeNextPickPreferences()
        showSettings(preferences, FakeFeatureFlags())

        onNodeWithText("Result").performScrollTo().performClick()
        waitForIdle()
        onNodeWithText("Deck").performClick()
        waitForIdle()

        assertEquals(ResultMode.DECK, preferences.mode.value)
    }

    @Test
    fun the_section_is_absent_without_a_view_model() = runComposeUiTest {
        val settings = settingsViewModel(FakeRepository(), flags = FakeFeatureFlags())
        setContent { MuvissTheme(darkTheme = false) { SettingsScreen(settings, {}, {}, {}) } }
        waitForIdle()

        assertTrue(onAllNodesWithTag(NEXT_PICK_PROMPT_TAG).fetchSemanticsNodes().isEmpty())
    }

    private fun ComposeUiTest.showSettings(preferences: NextPickPreferences, flags: FakeFeatureFlags) {
        // Built once, outside composition, so a recomposition cannot rebuild a view model and reset its state.
        val settings = settingsViewModel(FakeRepository(), flags = flags)
        val nextPick = NextPickSettingsViewModel(flags, preferences)
        setContent {
            MuvissTheme(darkTheme = false) {
                SettingsScreen(settings, onOpenLicenses = {}, onOpenImport = {}, onOpenTriage = {}, nextPick = nextPick)
            }
        }
        waitForIdle()
    }
}

/** A [NextPickPreferences] over plain state; the screen writes through it and the test reads it back. */
internal class FakeNextPickPreferences : NextPickPreferences {
    val length = MutableStateFlow(SessionLength.DEFAULT)
    val mode = MutableStateFlow(ResultMode.DEFAULT)

    override val sessionLength: StateFlow<SessionLength> = length.asStateFlow()
    override val resultMode: StateFlow<ResultMode> = mode.asStateFlow()

    override suspend fun setSessionLength(length: SessionLength) {
        this.length.value = length
    }

    override suspend fun setResultMode(mode: ResultMode) {
        this.mode.value = mode
    }
}
