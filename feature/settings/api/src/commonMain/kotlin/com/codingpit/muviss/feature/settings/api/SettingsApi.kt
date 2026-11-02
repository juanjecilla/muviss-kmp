package com.codingpit.muviss.feature.settings.api

import com.codingpit.muviss.models.GenreKey
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.MutedGenres
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
     * What suggestion surfaces (triage deck, Discover, For you, More like this, and search when opted in)
     * leave out. Emits again whenever the setting or the session's "show muted" chip changes.
     */
    fun observeMuting(): Flow<GenreMuting>

    /** The chip on Triage and Discover: shows muted titles for this session only. Resets on relaunch. */
    fun setShowMutedThisSession(show: Boolean)

    /** Mutes or unmutes one genre, remembering [name] for the Settings list. The Onboarding page's door. */
    suspend fun setGenreMuted(key: GenreKey, name: String, muted: Boolean)

    /**
     * Up to [limit] popular posters for [key]'s genre, for Onboarding's "what would this hide" strip.
     * Best-effort: empty offline or when the lookup fails, and callers must read empty as "no examples",
     * never as an error.
     */
    suspend fun examplesFor(key: GenreKey, limit: Int = 3): List<MediaSummary>

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

/**
 * The muting a surface should apply right now (Muted Genres, ADR 0031).
 *
 * @property configured what Settings says is muted, whatever this session is showing.
 * @property showMuted the session chip: true lifts muting on suggestion surfaces until relaunch.
 * @property inSearch whether typed search is muted too.
 */
data class GenreMuting(
    val configured: MutedGenres = MutedGenres.NONE,
    val showMuted: Boolean = false,
    val inSearch: Boolean = false,
) {
    /** What suggestion surfaces apply. */
    val effective: MutedGenres get() = if (showMuted) MutedGenres.NONE else configured

    /** What typed search applies: nothing unless the user opted in. */
    val forSearch: MutedGenres get() = if (inSearch) effective else MutedGenres.NONE

    /** Whether the chip has anything to toggle. */
    val hasMuted: Boolean get() = configured.keys.isNotEmpty()
}
