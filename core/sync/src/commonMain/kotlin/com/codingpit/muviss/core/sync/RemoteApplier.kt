package com.codingpit.muviss.core.sync

import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import com.codingpit.muviss.core.database.MuvissDatabase

/**
 * Merges pulled rows into the local database (ADR 0020).
 *
 * Every page is applied in **one transaction**: the Library, the widgets and
 * every other `Flow` over these tables re-emit once per page instead of once
 * per row, and no half-applied page is ever visible — which matters because
 * some half-applied states (ticks pulled before the snapshot that covers them)
 * are ones the rest of the app throws on.
 *
 * ## Who wins
 *
 * A pulled row replaces a local one when the local row is **clean**, or when
 * the pulled row's `updatedAtEpochMs` is strictly newer than a local row with
 * unsent edits. Last-write-wins is a contest between two unsent-or-newer
 * edits; a clean local row is not one, it is a copy of something the server
 * already holds, so the server's current version is simply the truth. That
 * distinction is what lets the server clamp a fast device's timestamp without
 * stranding that device on a stamp nobody else will ever beat.
 *
 * ## User fields and snapshot fields
 *
 * `collectionEntry` mixes what the user said (favorite, rating, note, the
 * tombstone, the add date) with what the provider said (title, poster, episode
 * counts). A pulled row that wins takes the user fields only. The snapshot
 * fields it carries are only as fresh as whichever device last edited the row,
 * and would overwrite a newer snapshot taken here; they are used only when
 * there is no local row to keep.
 *
 * ## Reconciliation
 *
 * Merging `episodeProgress` and `episodePlay` independently can leave them
 * disagreeing, and merging `collectionEntry` and `episodeProgress`
 * independently can leave more episodes seen than aired. [reconcile] puts
 * both back in step for the titles a pull touched.
 */
internal class RemoteApplier(private val database: MuvissDatabase) {

    /** Applies [page] in one transaction and returns the ids of the titles it touched. */
    suspend fun applyPage(page: SyncPage): Set<String> = database.transactionWithResult {
        val changes = page.changes
        changes.collectionEntries.forEach { applyCollectionEntry(it) }
        changes.episodeProgress.forEach { applyEpisodeProgress(it) }
        changes.mediaLists.forEach { applyMediaList(it) }
        changes.listEntries.forEach { applyListEntry(it) }
        changes.triageDecisions.forEach { applyTriageDecision(it) }
        changes.triageSnoozes.forEach { applyTriageSnooze(it) }
        changes.episodePlays.forEach { applyEpisodePlay(it) }

        val touched = buildSet {
            changes.collectionEntries.mapTo(this) { it.mediaId }
            changes.episodeProgress.mapTo(this) { it.mediaId }
            changes.episodePlays.mapTo(this) { it.mediaId }
        }
        // Inside the page's own transaction, so there is no moment at which the
        // ticks are in and the count that covers them is not.
        if (changes.episodeProgress.isNotEmpty() || changes.collectionEntries.isNotEmpty()) raiseAiredToSeen(touched)
        touched
    }

    /**
     * Runs after every table has been drained, over the titles any page
     * touched, and then [finish] — in the last transaction, so the cursors move
     * only once the reconciliation they rest on has committed. If the process
     * dies before then nothing has advanced, and the retry re-applies the same
     * rows to the same result.
     *
     * It cannot run per page: `episodeProgress` and `episodePlay` arrive in
     * different tables at different times, and reconciling after the first
     * would see ticks with no plays only because the plays had not arrived yet.
     */
    suspend fun reconcile(touched: Set<String>, finish: suspend () -> Unit) {
        val chunks = touched.chunked(SQL_ID_CHUNK).ifEmpty { listOf(emptyList()) }
        chunks.forEachIndexed { index, mediaIds ->
            database.transaction {
                if (mediaIds.isNotEmpty()) {
                    // An un-tick wins over a play that outlived it; then a tick with no play gets one.
                    database.episodePlayQueries.tombstonePlaysForUnseen(mediaIds)
                    database.episodePlayQueries.insertMissingPlaysForSeen(mediaIds)
                    database.collectionEntryQueries.raiseAiredToSeen(mediaIds)
                }
                if (index == chunks.lastIndex) finish()
            }
        }
    }

    private suspend fun raiseAiredToSeen(mediaIds: Set<String>) {
        mediaIds.chunked(SQL_ID_CHUNK).forEach { database.collectionEntryQueries.raiseAiredToSeen(it) }
    }

    private fun remoteWins(localUpdatedAt: Long, localIsDirty: Boolean, remoteUpdatedAt: Long): Boolean = !localIsDirty || remoteUpdatedAt > localUpdatedAt

    private suspend fun applyCollectionEntry(change: CollectionEntryChange) {
        val queries = database.collectionEntryQueries
        val local = queries.selectById(change.mediaId).awaitAsOneOrNull()
        when {
            local == null -> queries.upsert(
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
                notificationsMuted = false,
                rating = change.rating?.toLong(),
                note = change.note,
                revisitWillingness = change.revisitWillingness,
                coWatchPinned = change.coWatchPinned,
            )

            remoteWins(local.updatedAtEpochMs, local.isDirty, change.updatedAtEpochMs) -> queries.applyRemoteUserFields(
                favorite = change.favorite,
                rating = change.rating?.toLong(),
                note = change.note,
                revisitWillingness = change.revisitWillingness,
                coWatchPinned = change.coWatchPinned,
                deleted = change.deleted,
                addedAtEpochMs = change.addedAtEpochMs,
                updatedAtEpochMs = change.updatedAtEpochMs,
                mediaId = change.mediaId,
            )
        }
    }

    private suspend fun applyEpisodeProgress(change: EpisodeProgressChange) {
        val local = database.episodeProgressQueries.selectByEpisodeId(change.episodeId).awaitAsOneOrNull()
        if (local != null && !remoteWins(local.updatedAtEpochMs, local.isDirty, change.updatedAtEpochMs)) return
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
        if (local != null && !remoteWins(local.updatedAtEpochMs, local.isDirty, change.updatedAtEpochMs)) return
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
        if (local != null && !remoteWins(local.updatedAtEpochMs, local.isDirty, change.updatedAtEpochMs)) return
        database.mediaListQueries.upsertEntry(
            listId = change.listId,
            mediaId = change.mediaId,
            addedAtEpochMs = change.addedAtEpochMs,
            updatedAtEpochMs = change.updatedAtEpochMs,
            isDirty = false,
            deleted = change.deleted,
        )
    }

    private suspend fun applyTriageDecision(change: TriageDecisionChange) {
        val local = database.triageDecisionQueries.selectById(change.mediaId).awaitAsOneOrNull()
        if (local != null && !remoteWins(local.updatedAtEpochMs, local.isDirty, change.updatedAtEpochMs)) return
        database.triageDecisionQueries.upsert(
            mediaId = change.mediaId,
            mediaType = change.mediaType,
            verdict = change.verdict,
            title = change.title,
            posterUrl = change.posterUrl,
            decidedAtEpochMs = change.decidedAtEpochMs,
            resolved = change.resolved,
            updatedAtEpochMs = change.updatedAtEpochMs,
            isDirty = false,
            deleted = change.deleted,
        )
    }

    private suspend fun applyTriageSnooze(change: TriageSnoozeChange) {
        val local = database.triageSnoozeQueries.selectById(change.mediaId).awaitAsOneOrNull()
        if (local != null && !remoteWins(local.updatedAtEpochMs, local.isDirty, change.updatedAtEpochMs)) return
        database.triageSnoozeQueries.upsert(
            mediaId = change.mediaId,
            mediaType = change.mediaType,
            title = change.title,
            year = change.year,
            posterUrl = change.posterUrl,
            overview = change.overview,
            snoozedAtEpochMs = change.snoozedAtEpochMs,
            dueAtEpochDay = change.dueAtEpochDay,
            updatedAtEpochMs = change.updatedAtEpochMs,
            isDirty = false,
            deleted = change.deleted,
        )
    }

    private suspend fun applyEpisodePlay(change: EpisodePlayChange) {
        val local = database.episodePlayQueries.selectById(change.id).awaitAsOneOrNull()
        if (local != null && !remoteWins(local.updatedAtEpochMs, local.isDirty, change.updatedAtEpochMs)) return
        database.episodePlayQueries.upsert(
            id = change.id,
            episodeId = change.episodeId,
            mediaId = change.mediaId,
            watchedAtEpochMs = change.watchedAtEpochMs,
            updatedAtEpochMs = change.updatedAtEpochMs,
            isDirty = false,
            deleted = change.deleted,
        )
    }
}
