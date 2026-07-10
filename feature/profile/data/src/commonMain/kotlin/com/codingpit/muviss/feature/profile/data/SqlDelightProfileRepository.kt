package com.codingpit.muviss.feature.profile.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.codingpit.muviss.core.common.AppDispatchers
import com.codingpit.muviss.core.database.ProfileQueries
import com.codingpit.muviss.feature.profile.domain.LocalProfile
import com.codingpit.muviss.feature.profile.domain.ProfileRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import com.codingpit.muviss.core.database.Profile as ProfileRow

/**
 * SQLDelight-backed [ProfileRepository] over `Profile.sq`. The table is a
 * permanent singleton row (`id = 0`); [ensureRow] runs once at construction so
 * [observeProfile] never has to model a "no row yet" state beyond the very
 * first launch — mirrors settings' `SqlDelightSettingsRepository`.
 */
class SqlDelightProfileRepository(
    private val queries: ProfileQueries,
    private val dispatchers: AppDispatchers,
) : ProfileRepository {

    init {
        queries.ensureRow()
    }

    override fun observeProfile(): Flow<LocalProfile> = queries.selectProfile()
        .asFlow()
        .mapToOneOrNull(dispatchers.io)
        .map { row -> row?.toDomain() ?: LocalProfile.DEFAULT }

    override suspend fun setDisplayName(displayName: String) = withContext(dispatchers.io) {
        queries.updateDisplayName(displayName)
        Unit
    }

    override suspend fun setAvatar(avatarId: String) = withContext(dispatchers.io) {
        queries.updateAvatar(avatarId)
        Unit
    }

    private fun ProfileRow.toDomain(): LocalProfile = LocalProfile(displayName = displayName, avatarId = avatarId)
}
