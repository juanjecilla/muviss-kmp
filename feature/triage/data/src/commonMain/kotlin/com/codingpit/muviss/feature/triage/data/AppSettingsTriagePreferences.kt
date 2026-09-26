package com.codingpit.muviss.feature.triage.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.codingpit.muviss.core.common.AppDispatchers
import com.codingpit.muviss.core.database.AppSettingsQueries
import com.codingpit.muviss.feature.triage.domain.TriagePreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext

/**
 * "Has the onboarding been shown here?" — a per-device fact, so it lives on the
 * singleton `appSettings` row alongside theme and locale, and is excluded from
 * sync for the same reason `notificationsMuted` is (ADR 0009).
 *
 * Triage reads that row directly rather than through settings' `:api`: this is
 * triage's own onboarding state, and pushing it into `SettingsApi` would make
 * the settings feature depend on triage's vocabulary. `appSettings` is
 * `:core:database` infrastructure, not settings' private property — the same
 * argument settings' own `exportData` makes for reading `collectionEntry`.
 *
 * `ensureRow` (`INSERT OR IGNORE`, idempotent) runs before every read and
 * write, matching `SqlDelightSettingsRepository`.
 */
class AppSettingsTriagePreferences(
    private val queries: AppSettingsQueries,
    private val dispatchers: AppDispatchers,
) : TriagePreferences {

    override fun observeTutorialSeen(): Flow<Boolean> = queries.selectSettings()
        .asFlow()
        .mapToOneOrNull(dispatchers.io)
        .onStart { ensureRow() }
        .map { row -> row?.triageTutorialSeen ?: false }

    override suspend fun setTutorialSeen(seen: Boolean) = withContext(dispatchers.io) {
        ensureRow()
        queries.updateTriageTutorialSeen(seen)
        Unit
    }

    override fun observeSnoozeHintSeen(): Flow<Boolean> = queries.selectSettings()
        .asFlow()
        .mapToOneOrNull(dispatchers.io)
        .onStart { ensureRow() }
        // False when the row is missing, so a read that loses a race with
        // `ensureRow` shows the hint again rather than swallowing it — the
        // failure that matters here is never showing it at all.
        .map { row -> row?.triageSnoozeHintSeen ?: false }

    override suspend fun setSnoozeHintSeen(seen: Boolean) = withContext(dispatchers.io) {
        ensureRow()
        queries.updateTriageSnoozeHintSeen(seen)
        Unit
    }

    private suspend fun ensureRow() = withContext(dispatchers.io) { queries.ensureRow() }
}
