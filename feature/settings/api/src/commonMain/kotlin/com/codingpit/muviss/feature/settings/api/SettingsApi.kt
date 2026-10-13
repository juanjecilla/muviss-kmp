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

    /**
     * Turns new-episode notifications on or off. Turning them **on** asks the OS first
     * (EPIC 47) and stores nothing if it says no, so the switch never claims an alert
     * that cannot arrive. The one door every surface uses — Settings, Onboarding and
     * the Notification Nudge — so none of them can forget to ask.
     */
    suspend fun setNotificationsEnabled(enabled: Boolean): NotificationsChange
}

/** What [SettingsApi.setNotificationsEnabled] ended up doing. */
enum class NotificationsChange {
    ENABLED,
    DISABLED,

    /** The user asked for them and the OS said no. Nothing was stored; the setting is as it was. */
    DENIED,
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
