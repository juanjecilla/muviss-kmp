package com.codingpit.muviss.feature.settings.data

import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import com.codingpit.muviss.core.common.AppDispatchers
import com.codingpit.muviss.core.database.MuvissDatabase
import com.codingpit.muviss.feature.settings.domain.BackupRestorer
import com.codingpit.muviss.feature.settings.domain.BackupSummary
import com.codingpit.muviss.feature.settings.domain.ImportFileError
import com.codingpit.muviss.feature.settings.domain.ImportFileException
import com.codingpit.muviss.feature.settings.domain.RestoreResult
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Restores a [MuvissDataExport] of any version up to the current one (EPIC 29, #72).
 *
 * Every row merges by `updatedAtEpochMs`, last write wins — the rule sync
 * applies (ADR 0020) — so restoring last month's backup over a library in use
 * keeps every edit made since. A restored row is written dirty: it is a change
 * the sync server has not seen. Ties keep the local row, so restoring the same
 * file twice changes nothing the second time.
 *
 * After the rows, the same reconciliation a sync pull runs (`RemoteApplier`):
 * plays for un-ticked episodes are tombstoned, a tick with no play gets one,
 * and `airedEpisodes` is raised to the ticked count. A backup's ticks and its
 * snapshot can disagree once merged with newer local rows, and
 * `WatchProgress`'s `require(seen <= aired)` throws when the Library next
 * derives that title's status.
 *
 * All of it is one transaction: a file that fails halfway leaves nothing behind.
 */
class SqlDelightBackupRestorer(
    private val database: MuvissDatabase,
    private val dispatchers: AppDispatchers,
) : BackupRestorer {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun summarize(content: String): BackupSummary {
        val backup = decode(content)
        return BackupSummary(
            formatVersion = backup.formatVersion,
            exportedAtEpochMs = backup.exportedAtEpochMs,
            titleCount = backup.collection.size,
            episodeCount = backup.progress.count { it.seen },
            listCount = backup.lists.size,
        )
    }

    override suspend fun restore(content: String): RestoreResult {
        val backup = decode(content)
        return withContext(dispatchers.io) {
            database.transactionWithResult {
                val tally = Tally()
                backup.collection.forEach { tally.count(restoreCollectionEntry(it)) }
                backup.progress.forEach { tally.count(restoreProgress(it)) }
                backup.plays.forEach { tally.count(restorePlay(it)) }
                backup.triage.forEach { tally.count(restoreTriageDecision(it)) }
                backup.snoozes.forEach { tally.count(restoreSnooze(it)) }
                backup.lists.forEach { tally.count(restoreList(it)) }
                backup.listEntries.forEach { tally.count(restoreListEntry(it)) }
                backup.profile?.let { restoreProfile(it) }

                val mediaIds = buildSet {
                    backup.collection.mapTo(this) { it.mediaId }
                    backup.progress.mapTo(this) { it.mediaId }
                    backup.plays.mapTo(this) { it.mediaId }
                }
                mediaIds.chunked(SQL_ID_CHUNK).forEach { chunk ->
                    database.episodePlayQueries.tombstonePlaysForUnseen(chunk)
                    database.episodePlayQueries.insertMissingPlaysForSeen(chunk)
                    database.collectionEntryQueries.raiseAiredToSeen(chunk)
                }
                RestoreResult(restored = tally.restored, kept = tally.kept)
            }
        }
    }

    private fun decode(content: String): MuvissDataExport {
        // SerializationException extends IllegalArgumentException, which the
        // decoder also throws directly for a value of the wrong shape. The
        // parser's own message is not copy, so it stays out of the UI.
        val backup = try {
            json.decodeFromString<MuvissDataExport>(content)
        } catch (@Suppress("SwallowedException") e: IllegalArgumentException) {
            null
        }
        return when {
            backup == null -> throw ImportFileException(ImportFileError.UnreadableBackup)

            backup.formatVersion > MuvissDataExport.CURRENT_FORMAT_VERSION ->
                throw ImportFileException(ImportFileError.NewerBackupVersion)

            else -> backup
        }
    }

    /** True when [incoming] should replace a local row stamped [localUpdatedAt] (null: there is none). */
    private fun wins(incoming: Long, localUpdatedAt: Long?): Boolean = localUpdatedAt == null || incoming > localUpdatedAt

    private suspend fun restoreCollectionEntry(row: CollectionEntryExport): Boolean {
        val local = database.collectionEntryQueries.selectById(row.mediaId).awaitAsOneOrNull()
        if (!wins(row.updatedAtEpochMs, local?.updatedAtEpochMs)) return false
        database.collectionEntryQueries.upsert(
            mediaId = row.mediaId,
            mediaType = row.mediaType,
            title = row.title,
            posterUrl = row.posterUrl,
            releaseYear = row.releaseYear?.toLong(),
            productionStatus = row.productionStatus,
            totalEpisodes = row.totalEpisodes.toLong(),
            airedEpisodes = row.airedEpisodes.toLong(),
            favorite = row.favorite,
            genres = row.genres,
            runtimeMinutes = row.runtimeMinutes?.toLong(),
            addedAtEpochMs = row.addedAtEpochMs,
            updatedAtEpochMs = row.updatedAtEpochMs,
            isDirty = true,
            deleted = false,
            notificationsMuted = row.notificationsMuted,
            rating = row.rating?.toLong(),
            note = row.note,
            revisitWillingness = row.revisitWillingness,
            coWatchPinned = row.coWatchPinned,
        )
        return true
    }

    private suspend fun restoreProgress(row: EpisodeProgressExport): Boolean {
        val local = database.episodeProgressQueries.selectByEpisodeId(row.episodeId).awaitAsOneOrNull()
        if (!wins(row.updatedAtEpochMs, local?.updatedAtEpochMs)) return false
        database.episodeProgressQueries.upsert(
            episodeId = row.episodeId,
            mediaId = row.mediaId,
            seasonNumber = row.seasonNumber.toLong(),
            episodeNumber = row.episodeNumber.toLong(),
            seen = row.seen,
            updatedAtEpochMs = row.updatedAtEpochMs,
            isDirty = true,
        )
        return true
    }

    /** The id is derived (ADR 0013), so the same viewing restored twice lands on the same row. */
    private suspend fun restorePlay(row: EpisodePlayExport): Boolean {
        val id = "${row.episodeId}@${row.watchedAtEpochMs}"
        val stamp = row.updatedAtEpochMs ?: row.watchedAtEpochMs
        val local = database.episodePlayQueries.selectById(id).awaitAsOneOrNull()
        if (!wins(stamp, local?.updatedAtEpochMs)) return false
        database.episodePlayQueries.upsert(
            id = id,
            episodeId = row.episodeId,
            mediaId = row.mediaId,
            watchedAtEpochMs = row.watchedAtEpochMs,
            updatedAtEpochMs = stamp,
            isDirty = true,
            deleted = false,
        )
        return true
    }

    private suspend fun restoreTriageDecision(row: TriageDecisionExport): Boolean {
        val local = database.triageDecisionQueries.selectById(row.mediaId).awaitAsOneOrNull()
        if (!wins(row.updatedAtEpochMs, local?.updatedAtEpochMs)) return false
        database.triageDecisionQueries.upsert(
            mediaId = row.mediaId,
            mediaType = row.mediaType,
            verdict = row.verdict,
            title = row.title,
            posterUrl = row.posterUrl,
            decidedAtEpochMs = row.decidedAtEpochMs,
            resolved = row.resolved,
            updatedAtEpochMs = row.updatedAtEpochMs,
            isDirty = true,
            deleted = false,
        )
        return true
    }

    private suspend fun restoreSnooze(row: TriageSnoozeExport): Boolean {
        val local = database.triageSnoozeQueries.selectById(row.mediaId).awaitAsOneOrNull()
        if (!wins(row.updatedAtEpochMs, local?.updatedAtEpochMs)) return false
        database.triageSnoozeQueries.upsert(
            mediaId = row.mediaId,
            mediaType = row.mediaType,
            title = row.title,
            year = row.year?.toLong(),
            posterUrl = row.posterUrl,
            overview = row.overview,
            snoozedAtEpochMs = row.snoozedAtEpochMs,
            dueAtEpochDay = row.dueAtEpochDay,
            updatedAtEpochMs = row.updatedAtEpochMs,
            isDirty = true,
            deleted = false,
        )
        return true
    }

    private suspend fun restoreList(row: MediaListExport): Boolean {
        val local = database.mediaListQueries.selectListById(row.id).awaitAsOneOrNull()
        if (!wins(row.updatedAtEpochMs, local?.updatedAtEpochMs)) return false
        database.mediaListQueries.upsertList(
            id = row.id,
            name = row.name,
            createdAtEpochMs = row.createdAtEpochMs,
            updatedAtEpochMs = row.updatedAtEpochMs,
            isDirty = true,
            deleted = false,
        )
        return true
    }

    private suspend fun restoreListEntry(row: ListEntryExport): Boolean {
        val local = database.mediaListQueries.selectEntry(row.listId, row.mediaId).awaitAsOneOrNull()
        if (!wins(row.updatedAtEpochMs, local?.updatedAtEpochMs)) return false
        database.mediaListQueries.upsertEntry(
            listId = row.listId,
            mediaId = row.mediaId,
            addedAtEpochMs = row.addedAtEpochMs,
            updatedAtEpochMs = row.updatedAtEpochMs,
            isDirty = true,
            deleted = false,
        )
        return true
    }

    /**
     * The profile carries no timestamp, so it cannot merge: it is restored only
     * over an untouched one, never over a name the person has already chosen
     * on this device.
     */
    private suspend fun restoreProfile(profile: ProfileExport) {
        database.profileQueries.ensureRow()
        val local = database.profileQueries.selectProfile().awaitAsOneOrNull() ?: return
        if (local.displayName == DEFAULT_DISPLAY_NAME && local.avatarId == DEFAULT_AVATAR) {
            database.profileQueries.updateDisplayName(profile.displayName)
            database.profileQueries.updateAvatar(profile.avatarId)
        }
    }

    private class Tally {
        var restored = 0
            private set
        var kept = 0
            private set

        fun count(wasRestored: Boolean) {
            if (wasRestored) restored++ else kept++
        }
    }

    private companion object {
        /** Under SQLite's 999 bound-parameter ceiling on older Androids; `:core:sync`'s `SQL_ID_CHUNK`. */
        const val SQL_ID_CHUNK = 400

        // Profile.sq's `ensureRow` defaults.
        const val DEFAULT_DISPLAY_NAME = "You"
        const val DEFAULT_AVATAR = "indigo"
    }
}
