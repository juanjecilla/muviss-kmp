package com.codingpit.muviss.feature.settings.data

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.core.common.AppDispatchers
import com.codingpit.muviss.core.database.AppSettingsQueries
import com.codingpit.muviss.core.database.CollectionEntryQueries
import com.codingpit.muviss.core.database.EpisodeProgressQueries
import com.codingpit.muviss.core.database.TriageDecisionQueries
import com.codingpit.muviss.feature.settings.domain.AppSettings
import com.codingpit.muviss.feature.settings.domain.AppTheme
import com.codingpit.muviss.feature.settings.domain.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import com.codingpit.muviss.core.database.AppSettings as AppSettingsRow
import com.codingpit.muviss.core.database.CollectionEntry as CollectionEntryRow
import com.codingpit.muviss.core.database.EpisodeProgress as EpisodeProgressRow
import com.codingpit.muviss.core.database.TriageDecision as TriageDecisionRow

/**
 * SQLDelight-backed [SettingsRepository] over `AppSettings.sq`. The table is
 * a permanent singleton row (`id = 0`). [ensureRow] (`INSERT OR IGNORE`, so
 * idempotent) runs before every read via [observeSettings]'s `onStart` and
 * at the top of every mutation — see `SqlDelightProfileRepository`'s KDoc for
 * why this can no longer run once from `init` now that `generateAsync`
 * (EPIC 13) made it a `suspend fun`.
 *
 * [exportData] reaches into `collectionEntry`/`episodeProgress` directly
 * (both are `:core:database` infrastructure, not collection/progress's own —
 * no cross-feature dependency needed) rather than through those features'
 * `:api`, because the export is a literal table dump, not a domain view. It
 * uses `awaitAsList()` (from `async-extensions`) rather than
 * `executeAsList()` because the latter assumes a synchronous driver and
 * throws on web's async one — see `DatabaseFactory.web.kt`.
 */
class SqlDelightSettingsRepository(
    private val settingsQueries: AppSettingsQueries,
    private val collectionQueries: CollectionEntryQueries,
    private val progressQueries: EpisodeProgressQueries,
    private val triageQueries: TriageDecisionQueries,
    private val dispatchers: AppDispatchers,
    private val clock: AppClock,
) : SettingsRepository {

    private val json = Json { prettyPrint = true }

    override fun observeSettings(): Flow<AppSettings> = settingsQueries.selectSettings()
        .asFlow()
        .mapToOneOrNull(dispatchers.io)
        .onStart { ensureRow() }
        .map { row -> row?.toDomain() ?: AppSettings() }

    override suspend fun setTheme(theme: AppTheme) = withContext(dispatchers.io) {
        ensureRow()
        settingsQueries.updateTheme(theme.name)
        Unit
    }

    override suspend fun setLanguage(language: String) = withContext(dispatchers.io) {
        ensureRow()
        settingsQueries.updateLanguage(language)
        Unit
    }

    override suspend fun setRegion(region: String) = withContext(dispatchers.io) {
        ensureRow()
        settingsQueries.updateRegion(region)
        Unit
    }

    override suspend fun setNotificationsEnabled(enabled: Boolean) = withContext(dispatchers.io) {
        ensureRow()
        settingsQueries.updateNotificationsEnabled(enabled)
        Unit
    }

    override suspend fun exportData(): String = withContext(dispatchers.io) {
        val export = MuvissDataExport(
            exportedAtEpochMs = clock.nowEpochMs(),
            collection = collectionQueries.selectAll().awaitAsList().map { it.toExport() },
            progress = progressQueries.selectAll().awaitAsList().map { it.toExport() },
            triage = triageQueries.selectAll().awaitAsList().map { it.toExport() },
        )
        json.encodeToString(export)
    }

    private suspend fun ensureRow() = withContext(dispatchers.io) { settingsQueries.ensureRow() }

    private fun AppSettingsRow.toDomain(): AppSettings = AppSettings(
        theme = AppTheme.valueOf(theme),
        language = language,
        region = region,
        notificationsEnabled = notificationsEnabled,
    )

    private fun CollectionEntryRow.toExport(): CollectionEntryExport = CollectionEntryExport(
        mediaId = mediaId,
        mediaType = mediaType,
        title = title,
        posterUrl = posterUrl,
        releaseYear = releaseYear?.toInt(),
        productionStatus = productionStatus,
        totalEpisodes = totalEpisodes.toInt(),
        airedEpisodes = airedEpisodes.toInt(),
        favorite = favorite,
        addedAtEpochMs = addedAtEpochMs,
        updatedAtEpochMs = updatedAtEpochMs,
    )

    private fun TriageDecisionRow.toExport(): TriageDecisionExport = TriageDecisionExport(
        mediaId = mediaId,
        mediaType = mediaType,
        verdict = verdict,
        title = title,
        posterUrl = posterUrl,
        decidedAtEpochMs = decidedAtEpochMs,
        updatedAtEpochMs = updatedAtEpochMs,
    )

    private fun EpisodeProgressRow.toExport(): EpisodeProgressExport = EpisodeProgressExport(
        episodeId = episodeId,
        mediaId = mediaId,
        seasonNumber = seasonNumber.toInt(),
        episodeNumber = episodeNumber.toInt(),
        seen = seen,
        updatedAtEpochMs = updatedAtEpochMs,
    )
}
