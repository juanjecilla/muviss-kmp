package com.codingpit.muviss.feature.settings.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.core.common.AppDispatchers
import com.codingpit.muviss.core.database.AppSettingsQueries
import com.codingpit.muviss.core.database.CollectionEntryQueries
import com.codingpit.muviss.core.database.EpisodeProgressQueries
import com.codingpit.muviss.feature.settings.domain.AppSettings
import com.codingpit.muviss.feature.settings.domain.AppTheme
import com.codingpit.muviss.feature.settings.domain.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import com.codingpit.muviss.core.database.AppSettings as AppSettingsRow
import com.codingpit.muviss.core.database.CollectionEntry as CollectionEntryRow
import com.codingpit.muviss.core.database.EpisodeProgress as EpisodeProgressRow

/**
 * SQLDelight-backed [SettingsRepository] over `AppSettings.sq`. The table is
 * a permanent singleton row (`id = 0`); [ensureRow] runs once at construction
 * so [observeSettings] never has to model a "no row yet" state beyond the
 * very first launch.
 *
 * [exportData] reaches into `collectionEntry`/`episodeProgress` directly
 * (both are `:core:database` infrastructure, not collection/progress's own —
 * no cross-feature dependency needed) rather than through those features'
 * `:api`, because the export is a literal table dump, not a domain view.
 */
class SqlDelightSettingsRepository(
    private val settingsQueries: AppSettingsQueries,
    private val collectionQueries: CollectionEntryQueries,
    private val progressQueries: EpisodeProgressQueries,
    private val dispatchers: AppDispatchers,
    private val clock: AppClock,
) : SettingsRepository {

    private val json = Json { prettyPrint = true }

    init {
        settingsQueries.ensureRow()
    }

    override fun observeSettings(): Flow<AppSettings> = settingsQueries.selectSettings()
        .asFlow()
        .mapToOneOrNull(dispatchers.io)
        .map { row -> row?.toDomain() ?: AppSettings() }

    override suspend fun setTheme(theme: AppTheme) = withContext(dispatchers.io) {
        settingsQueries.updateTheme(theme.name)
        Unit
    }

    override suspend fun setLanguage(language: String) = withContext(dispatchers.io) {
        settingsQueries.updateLanguage(language)
        Unit
    }

    override suspend fun setRegion(region: String) = withContext(dispatchers.io) {
        settingsQueries.updateRegion(region)
        Unit
    }

    override suspend fun setNotificationsEnabled(enabled: Boolean) = withContext(dispatchers.io) {
        settingsQueries.updateNotificationsEnabled(enabled)
        Unit
    }

    override suspend fun exportData(): String = withContext(dispatchers.io) {
        val export = MuvissDataExport(
            exportedAtEpochMs = clock.nowEpochMs(),
            collection = collectionQueries.selectAll().executeAsList().map { it.toExport() },
            progress = progressQueries.selectAll().executeAsList().map { it.toExport() },
        )
        json.encodeToString(export)
    }

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

    private fun EpisodeProgressRow.toExport(): EpisodeProgressExport = EpisodeProgressExport(
        episodeId = episodeId,
        mediaId = mediaId,
        seasonNumber = seasonNumber.toInt(),
        episodeNumber = episodeNumber.toInt(),
        seen = seen,
        updatedAtEpochMs = updatedAtEpochMs,
    )
}
