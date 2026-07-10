package com.codingpit.muviss.feature.profile.domain

import kotlinx.coroutines.flow.Flow

/** Observes the local profile identity (display name + avatar). */
class ObserveProfileUseCase(private val repository: ProfileRepository) {
    operator fun invoke(): Flow<LocalProfile> = repository.observeProfile()
}

/** Renames the local profile; blank names are rejected by the ViewModel before this is called. */
class SetDisplayNameUseCase(private val repository: ProfileRepository) {
    suspend operator fun invoke(displayName: String) = repository.setDisplayName(displayName)
}

/** Switches the avatar preset (see [AvatarPresets]). */
class SetAvatarUseCase(private val repository: ProfileRepository) {
    suspend operator fun invoke(avatarId: String) = repository.setAvatar(avatarId)
}

/**
 * Groups the profile identity's mutators so a consumer like `ProfileViewModel`
 * takes one constructor parameter instead of two — mirrors settings'
 * `SettingsActions`.
 */
class ProfileActions(
    private val setDisplayNameUseCase: SetDisplayNameUseCase,
    private val setAvatarUseCase: SetAvatarUseCase,
) {
    suspend fun setDisplayName(displayName: String) = setDisplayNameUseCase(displayName)
    suspend fun setAvatar(avatarId: String) = setAvatarUseCase(avatarId)
}
