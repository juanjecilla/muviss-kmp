package com.codingpit.muviss.feature.settings.domain

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
class SetNotificationsEnabledUseCase(private val repository: SettingsRepository) {
    suspend operator fun invoke(enabled: Boolean) = repository.setNotificationsEnabled(enabled)
}

/** Dumps the saved library + watch progress as a single JSON document, for the About screen's export action. */
class ExportDataUseCase(private val repository: SettingsRepository) {
    suspend operator fun invoke(): String = repository.exportData()
}

/**
 * Groups every settings *mutator* (everything but [ObserveSettingsUseCase],
 * which is a continuous read, not a write) so a consumer like
 * `SettingsViewModel` takes one constructor parameter instead of five —
 * keeps each use case its own testable class while avoiding a long
 * parameter list at the composition edge.
 */
class SettingsActions(
    private val setThemeUseCase: SetThemeUseCase,
    private val setLanguageUseCase: SetLanguageUseCase,
    private val setRegionUseCase: SetRegionUseCase,
    private val setNotificationsEnabledUseCase: SetNotificationsEnabledUseCase,
    private val exportDataUseCase: ExportDataUseCase,
) {
    suspend fun setTheme(theme: AppTheme) = setThemeUseCase(theme)
    suspend fun setLanguage(language: String) = setLanguageUseCase(language)
    suspend fun setRegion(region: String) = setRegionUseCase(region)
    suspend fun setNotificationsEnabled(enabled: Boolean) = setNotificationsEnabledUseCase(enabled)
    suspend fun exportData(): String = exportDataUseCase()
}
