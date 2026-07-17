package com.codingpit.muviss.core.sync

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.core.common.AppDispatchers
import com.codingpit.muviss.core.database.MuvissDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import com.codingpit.muviss.core.database.CollectionEntry as CollectionEntryRow
import com.codingpit.muviss.core.database.EpisodeProgress as EpisodeProgressRow
import com.codingpit.muviss.core.database.ListEntry as ListEntryRow
import com.codingpit.muviss.core.database.MediaList as MediaListRow

/** The outcome of one [SyncEngine.syncNow] run. */
sealed interface SyncOutcome {
    /** No [SyncBackend] session exists — sync was skipped, not attempted. Not an error: this is the default, pre-sign-in state. */
    data object NotSignedIn : SyncOutcome

    data class Success(val pushedCount: Int, val pulledCount: Int, val syncedAtEpochMs: Long) : SyncOutcome

    data class Failed(val message: String) : SyncOutcome
}

/**
 * Replays local dirty rows to [backend] and merges remote changes back in —
 * the concrete implementation of the "SyncEngine" abstraction CONTEXT.md and
 * ADR 0002 named before any backend existed. See ADR 0009 for the full
 * design and docs/SYNC.md for the schema/RLS this expects server-side.
 *
 * **Order**: push local dirty rows first, then pull. A push is safe to
 * attempt unconditionally (never gated on first reading the remote value)
 * because the backend's own last-write-wins guard — a Postgres trigger, see
 * docs/SYNC.md — silently discards a push whose `updated_at_epoch_ms` is
 * older than what's already stored, rather than this engine needing to
 * read-before-write. The pull side applies the same rule client-side (see
 * [applyRemote]): a remote row only overwrites a local one when its
 * `updatedAtEpochMs` is strictly greater, so tombstones (`deleted = true`)
 * propagate exactly like any other field and a stale delete/undelete can
 * never resurrect a newer local write, or vice versa.
 *
 * Conflict granularity is the whole row, not per-field — simple and
 * predictable, matching CONTEXT.md's "episode ticks are idempotent
 * booleans" note (no merge needed there beyond LWW) and accepted as a
 * known, documented limitation for the richer rows (ADR 0009).
 */
class SyncEngine(
    private val backend: SyncBackend,
    private val database: MuvissDatabase,
    private val dispatchers: AppDispatchers,
    private val clock: AppClock,
) {
    /** The last time [syncNow] completed a full cycle, or null if it never has. Backs the profile screen's "last synced" label. */
    fun observeLastSyncedAt(): Flow<Long?> = database.appSettingsQueries.selectSettings()
        .asFlow()
        .mapToOneOrNull(dispatchers.io)
        .map { it?.lastSyncedAtEpochMs }

    suspend fun syncNow(): SyncOutcome = withContext(dispatchers.io) {
        backend.session.first() ?: return@withContext SyncOutcome.NotSignedIn
        runCatching { runSync() }
            .fold(
                onSuccess = { it },
                onFailure = { e -> SyncOutcome.Failed(e.message ?: "Sync failed") },
            )
    }

    private suspend fun runSync(): SyncOutcome {
        val dirty = collectDirty()
        if (!dirty.isEmpty) {
            backend.push(dirty).getOrThrow()
            clearDirty(dirty)
        }

        database.appSettingsQueries.ensureRow()
        val lastSyncedAt = database.appSettingsQueries.selectSettings().awaitAsOneOrNull()?.lastSyncedAtEpochMs
        val remote = backend.pull(lastSyncedAt).getOrThrow()
        applyRemote(remote)

        val now = clock.nowEpochMs()
        database.appSettingsQueries.updateLastSyncedAt(now)
        return SyncOutcome.Success(pushedCount = dirty.size, pulledCount = remote.size, syncedAtEpochMs = now)
    }

    private suspend fun collectDirty(): SyncChangeSet = SyncChangeSet(
        collectionEntries = database.collectionEntryQueries.selectDirty().awaitAsList().map { it.toChange() },
        episodeProgress = database.episodeProgressQueries.selectDirty().awaitAsList().map { it.toChange() },
        mediaLists = database.mediaListQueries.selectDirtyLists().awaitAsList().map { it.toChange() },
        listEntries = database.mediaListQueries.selectDirtyEntries().awaitAsList().map { it.toChange() },
    )

    private suspend fun clearDirty(dirty: SyncChangeSet) {
        dirty.collectionEntries.forEach { database.collectionEntryQueries.clearDirty(it.mediaId) }
        dirty.episodeProgress.forEach { database.episodeProgressQueries.clearDirty(it.episodeId) }
        dirty.mediaLists.forEach { database.mediaListQueries.clearDirtyList(it.id) }
        dirty.listEntries.forEach { database.mediaListQueries.clearDirtyEntry(listId = it.listId, mediaId = it.mediaId) }
    }

    private suspend fun applyRemote(remote: SyncChangeSet) {
        remote.collectionEntries.forEach { change -> applyCollectionEntry(change) }
        remote.episodeProgress.forEach { change -> applyEpisodeProgress(change) }
        remote.mediaLists.forEach { change -> applyMediaList(change) }
        remote.listEntries.forEach { change -> applyListEntry(change) }
    }

    /** Never resurrects a tombstone and never overwrites a newer local edit — see the class KDoc's "Order" section. Applies unconditionally when no local row exists (first sync on a fresh install). */
    private suspend fun applyCollectionEntry(change: CollectionEntryChange) {
        val local = database.collectionEntryQueries.selectById(change.mediaId).awaitAsOneOrNull()
        if (local != null && local.updatedAtEpochMs >= change.updatedAtEpochMs) return
        database.collectionEntryQueries.upsert(
            mediaId = change.mediaId,
            mediaType = change.mediaType,
            title = change.title,
            posterUrl = change.posterUrl,
            releaseYear = change.releaseYear?.toLong(),
            productionStatus = change.productionStatus,
            totalEpisodes = change.totalEpisodes.toLong(),
            airedEpisodes = change.airedEpisodes.toLong(),
            favorite = change.favorite,
            genres = change.genres,
            runtimeMinutes = change.runtimeMinutes?.toLong(),
            addedAtEpochMs = change.addedAtEpochMs,
            updatedAtEpochMs = change.updatedAtEpochMs,
            isDirty = false,
            deleted = change.deleted,
            // A per-device notification preference, not user library data — never overwritten by a remote row (see `SyncChangeSet.CollectionEntryChange`'s KDoc).
            notificationsMuted = local?.notificationsMuted ?: false,
            rating = change.rating?.toLong(),
            note = change.note,
        )
    }

    private suspend fun applyEpisodeProgress(change: EpisodeProgressChange) {
        val local = database.episodeProgressQueries.selectByEpisodeId(change.episodeId).awaitAsOneOrNull()
        if (local != null && local.updatedAtEpochMs >= change.updatedAtEpochMs) return
        database.episodeProgressQueries.upsert(
            episodeId = change.episodeId,
            mediaId = change.mediaId,
            seasonNumber = change.seasonNumber.toLong(),
            episodeNumber = change.episodeNumber.toLong(),
            seen = change.seen,
            updatedAtEpochMs = change.updatedAtEpochMs,
            isDirty = false,
        )
    }

    private suspend fun applyMediaList(change: MediaListChange) {
        val local = database.mediaListQueries.selectListById(change.id).awaitAsOneOrNull()
        if (local != null && local.updatedAtEpochMs >= change.updatedAtEpochMs) return
        database.mediaListQueries.upsertList(
            id = change.id,
            name = change.name,
            createdAtEpochMs = change.createdAtEpochMs,
            updatedAtEpochMs = change.updatedAtEpochMs,
            isDirty = false,
            deleted = change.deleted,
        )
    }

    private suspend fun applyListEntry(change: ListEntryChange) {
        val local = database.mediaListQueries.selectEntry(change.listId, change.mediaId).awaitAsOneOrNull()
        if (local != null && local.updatedAtEpochMs >= change.updatedAtEpochMs) return
        database.mediaListQueries.upsertEntry(
            listId = change.listId,
            mediaId = change.mediaId,
            addedAtEpochMs = change.addedAtEpochMs,
            updatedAtEpochMs = change.updatedAtEpochMs,
            isDirty = false,
            deleted = change.deleted,
        )
    }

    private fun CollectionEntryRow.toChange() = CollectionEntryChange(
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
    )

    private fun EpisodeProgressRow.toChange() = EpisodeProgressChange(
        episodeId = episodeId,
        mediaId = mediaId,
        seasonNumber = seasonNumber.toInt(),
        episodeNumber = episodeNumber.toInt(),
        seen = seen,
        updatedAtEpochMs = updatedAtEpochMs,
    )

    private fun MediaListRow.toChange() = MediaListChange(
        id = id,
        name = name,
        createdAtEpochMs = createdAtEpochMs,
        updatedAtEpochMs = updatedAtEpochMs,
        deleted = deleted,
    )

    private fun ListEntryRow.toChange() = ListEntryChange(
        listId = listId,
        mediaId = mediaId,
        addedAtEpochMs = addedAtEpochMs,
        updatedAtEpochMs = updatedAtEpochMs,
        deleted = deleted,
    )
}
