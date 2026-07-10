package com.codingpit.muviss.feature.profile.domain

import kotlinx.coroutines.flow.Flow

/**
 * Domain-owned contract for the local profile identity. The implementation
 * lives in the data layer over SQLDelight (`Profile.sq`), mirroring settings'
 * `SettingsRepository` singleton-row pattern.
 */
interface ProfileRepository {
    /** The current profile; always has a value once the singleton row exists (see `ensureRow`). */
    fun observeProfile(): Flow<LocalProfile>

    suspend fun setDisplayName(displayName: String)

    suspend fun setAvatar(avatarId: String)
}
