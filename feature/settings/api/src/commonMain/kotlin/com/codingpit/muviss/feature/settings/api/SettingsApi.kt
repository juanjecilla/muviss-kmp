package com.codingpit.muviss.feature.settings.api

import kotlinx.coroutines.flow.Flow

/**
 * Public contract exposed by the settings feature to other features and to
 * the app shell (`MuvissApp`). Peers depend on this module only, never on
 * settings' domain/data/ui.
 */
interface SettingsApi {
    /** The theme the app shell should render (see [ThemeMode]); `MuvissApp` applies it to `MuvissTheme`. */
    fun observeThemeMode(): Flow<ThemeMode>

    /** The global notifications toggle. Persisted only today — EPIC 5 (notifications) reads this to gate itself. */
    fun observeNotificationsEnabled(): Flow<Boolean>

    /**
     * The "Send crash reports" switch (default on). The app shell forwards it to
     * the crash reporter so the Settings toggle takes effect at once; the same
     * value is read *before* Koin exists at startup, straight from `appSettings`.
     */
    fun observeCrashReportsEnabled(): Flow<Boolean>
}

/**
 * The theme choices Settings offers, mirrored here (rather than exposing the
 * domain's `AppTheme` directly) so peers depend on a stable, feature-owned
 * type — the same pattern `CollectionApi`'s `CollectionSummary` follows.
 */
enum class ThemeMode {
    LIGHT,
    DARK,
    SYSTEM,
}
