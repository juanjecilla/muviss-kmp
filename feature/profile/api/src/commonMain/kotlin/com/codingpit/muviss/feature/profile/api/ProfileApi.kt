package com.codingpit.muviss.feature.profile.api

import kotlinx.coroutines.flow.Flow

/**
 * Public contract exposed by the profile feature to other features. Peers
 * depend on this module only, never on the feature's domain/data/ui — e.g. a
 * future greeting in the app shell, or EPIC 9's sync engine reading the local
 * identity it attaches a remote account to.
 */
interface ProfileApi {
    /** The current local identity (display name + avatar preset id). */
    fun observeProfile(): Flow<ProfileSummary>
}

/**
 * The minimal identity info a peer needs, mirrored here (rather than exposing
 * the domain's `LocalProfile` directly) so peers depend on a stable,
 * feature-owned type — the same pattern `SettingsApi`'s `ThemeMode` follows.
 */
data class ProfileSummary(
    val displayName: String,
    val avatarId: String,
)
