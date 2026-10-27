@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.settings.ui

import com.codingpit.muviss.core.testing.FakeFeatureFlags
import com.codingpit.muviss.feature.nextpick.api.ResultMode
import com.codingpit.muviss.feature.nextpick.api.SessionLength
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class NextPickSettingsViewModelTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun the_state_starts_at_the_defaults_the_stored_values_would_give() {
        val viewModel = NextPickSettingsViewModel(FakeFeatureFlags(), FakeNextPickPreferences())

        assertEquals(NextPickSettingsUiState(), viewModel.state.value)
    }

    @Test
    fun the_state_follows_what_is_stored() {
        val flags = FakeFeatureFlags().apply { completionPromptEnabled.value = false }
        val preferences = FakeNextPickPreferences()
        preferences.length.value = SessionLength.QUICK
        preferences.mode.value = ResultMode.TOP_PICKS

        val state = NextPickSettingsViewModel(flags, preferences).state.value

        assertEquals(NextPickSettingsUiState(completionPromptEnabled = false, sessionLength = SessionLength.QUICK, resultMode = ResultMode.TOP_PICKS), state)
    }

    @Test
    fun toggling_the_prompt_writes_the_flag_and_the_state_follows() {
        val flags = FakeFeatureFlags()
        val viewModel = NextPickSettingsViewModel(flags, FakeNextPickPreferences())

        viewModel.onCompletionPromptToggled(false)

        assertEquals(false, flags.completionPromptEnabled.value)
        assertEquals(false, viewModel.state.value.completionPromptEnabled)
    }

    @Test
    fun selecting_a_session_length_writes_it() {
        val preferences = FakeNextPickPreferences()
        val viewModel = NextPickSettingsViewModel(FakeFeatureFlags(), preferences)

        viewModel.onSessionLengthSelected(SessionLength.NONE)

        assertEquals(SessionLength.NONE, preferences.length.value)
        assertEquals(SessionLength.NONE, viewModel.state.value.sessionLength)
    }

    @Test
    fun selecting_a_result_mode_writes_it() {
        val preferences = FakeNextPickPreferences()
        val viewModel = NextPickSettingsViewModel(FakeFeatureFlags(), preferences)

        viewModel.onResultModeSelected(ResultMode.DECK)

        assertEquals(ResultMode.DECK, preferences.mode.value)
        assertEquals(ResultMode.DECK, viewModel.state.value.resultMode)
    }
}
