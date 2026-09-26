package com.codingpit.muviss.core.sync

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import com.codingpit.muviss.core.database.MuvissDatabase
import com.codingpit.muviss.core.database.CollectionEntry as CollectionEntryRow
import com.codingpit.muviss.core.database.EpisodePlay as EpisodePlayRow
import com.codingpit.muviss.core.database.EpisodeProgress as EpisodeProgressRow
import com.codingpit.muviss.core.database.ListEntry as ListEntryRow
import com.codingpit.muviss.core.database.MediaList as MediaListRow
import com.codingpit.muviss.core.database.TriageDecision as TriageDecisionRow
import com.codingpit.muviss.core.database.TriageSnooze as TriageSnoozeRow

private const val FULL_PULL_KEY = "_lastFullPullAt"

/**
 * The local half of the change-log: reading what is dirty, marking it sent,
 * and the bulk operations (everything back on the log, everything gone) that
 * account handling and "resync everything" are made of.
 *
 * Split from [SyncEngine] because none of it involves the network or a policy
 * decision — it is what the database says, in the shape the backend takes.
 */
internal class LocalChangeLog(private val database: MuvissDatabase) {

    /**
     * Sends every dirty row through [push], one table at a time in
     * [SyncTable] order and at most [PUSH_CHUNK_ROWS] rows per call, and
     * returns how many rows it sent.
     *
     * A chunk is marked clean only after its own push succeeded, and only if
     * the row is still the version that was sent: see `clearDirty` in the
     * `.sq` files. So a failure at chunk seven leaves chunks one to six
     * cleared, and an edit made while a chunk was in flight stays dirty.
     */
    suspend fun pushDirty(push: suspend (SyncChangeSet) -> Result<Unit>): Int = SyncTable.entries.sumOf { table -> pushTable(table, push) }

    private suspend fun pushTable(table: SyncTable, push: suspend (SyncChangeSet) -> Result<Unit>): Int = when (table) {
        SyncTable.COLLECTION_ENTRY -> send(
            rows = database.collectionEntryQueries.selectDirty().awaitAsList().map { it.toChange() },
            wrap = { SyncChangeSet(collectionEntries = it) },
            push = push,
        ) { chunk -> chunk.forEach { database.collectionEntryQueries.clearDirty(it.mediaId, it.updatedAtEpochMs) } }

        SyncTable.EPISODE_PROGRESS -> send(
            rows = database.episodeProgressQueries.selectDirty().awaitAsList().map { it.toChange() },
            wrap = { SyncChangeSet(episodeProgress = it) },
            push = push,
        ) { chunk -> chunk.forEach { database.episodeProgressQueries.clearDirty(it.episodeId, it.updatedAtEpochMs) } }

        SyncTable.MEDIA_LIST -> send(
            rows = database.mediaListQueries.selectDirtyLists().awaitAsList().map { it.toChange() },
            wrap = { SyncChangeSet(mediaLists = it) },
            push = push,
        ) { chunk -> chunk.forEach { database.mediaListQueries.clearDirtyList(it.id, it.updatedAtEpochMs) } }

        SyncTable.LIST_ENTRY -> send(
            rows = database.mediaListQueries.selectDirtyEntries().awaitAsList().map { it.toChange() },
            wrap = { SyncChangeSet(listEntries = it) },
            push = push,
        ) { chunk -> chunk.forEach { database.mediaListQueries.clearDirtyEntry(it.listId, it.mediaId, it.updatedAtEpochMs) } }

        SyncTable.TRIAGE_DECISION -> send(
            rows = database.triageDecisionQueries.selectDirty().awaitAsList().map { it.toChange() },
            wrap = { SyncChangeSet(triageDecisions = it) },
            push = push,
        ) { chunk -> chunk.forEach { database.triageDecisionQueries.clearDirty(it.mediaId, it.updatedAtEpochMs) } }

        SyncTable.TRIAGE_SNOOZE -> send(
            rows = database.triageSnoozeQueries.selectDirty().awaitAsList().map { it.toChange() },
            wrap = { SyncChangeSet(triageSnoozes = it) },
            push = push,
        ) { chunk -> chunk.forEach { database.triageSnoozeQueries.clearDirty(it.mediaId, it.updatedAtEpochMs) } }

        SyncTable.EPISODE_PLAY -> send(
            rows = database.episodePlayQueries.selectDirty().awaitAsList().map { it.toChange() },
            wrap = { SyncChangeSet(episodePlays = it) },
            push = push,
        ) { chunk -> chunk.forEach { database.episodePlayQueries.clearDirty(it.id, it.updatedAtEpochMs) } }
    }

    private suspend fun <T> send(
        rows: List<T>,
        wrap: (List<T>) -> SyncChangeSet,
        push: suspend (SyncChangeSet) -> Result<Unit>,
        markPushed: suspend (List<T>) -> Unit,
    ): Int {
        rows.chunked(PUSH_CHUNK_ROWS).forEach { chunk ->
            push(wrap(chunk)).getOrThrow()
            database.transaction { markPushed(chunk) }
        }
        return rows.size
    }

    // --- cursors --------------------------------------------------------------

    suspend fun loadCursors(): Map<SyncTable, SyncCursor> {
        val stored = database.syncCursorQueries.selectAll().awaitAsList().associate { it.tableName to it.seq }
        return SyncTable.entries.mapNotNull { table -> stored[table.cursorKey]?.let { table to SyncCursor(it) } }.toMap()
    }

    // The three below, and the two bulk operations after them, do not open a
    // transaction of their own: each is one step of something the caller must
    // make atomic, and a nested transaction is a second `newTransaction()` for
    // no extra safety.

    suspend fun saveCursors(cursors: Map<SyncTable, SyncCursor>) {
        cursors.forEach { (table, cursor) -> database.syncCursorQueries.upsert(table.cursorKey, cursor.position) }
    }

    suspend fun resetCursors() {
        database.syncCursorQueries.deleteAll()
    }

    /** When this device last pulled from the beginning, or null if it has no record of one. Stored under a reserved name in `syncCursor`; see that file. */
    suspend fun lastFullPullAt(): Long? = database.syncCursorQueries.selectByName(FULL_PULL_KEY).awaitAsOneOrNull()?.seq

    suspend fun recordFullPull(atEpochMs: Long) {
        database.syncCursorQueries.upsert(FULL_PULL_KEY, atEpochMs)
    }

    // --- bulk operations ------------------------------------------------------

    /** Puts every user-owned row back on the change-log, so the next push sends the whole library. */
    suspend fun markAllDirty() {
        database.collectionEntryQueries.markAllDirty()
        database.episodeProgressQueries.markAllDirty()
        database.mediaListQueries.markAllListsDirty()
        database.mediaListQueries.markAllEntriesDirty()
        database.triageDecisionQueries.markAllDirty()
        database.triageSnoozeQueries.markAllDirty()
        database.episodePlayQueries.markAllDirty()
    }

    /**
     * Deletes every user-owned row. The `episode` catalog is not among them: it
     * is TMDB's data rather than the user's (ADR 0015), and is not what an
     * account switch is about.
     */
    suspend fun deleteAllUserData() {
        database.collectionEntryQueries.deleteAll()
        database.episodeProgressQueries.deleteAll()
        database.mediaListQueries.deleteAllLists()
        database.mediaListQueries.deleteAllEntries()
        database.triageDecisionQueries.deleteAll()
        database.triageSnoozeQueries.deleteAll()
        database.episodePlayQueries.deleteAll()
    }
}

internal fun CollectionEntryRow.toChange() = CollectionEntryChange(
    mediaId = mediaId,
    mediaType = mediaType,
    title = title,
    posterUrl = posterUrl,
    releaseYear = releaseYear?.toInt(),
    productionStatus = productionStatus,
    totalEpisodes = totalEpisodes.toInt(),
    airedEpisodes = airedEpisodes.toInt(),
    favorite = favorite,
    genres = genres,
    runtimeMinutes = runtimeMinutes?.toInt(),
    addedAtEpochMs = addedAtEpochMs,
    updatedAtEpochMs = updatedAtEpochMs,
    deleted = deleted,
    rating = rating?.toInt(),
    note = note,
    revisitWillingness = revisitWillingness,
    coWatchPinned = coWatchPinned,
)

internal fun EpisodeProgressRow.toChange() = EpisodeProgressChange(
    episodeId = episodeId,
    mediaId = mediaId,
    seasonNumber = seasonNumber.toInt(),
    episodeNumber = episodeNumber.toInt(),
    seen = seen,
    updatedAtEpochMs = updatedAtEpochMs,
)

internal fun MediaListRow.toChange() = MediaListChange(
    id = id,
    name = name,
    createdAtEpochMs = createdAtEpochMs,
    updatedAtEpochMs = updatedAtEpochMs,
    deleted = deleted,
)

internal fun ListEntryRow.toChange() = ListEntryChange(
    listId = listId,
    mediaId = mediaId,
    addedAtEpochMs = addedAtEpochMs,
    updatedAtEpochMs = updatedAtEpochMs,
    deleted = deleted,
)

internal fun EpisodePlayRow.toChange() = EpisodePlayChange(
    id = id,
    episodeId = episodeId,
    mediaId = mediaId,
    watchedAtEpochMs = watchedAtEpochMs,
    updatedAtEpochMs = updatedAtEpochMs,
    deleted = deleted,
)

internal fun TriageDecisionRow.toChange() = TriageDecisionChange(
    mediaId = mediaId,
    mediaType = mediaType,
    verdict = verdict,
    title = title,
    posterUrl = posterUrl,
    decidedAtEpochMs = decidedAtEpochMs,
    resolved = resolved,
    updatedAtEpochMs = updatedAtEpochMs,
    deleted = deleted,
)

internal fun TriageSnoozeRow.toChange() = TriageSnoozeChange(
    mediaId = mediaId,
    mediaType = mediaType,
    title = title,
    year = year,
    posterUrl = posterUrl,
    overview = overview,
    snoozedAtEpochMs = snoozedAtEpochMs,
    dueAtEpochDay = dueAtEpochDay,
    updatedAtEpochMs = updatedAtEpochMs,
    deleted = deleted,
)
