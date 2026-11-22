package com.codingpit.muviss.feature.settings.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.codingpit.muviss.core.common.crash.launchInReporting
import com.codingpit.muviss.core.common.crash.launchReporting
import com.codingpit.muviss.core.common.flags.FeatureFlags
import com.codingpit.muviss.feature.nextpick.api.NextPickPreferences
import com.codingpit.muviss.feature.nextpick.api.ResultMode
import com.codingpit.muviss.feature.nextpick.api.SessionLength
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update

/** The "What's next?" rows (EPIC 51, #403). Every value is per device; the defaults are the ones the columns declare. */
data class NextPickSettingsUiState(
    val completionPromptEnabled: Boolean = true,
    val sessionLength: SessionLength = SessionLength.DEFAULT,
    val resultMode: ResultMode = ResultMode.DEFAULT,
)

/**
 * Drives the Next Pick section of Settings. Its own view model rather than more fields on [SettingsViewModel]: it reads two
 * sources (the completion switch from [FeatureFlags], the two enums from [NextPickPreferences]), and its three rows can
 * change without the rest of the screen recomposing.
 */
class NextPickSettingsViewModel(
    private val featureFlags: FeatureFlags,
    private val preferences: NextPickPreferences,
) : ViewModel() {

    private val _state = MutableStateFlow(NextPickSettingsUiState())
    val state: StateFlow<NextPickSettingsUiState> = _state.asStateFlow()

    init {
        combine(featureFlags.completionPromptEnabled, preferences.sessionLength, preferences.resultMode) { prompt, length, mode ->
            NextPickSettingsUiState(completionPromptEnabled = prompt, sessionLength = length, resultMode = mode)
        }
            .onEach { next -> _state.value = next }
            .launchInReporting(viewModelScope)
    }

    fun onCompletionPromptToggled(enabled: Boolean) {
        viewModelScope.launchReporting { featureFlags.setCompletionPromptEnabled(enabled) }
    }

    fun onSessionLengthSelected(length: SessionLength) {
        viewModelScope.launchReporting { preferences.setSessionLength(length) }
    }

    fun onResultModeSelected(mode: ResultMode) {
        viewModelScope.launchReporting { preferences.setResultMode(mode) }
    }
}
