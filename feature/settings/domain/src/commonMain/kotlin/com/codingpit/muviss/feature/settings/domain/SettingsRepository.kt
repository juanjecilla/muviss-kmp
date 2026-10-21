package com.codingpit.muviss.feature.settings.domain

import com.codingpit.muviss.models.GenreKey
import kotlinx.coroutines.flow.Flow

/**
 * Domain-owned contract for the app's settings. The implementation lives in
 * the data layer over SQLDelight (`AppSettings.sq`) and also backs
 * `:core:network`'s `MetadataLocale` (via `SettingsLocaleSync`, so TMDB
 * requests pick up [AppSettings.language]/[AppSettings.region] live) and the
 * data-export feature (which reads the collection/progress tables directly
 * alongside this one).
 */
interface SettingsRepository {
    /** The current settings, recomputed whenever any of them change. */
    fun observeSettings(): Flow<AppSettings>

    suspend fun setTheme(theme: AppTheme)

    /** [language] should be one of [SupportedLocales.languages]' codes. */
    suspend fun setLanguage(language: String)

    /** [region] should be one of [SupportedLocales.regions]' codes. */
    suspend fun setRegion(region: String)

    suspend fun setNotificationsEnabled(enabled: Boolean)

    /** The "Send crash reports" switch. Read before Koin starts by `CrashReportsConsent`, so it lives in `appSettings`. */
    suspend fun setCrashReportsEnabled(enabled: Boolean)

    /** Mutes or unmutes one genre. [name] is kept so the list can be shown offline. Idempotent. */
    suspend fun setGenreMuted(key: GenreKey, name: String, muted: Boolean)

    /**
     * Device Backup (ADR 0034): whether the OS may back this device's library up. Writes the column and then
     * applies the matching file exclusion through `DeviceBackupExclusion`, so the two cannot disagree.
     */
    suspend fun setDeviceBackupEnabled(enabled: Boolean)

    /** Whether typed search also hides muted genres. */
    suspend fun setMutedGenresInSearch(enabled: Boolean)

    /** A JSON dump of the saved library (`collectionEntry`) + watch progress (`episodeProgress`) tables. */
    suspend fun exportData(): String
}
