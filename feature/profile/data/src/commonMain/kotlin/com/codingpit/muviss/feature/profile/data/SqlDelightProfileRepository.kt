package com.codingpit.muviss.feature.profile.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.codingpit.muviss.core.common.AppDispatchers
import com.codingpit.muviss.core.database.ProfileQueries
import com.codingpit.muviss.feature.profile.domain.LocalProfile
import com.codingpit.muviss.feature.profile.domain.ProfileRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext
import com.codingpit.muviss.core.database.Profile as ProfileRow

/**
 * SQLDelight-backed [ProfileRepository] over `Profile.sq`. The table is a
 * permanent singleton row (`id = 0`). [ensureRow] (`INSERT OR IGNORE`, so
 * idempotent) runs before every read via [observeProfile]'s `onStart` and at
 * the top of every mutation, so there is never a "no row yet" state to model
 * beyond the very first call, regardless of which one happens first — since
 * `generateAsync` (EPIC 13, `core/database`'s `build.gradle.kts`) turned
 * `ensureRow` into a `suspend fun`, it can no longer run once from `init` the
 * way it used to (`init` blocks aren't suspend). Mirrors settings'
 * `SqlDelightSettingsRepository`.
 */
class SqlDelightProfileRepository(
    private val queries: ProfileQueries,
    private val dispatchers: AppDispatchers,
) : ProfileRepository {

    override fun observeProfile(): Flow<LocalProfile> = queries.selectProfile()
        .asFlow()
        .mapToOneOrNull(dispatchers.io)
        .onStart { ensureRow() }
        .map { row -> row?.toDomain() ?: LocalProfile.DEFAULT }

    override suspend fun setDisplayName(displayName: String) = withContext(dispatchers.io) {
        ensureRow()
        queries.updateDisplayName(displayName)
        Unit
    }

    override suspend fun setAvatar(avatarId: String) = withContext(dispatchers.io) {
        ensureRow()
        queries.updateAvatar(avatarId)
        Unit
    }

    private suspend fun ensureRow() = withContext(dispatchers.io) { queries.ensureRow() }

    private fun ProfileRow.toDomain(): LocalProfile = LocalProfile(displayName = displayName, avatarId = avatarId)
}
