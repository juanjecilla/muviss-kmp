package com.codingpit.muviss.feature.settings.domain

import com.codingpit.muviss.core.common.notifications.NotificationPermissionRequester
import com.codingpit.muviss.feature.settings.api.NotificationsChange
import com.codingpit.muviss.models.GenreKey
import kotlinx.coroutines.flow.Flow

/** Observes the current app settings; the source the theme, locale, and notification UI reads from. */
class ObserveSettingsUseCase(private val repository: SettingsRepository) {
    operator fun invoke(): Flow<AppSettings> = repository.observeSettings()
}

/** Switches the app theme (light/dark/system); `MuvissTheme` applies it immediately. */
class SetThemeUseCase(private val repository: SettingsRepository) {
    suspend operator fun invoke(theme: AppTheme) = repository.setTheme(theme)
}

/** Switches the TMDB request language (e.g. "es-ES"); backs [com.codingpit.muviss.core.network.MetadataLocale]. */
class SetLanguageUseCase(private val repository: SettingsRepository) {
    suspend operator fun invoke(language: String) = repository.setLanguage(language)
}

/** Switches the watch-provider region (ISO 3166-1 alpha-2); backs [com.codingpit.muviss.core.network.MetadataLocale]. */
class SetRegionUseCase(private val repository: SettingsRepository) {
    suspend operator fun invoke(region: String) = repository.setRegion(region)
}

/** Flips the global notifications toggle; persisted only — EPIC 5 wires the actual notifications. */
class SetNotificationsEnabledUseCase(
    private val repository: SettingsRepository,
    private val permission: NotificationPermissionRequester,
) {
    suspend operator fun invoke(enabled: Boolean): NotificationsChange {
        if (!enabled) {
            repository.setNotificationsEnabled(false)
            return NotificationsChange.DISABLED
        }
        // Asked before anything is stored: a switch that reads "on" while the OS blocks the
        // alert is the lie the Settings note used to have to apologise for.
        if (!permission.request()) return NotificationsChange.DENIED
        repository.setNotificationsEnabled(true)
        return NotificationsChange.ENABLED
    }
}

/** Flips the "Send crash reports" switch; the app shell forwards it to the reporter live. */
class SetCrashReportsEnabledUseCase(private val repository: SettingsRepository) {
    suspend operator fun invoke(enabled: Boolean) = repository.setCrashReportsEnabled(enabled)
}

/** Turns Device Backup on or off (ADR 0034). The repository applies the OS-side exclusion with the stored value. */
class SetDeviceBackupEnabledUseCase(private val repository: SettingsRepository) {
    suspend operator fun invoke(enabled: Boolean) = repository.setDeviceBackupEnabled(enabled)
}

/** The two Muted Genres writes (ADR 0031), grouped so [SettingsActions] stays inside detekt's parameter budget. */
class MutedGenresActions(private val repository: SettingsRepository) {
    /** Mutes or unmutes one genre. */
    suspend fun setGenreMuted(key: GenreKey, name: String, muted: Boolean) = repository.setGenreMuted(key, name, muted)

    /** Whether typed search also hides muted genres. */
    suspend fun setInSearch(enabled: Boolean) = repository.setMutedGenresInSearch(enabled)
}

/** Dumps the saved library + watch progress as a single JSON document, for the About screen's export action. */
class ExportDataUseCase(private val repository: SettingsRepository) {
    suspend operator fun invoke(): String = repository.exportData()
}

/**
 * Groups every settings *mutator* (everything but [ObserveSettingsUseCase],
 * which is a continuous read, not a write) so a consumer like
 * `SettingsViewModel` takes one constructor parameter instead of six —
 * keeps each use case its own testable class while avoiding a long
 * parameter list at the composition edge.
 */
@Suppress("LongParameterList") // the seventh groups Muted Genres (ADR 0031); this class exists to hold the list
class SettingsActions(
    private val setThemeUseCase: SetThemeUseCase,
    private val setLanguageUseCase: SetLanguageUseCase,
    private val setRegionUseCase: SetRegionUseCase,
    private val setNotificationsEnabledUseCase: SetNotificationsEnabledUseCase,
    private val setCrashReportsEnabledUseCase: SetCrashReportsEnabledUseCase,
    private val mutedGenres: MutedGenresActions,
    private val exportDataUseCase: ExportDataUseCase,
    private val setDeviceBackupEnabledUseCase: SetDeviceBackupEnabledUseCase,
) {
    suspend fun setTheme(theme: AppTheme) = setThemeUseCase(theme)
    suspend fun setLanguage(language: String) = setLanguageUseCase(language)
    suspend fun setRegion(region: String) = setRegionUseCase(region)
    suspend fun setNotificationsEnabled(enabled: Boolean): NotificationsChange = setNotificationsEnabledUseCase(enabled)
    suspend fun setCrashReportsEnabled(enabled: Boolean) = setCrashReportsEnabledUseCase(enabled)
    suspend fun setDeviceBackupEnabled(enabled: Boolean) = setDeviceBackupEnabledUseCase(enabled)
    suspend fun setGenreMuted(key: GenreKey, name: String, muted: Boolean) = mutedGenres.setGenreMuted(key, name, muted)
    suspend fun setMutedGenresInSearch(enabled: Boolean) = mutedGenres.setInSearch(enabled)
    suspend fun exportData(): String = exportDataUseCase()
}
