package com.codingpit.muviss.feature.settings.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.core.common.AppVersion
import com.codingpit.muviss.core.common.crash.launchInReporting
import com.codingpit.muviss.core.common.crash.launchReporting
import com.codingpit.muviss.core.common.crash.reportFailure
import com.codingpit.muviss.core.common.flags.FeatureFlags
import com.codingpit.muviss.core.common.flags.SnoozePeriod
import com.codingpit.muviss.core.common.flags.SnoozePlacement
import com.codingpit.muviss.core.common.flags.TriageControlScheme
import com.codingpit.muviss.core.designsystem.text.UiText
import com.codingpit.muviss.core.designsystem.text.toUiText
import com.codingpit.muviss.feature.settings.api.NotificationsChange
import com.codingpit.muviss.feature.settings.domain.AppSettings
import com.codingpit.muviss.feature.settings.domain.AppTheme
import com.codingpit.muviss.feature.settings.domain.DeleteAllDataUseCase
import com.codingpit.muviss.feature.settings.domain.SettingsActions
import com.codingpit.muviss.feature.settings.domain.SettingsObservations
import com.codingpit.muviss.feature.settings.ui.generated.resources.Res
import com.codingpit.muviss.feature.settings.ui.generated.resources.error_generic
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update

data class SettingsUiState(
    val loading: Boolean = true,
    val settings: AppSettings = AppSettings(),
    val error: UiText? = null,
    val appVersion: AppVersion = AppVersion(versionName = "", versionCode = 0),
    /** One-shot: non-null while an export is ready for the platform sharer to hand off; cleared by [SettingsViewModel.exportHandled]. */
    val exportJson: String? = null,
    /** The name the export is offered under, dated so successive backups do not overwrite each other (EPIC 29, #72). */
    val exportFileName: String = "",
    val exportError: UiText? = null,
    /** The Delete all data confirmation is open. One tap opens it; only its own button deletes. */
    val confirmingDeleteAll: Boolean = false,
    /** Everything was just deleted on this screen; it says so under the row until the screen goes. */
    val deletedAll: Boolean = false,
    /** Which drag scheme the triage deck uses (ADR 0010) — a per-device input preference, not a library setting. */
    val triageControlScheme: TriageControlScheme = TriageControlScheme.DEFAULT,
    val snoozePeriod: SnoozePeriod = SnoozePeriod.DEFAULT,
    val snoozePlacement: SnoozePlacement = SnoozePlacement.DEFAULT,
    /** The app-wide motion switch. While it is off every per-feature motion row below is inert. */
    val animationsEnabled: Boolean = true,
    val triageDeckAnimations: Boolean = true,
    /** Tick feedback on Progress and Detail (EPIC 52). Narrows [animationsEnabled] like the deck switch. */
    val tickAnimations: Boolean = true,
    /** The user just asked for notifications and the OS said no (EPIC 47); the blocked note explains the snapped-back switch. */
    val notificationsDenied: Boolean = false,
    /** Where the Feature Request row opens (ADR 0035), hidden fields included; null hides the row. */
    val featureRequestUrl: String? = null,
    /** Where the Bug Report row opens (ADR 0035, #449); null hides the row. */
    val bugReportUrl: String? = null,
)

/**
 * Drives the Settings screen: observes [AppSettings] (theme/locale/notifications),
 * exposes the mutators (grouped as [SettingsActions] to keep this constructor
 * short), and runs the data-export action, one-shot-delivering the resulting
 * JSON to the UI so it can hand it to the platform's share/save seam
 * ([rememberDataExporter]).
 */
class SettingsViewModel(
    private val observations: SettingsObservations,
    private val actions: SettingsActions,
    appVersion: AppVersion,
    private val featureFlags: FeatureFlags,
    private val deleteAllData: DeleteAllDataUseCase,
    private val clock: AppClock,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState(appVersion = appVersion))
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        featureFlags.triageSnoozePeriod
            .onEach { period -> _state.update { it.copy(snoozePeriod = period) } }
            .launchInReporting(viewModelScope)

        featureFlags.triageSnoozePlacement
            .onEach { placement -> _state.update { it.copy(snoozePlacement = placement) } }
            .launchInReporting(viewModelScope)

        featureFlags.triageControlScheme
            .onEach { scheme -> _state.update { it.copy(triageControlScheme = scheme) } }
            .launchInReporting(viewModelScope)

        featureFlags.animationsEnabled
            .onEach { enabled -> _state.update { it.copy(animationsEnabled = enabled) } }
            .launchInReporting(viewModelScope)

        // Kept as its own value rather than folded into the master: the screen
        // has to render this switch's real stored position even while the
        // master has it disabled, so turning the master back on restores what
        // the user last chose instead of silently re-enabling everything.
        featureFlags.triageDeckAnimations
            .onEach { enabled -> _state.update { it.copy(triageDeckAnimations = enabled) } }
            .launchInReporting(viewModelScope)

        featureFlags.tickAnimations
            .onEach { enabled -> _state.update { it.copy(tickAnimations = enabled) } }
            .launchInReporting(viewModelScope)

        observeAppSettings()
        observeFeedbackLinks()
    }

    private var settingsObservation: Job? = null
    private var feedbackObservation: Job? = null

    /** Re-subscribes after a failed load (EPIC 30, #73): a Flow that threw is finished and will not emit again on its own. */
    fun retry() {
        _state.update { it.copy(loading = true, error = null) }
        observeAppSettings()
        observeFeedbackLinks()
    }

    /**
     * The links are combined with [SettingsObservations.settings], so a settings failure ends them too:
     * they restart with the settings on [retry], or the rows would stay hidden until the view model is recreated.
     */
    private fun observeFeedbackLinks() {
        feedbackObservation?.cancel()
        feedbackObservation = observations.feedbackLinks()
            .onEach { links -> _state.update { it.copy(featureRequestUrl = links.featureRequest, bugReportUrl = links.bugReport) } }
            .launchInReporting(viewModelScope)
    }

    private fun observeAppSettings() {
        settingsObservation?.cancel()
        settingsObservation = observations.settings()
            .catch { e -> _state.update { it.copy(loading = false, error = e.toUiText(UiText.Resource(Res.string.error_generic))) } }
            .onEach { settings -> _state.update { it.copy(loading = false, settings = settings, error = null) } }
            .launchInReporting(viewModelScope)
    }

    fun onSnoozePeriodSelected(period: SnoozePeriod) {
        viewModelScope.launchReporting { featureFlags.setTriageSnoozePeriod(period) }
    }

    fun onSnoozePlacementSelected(placement: SnoozePlacement) {
        viewModelScope.launchReporting { featureFlags.setTriageSnoozePlacement(placement) }
    }

    fun onTriageControlSchemeSelected(scheme: TriageControlScheme) {
        viewModelScope.launchReporting { featureFlags.setTriageControlScheme(scheme) }
    }

    fun onAnimationsToggled(enabled: Boolean) {
        viewModelScope.launchReporting { featureFlags.setAnimationsEnabled(enabled) }
    }

    fun onTriageDeckAnimationsToggled(enabled: Boolean) {
        viewModelScope.launchReporting { featureFlags.setTriageDeckAnimations(enabled) }
    }

    fun onTickAnimationsToggled(enabled: Boolean) {
        viewModelScope.launchReporting { featureFlags.setTickAnimations(enabled) }
    }

    fun onThemeSelected(theme: AppTheme) {
        viewModelScope.launchReporting { actions.setTheme(theme) }
    }

    fun onLanguageSelected(language: String) {
        viewModelScope.launchReporting { actions.setLanguage(language) }
    }

    fun onRegionSelected(region: String) {
        viewModelScope.launchReporting { actions.setRegion(region) }
    }

    fun onNotificationsToggled(enabled: Boolean) {
        viewModelScope.launchReporting {
            val change = actions.setNotificationsEnabled(enabled)
            // A refusal stores nothing, so the switch snaps back; this is what lets the screen say why.
            _state.update { it.copy(notificationsDenied = change == NotificationsChange.DENIED) }
        }
    }

    fun onCrashReportsToggled(enabled: Boolean) {
        viewModelScope.launchReporting { actions.setCrashReportsEnabled(enabled) }
    }

    /** Device Backup (ADR 0034). Nothing is copied now: the OS includes or excludes the library at its next backup. */
    fun onDeviceBackupToggled(enabled: Boolean) {
        viewModelScope.launchReporting { actions.setDeviceBackupEnabled(enabled) }
    }

    fun exportData() {
        viewModelScope.launchReporting {
            runCatching { actions.exportData() }.reportFailure().fold(
                onSuccess = { json ->
                    _state.update { it.copy(exportJson = json, exportFileName = backupFileName(clock.nowEpochMs()), exportError = null) }
                },
                onFailure = { e -> _state.update { it.copy(exportError = e.toUiText(UiText.Resource(Res.string.error_generic))) } },
            )
        }
    }

    /** Called once the platform sharer has consumed [SettingsUiState.exportJson], to clear the one-shot value. */
    fun exportHandled() {
        _state.update { it.copy(exportJson = null) }
    }

    fun onDeleteAllRequested() {
        _state.update { it.copy(confirmingDeleteAll = true) }
    }

    fun onDeleteAllDismissed() {
        _state.update { it.copy(confirmingDeleteAll = false) }
    }

    /** The confirmation's own button. Every screen observes its tables, so the app empties itself as this commits. */
    fun onDeleteAllConfirmed() {
        _state.update { it.copy(confirmingDeleteAll = false) }
        viewModelScope.launchReporting {
            deleteAllData()
            _state.update { it.copy(deletedAll = true) }
        }
    }
}
