package com.codingpit.muviss.feature.search.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.codingpit.muviss.core.common.AppDispatchers
import com.codingpit.muviss.core.database.AppSettingsQueries
import com.codingpit.muviss.feature.search.domain.SearchOnboarding
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext

/**
 * [SearchOnboarding] on the singleton `appSettings` row, for the same reasons
 * triage's `AppSettingsTriagePreferences` reads it directly: it is this
 * feature's own onboarding state, and `appSettings` is `:core:database`
 * infrastructure rather than the settings feature's private property.
 */
class AppSettingsSearchOnboarding(
    private val queries: AppSettingsQueries,
    private val dispatchers: AppDispatchers,
) : SearchOnboarding {

    // A missing row reads as "seen": a read that loses a race with ensureRow
    // must not flash the intro at someone who dismissed it long ago.
    override fun observeIntroSeen(): Flow<Boolean> = queries.selectSettings()
        .asFlow()
        .mapToOneOrNull(dispatchers.io)
        .onStart { ensureRow() }
        .map { row -> row?.discoverIntroSeen ?: true }

    override suspend fun setIntroSeen() = withContext(dispatchers.io) {
        ensureRow()
        queries.updateDiscoverIntroSeen(true)
        Unit
    }

    private suspend fun ensureRow() = withContext(dispatchers.io) { queries.ensureRow() }
}
