package com.codingpit.muviss.feature.settings.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.codingpit.muviss.core.common.AppVersion
import com.codingpit.muviss.core.common.flags.FeatureFlags
import com.codingpit.muviss.core.common.flags.TriageControlScheme
import com.codingpit.muviss.feature.settings.domain.AppSettings
import com.codingpit.muviss.feature.settings.domain.AppTheme
import com.codingpit.muviss.feature.settings.domain.ObserveSettingsUseCase
import com.codingpit.muviss.feature.settings.domain.SettingsActions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val loading: Boolean = true,
    val settings: AppSettings = AppSettings(),
    val error: String? = null,
    val appVersion: AppVersion = AppVersion(versionName = "", versionCode = 0),
    /** One-shot: non-null while an export is ready for the platform sharer to hand off; cleared by [SettingsViewModel.exportHandled]. */
    val exportJson: String? = null,
    val exportError: String? = null,
    /** Which drag scheme the triage deck uses (ADR 0010) — a per-device input preference, not a library setting. */
    val triageControlScheme: TriageControlScheme = TriageControlScheme.DEFAULT,
)

/**
 * Drives the Settings screen: observes [AppSettings] (theme/locale/notifications),
 * exposes the mutators (grouped as [SettingsActions] to keep this constructor
 * short), and runs the data-export action, one-shot-delivering the resulting
 * JSON to the UI so it can hand it to the platform's share/save seam
 * ([rememberDataExporter]).
 */
class SettingsViewModel(
    observeSettings: ObserveSettingsUseCase,
    private val actions: SettingsActions,
    appVersion: AppVersion,
    private val featureFlags: FeatureFlags,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState(appVersion = appVersion))
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        featureFlags.triageControlScheme
            .onEach { scheme -> _state.update { it.copy(triageControlScheme = scheme) } }
            .launchIn(viewModelScope)

        observeSettings()
            .catch { e -> _state.update { it.copy(loading = false, error = e.message ?: DEFAULT_ERROR) } }
            .onEach { settings -> _state.update { it.copy(loading = false, settings = settings, error = null) } }
            .launchIn(viewModelScope)
    }

    fun onTriageControlSchemeSelected(scheme: TriageControlScheme) {
        viewModelScope.launch { featureFlags.setTriageControlScheme(scheme) }
    }

    fun onThemeSelected(theme: AppTheme) {
        viewModelScope.launch { actions.setTheme(theme) }
    }

    fun onLanguageSelected(language: String) {
        viewModelScope.launch { actions.setLanguage(language) }
    }

    fun onRegionSelected(region: String) {
        viewModelScope.launch { actions.setRegion(region) }
    }

    fun onNotificationsToggled(enabled: Boolean) {
        viewModelScope.launch { actions.setNotificationsEnabled(enabled) }
    }

    fun exportData() {
        viewModelScope.launch {
            runCatching { actions.exportData() }.fold(
                onSuccess = { json -> _state.update { it.copy(exportJson = json, exportError = null) } },
                onFailure = { e -> _state.update { it.copy(exportError = e.message ?: DEFAULT_ERROR) } },
            )
        }
    }

    /** Called once the platform sharer has consumed [SettingsUiState.exportJson], to clear the one-shot value. */
    fun exportHandled() {
        _state.update { it.copy(exportJson = null) }
    }

    private companion object {
        const val DEFAULT_ERROR = "Something went wrong"
    }
}
