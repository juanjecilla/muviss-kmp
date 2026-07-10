package com.codingpit.muviss.feature.profile.data

import com.codingpit.muviss.feature.profile.api.ProfileApi
import com.codingpit.muviss.feature.profile.api.ProfileSummary
import com.codingpit.muviss.feature.profile.domain.ObserveProfileUseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Bridges the profile feature's use cases to its public [ProfileApi]. */
internal class DefaultProfileApi(
    private val observeProfile: ObserveProfileUseCase,
) : ProfileApi {

    override fun observeProfile(): Flow<ProfileSummary> = observeProfile()
        .map { profile -> ProfileSummary(profile.displayName, profile.avatarId) }
}
