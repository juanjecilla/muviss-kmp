package com.codingpit.muviss.feature.settings.data

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.core.common.AppDispatchers
import com.codingpit.muviss.core.common.AppVersion
import com.codingpit.muviss.core.database.AppSettingsQueries
import com.codingpit.muviss.core.database.CollectionEntryQueries
import com.codingpit.muviss.core.database.EpisodePlayQueries
import com.codingpit.muviss.core.database.EpisodeProgressQueries
import com.codingpit.muviss.core.database.MediaListQueries
import com.codingpit.muviss.core.database.MuvissDatabase
import com.codingpit.muviss.core.database.ProfileQueries
import com.codingpit.muviss.core.database.TriageDecisionQueries
import com.codingpit.muviss.core.database.TriageSnoozeQueries
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
import com.codingpit.muviss.core.database.EpisodePlay as EpisodePlayRow
import com.codingpit.muviss.core.database.EpisodeProgress as EpisodeProgressRow
import com.codingpit.muviss.core.database.ListEntry as ListEntryRow
import com.codingpit.muviss.core.database.MediaList as MediaListRow
import com.codingpit.muviss.core.database.Profile as ProfileRow
import com.codingpit.muviss.core.database.TriageDecision as TriageDecisionRow
import com.codingpit.muviss.core.database.TriageSnooze as TriageSnoozeRow

/**
 * SQLDelight-backed [SettingsRepository] over `AppSettings.sq`. The table is
 * a permanent singleton row (`id = 0`). [ensureRow] (`INSERT OR IGNORE`, so
 * idempotent) runs before every read via [observeSettings]'s `onStart` and
 * at the top of every mutation — see `SqlDelightProfileRepository`'s KDoc for
 * why this can no longer run once from `init` now that `generateAsync`
 * (EPIC 13) made it a `suspend fun`.
 *
 * [exportData] reaches into `collectionEntry`/`episodeProgress`/`episodePlay` directly
 * (both are `:core:database` infrastructure, not collection/progress's own —
 * no cross-feature dependency needed) rather than through those features'
 * `:api`, because the export is a literal table dump, not a domain view. It
 * uses `awaitAsList()` (from `async-extensions`) rather than
 * `executeAsList()` because the latter assumes a synchronous driver and
 * throws on web's async one — see `DatabaseFactory.web.kt`.
 */
class SqlDelightSettingsRepository(
    private val settingsQueries: AppSettingsQueries,
    private val exportQueries: ExportQueries,
    private val dispatchers: AppDispatchers,
    private val clock: AppClock,
    private val appVersion: AppVersion,
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

    override suspend fun setCrashReportsEnabled(enabled: Boolean) = withContext(dispatchers.io) {
        ensureRow()
        settingsQueries.updateCrashReportsEnabled(enabled)
        Unit
    }

    override suspend fun exportData(): String = withContext(dispatchers.io) {
        val export = MuvissDataExport(
            formatVersion = MuvissDataExport.CURRENT_FORMAT_VERSION,
            appVersion = appVersion.versionName,
            exportedAtEpochMs = clock.nowEpochMs(),
            collection = exportQueries.collection.selectAll().awaitAsList().map { it.toExport() },
            progress = exportQueries.progress.selectAll().awaitAsList().map { it.toExport() },
            triage = exportQueries.triage.selectAll().awaitAsList().map { it.toExport() },
            plays = exportQueries.plays.selectAll().awaitAsList().map { it.toExport() },
            snoozes = exportQueries.snoozes.selectAll().awaitAsList().map { it.toExport() },
            lists = exportQueries.lists.selectAllListsForExport().awaitAsList().map { it.toExport() },
            listEntries = exportQueries.lists.selectAllEntriesForExport().awaitAsList().map { it.toExport() },
            profile = exportQueries.profile.selectProfile().awaitAsOneOrNull()?.toExport(),
        )
        json.encodeToString(export)
    }

    private suspend fun ensureRow() = withContext(dispatchers.io) { settingsQueries.ensureRow() }

    private fun AppSettingsRow.toDomain(): AppSettings = AppSettings(
        theme = AppTheme.valueOf(theme),
        language = language,
        region = region,
        notificationsEnabled = notificationsEnabled,
        crashReportsEnabled = crashReportsEnabled,
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
        revisitWillingness = revisitWillingness,
        coWatchPinned = coWatchPinned,
        genres = genres,
        runtimeMinutes = runtimeMinutes?.toInt(),
        notificationsMuted = notificationsMuted,
        rating = rating?.toInt(),
        note = note,
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

    private fun EpisodePlayRow.toExport(): EpisodePlayExport = EpisodePlayExport(
        episodeId = episodeId,
        mediaId = mediaId,
        watchedAtEpochMs = watchedAtEpochMs,
    )

    private fun TriageSnoozeRow.toExport(): TriageSnoozeExport = TriageSnoozeExport(
        mediaId = mediaId,
        mediaType = mediaType,
        title = title,
        year = year?.toInt(),
        posterUrl = posterUrl,
        overview = overview,
        snoozedAtEpochMs = snoozedAtEpochMs,
        dueAtEpochDay = dueAtEpochDay,
        updatedAtEpochMs = updatedAtEpochMs,
    )

    private fun MediaListRow.toExport(): MediaListExport = MediaListExport(
        id = id,
        name = name,
        createdAtEpochMs = createdAtEpochMs,
        updatedAtEpochMs = updatedAtEpochMs,
    )

    private fun ListEntryRow.toExport(): ListEntryExport = ListEntryExport(
        listId = listId,
        mediaId = mediaId,
        addedAtEpochMs = addedAtEpochMs,
        updatedAtEpochMs = updatedAtEpochMs,
    )

    private fun ProfileRow.toExport(): ProfileExport = ProfileExport(displayName = displayName, avatarId = avatarId)
}

/**
 * The tables [SqlDelightSettingsRepository.exportData] dumps, read off one
 * [MuvissDatabase] so the repository's constructor stays inside detekt's
 * `LongParameterList` budget however many tables the export grows to.
 */
class ExportQueries(database: MuvissDatabase) {
    val collection: CollectionEntryQueries = database.collectionEntryQueries
    val progress: EpisodeProgressQueries = database.episodeProgressQueries
    val plays: EpisodePlayQueries = database.episodePlayQueries
    val triage: TriageDecisionQueries = database.triageDecisionQueries
    val snoozes: TriageSnoozeQueries = database.triageSnoozeQueries
    val lists: MediaListQueries = database.mediaListQueries
    val profile: ProfileQueries = database.profileQueries
}
