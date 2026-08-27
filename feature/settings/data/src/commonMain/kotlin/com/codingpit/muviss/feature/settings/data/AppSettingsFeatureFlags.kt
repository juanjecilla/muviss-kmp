package com.codingpit.muviss.feature.settings.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.codingpit.muviss.core.common.AppDispatchers
import com.codingpit.muviss.core.common.flags.FeatureFlags
import com.codingpit.muviss.core.common.flags.TriageControlScheme
import com.codingpit.muviss.core.database.AppSettingsQueries
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext
import com.codingpit.muviss.core.database.AppSettings as AppSettingsRow

/**
 * The local [FeatureFlags] source: flags are columns on the singleton
 * `appSettings` row, so they are per-device preferences the user sets in
 * Settings rather than anything served to them.
 *
 * Bound here, in the module that owns `appSettings`, even though most flags
 * today belong to triage — a flag registry is app-global by nature, and
 * settings is also the screen that has to render the toggles. A remote source
 * replaces this binding without any caller changing.
 *
 * Mirrors [SqlDelightSettingsRepository]'s `ensureRow` discipline: the row is
 * created on demand before every read and write, because `generateAsync`
 * (EPIC 13) made that a `suspend fun` and it can no longer run from `init`.
 */
class AppSettingsFeatureFlags(
    private val queries: AppSettingsQueries,
    private val dispatchers: AppDispatchers,
) : FeatureFlags {

    override val triageControlScheme: Flow<TriageControlScheme> = settings()
        .map { row -> TriageControlScheme.fromStored(row?.triageControlScheme) }

    override val animationsEnabled: Flow<Boolean> = settings().map { row -> row?.animationsEnabled ?: true }

    override val triageDeckAnimations: Flow<Boolean> = settings().map { row -> row?.triageDeckAnimations ?: true }

    override suspend fun setTriageControlScheme(scheme: TriageControlScheme) = withContext(dispatchers.io) {
        ensureRow()
        queries.updateTriageControlScheme(scheme.name)
        Unit
    }

    override suspend fun setAnimationsEnabled(enabled: Boolean) = withContext(dispatchers.io) {
        ensureRow()
        queries.updateAnimationsEnabled(enabled)
        Unit
    }

    override suspend fun setTriageDeckAnimations(enabled: Boolean) = withContext(dispatchers.io) {
        ensureRow()
        queries.updateTriageDeckAnimations(enabled)
        Unit
    }

    // Both motion flags default to true when the row is somehow missing: the
    // app has always animated, and a read that loses a race with `ensureRow`
    // must not look like the user asked for motion to stop.
    private fun settings(): Flow<AppSettingsRow?> = queries.selectSettings()
        .asFlow()
        .mapToOneOrNull(dispatchers.io)
        .onStart { ensureRow() }

    private suspend fun ensureRow() = withContext(dispatchers.io) { queries.ensureRow() }
}
