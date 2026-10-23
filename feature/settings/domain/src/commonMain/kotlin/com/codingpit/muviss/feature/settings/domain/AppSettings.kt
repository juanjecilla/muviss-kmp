package com.codingpit.muviss.feature.settings.domain

/** The full set of user-configurable app settings (see [SettingsRepository]). */
data class AppSettings(
    val theme: AppTheme = AppTheme.SYSTEM,
    val language: String = SupportedLocales.DEFAULT_LANGUAGE,
    val region: String = SupportedLocales.DEFAULT_REGION,
    val notificationsEnabled: Boolean = false,
    /** "Send crash reports". On by default; a per-device preference, never synced. */
    val crashReportsEnabled: Boolean = true,
    /** Genres left out of suggestions (Muted Genres, ADR 0031). Talk, News and Reality until the user says otherwise. */
    val mutedGenres: List<MutedGenreEntry> = MutedGenresCodec.DEFAULTS,
    /** Extends muting to typed search. Off: a search for a muted show still finds it. */
    val mutedGenresInSearch: Boolean = false,
    /**
     * Device Backup (ADR 0034). Off by default: when on, the OS may include this device's library in its own
     * backup (iCloud, Google's backup, a device-to-device transfer); when off, the library is excluded. Per-device,
     * never synced, never exported.
     */
    val deviceBackupEnabled: Boolean = false,
)
