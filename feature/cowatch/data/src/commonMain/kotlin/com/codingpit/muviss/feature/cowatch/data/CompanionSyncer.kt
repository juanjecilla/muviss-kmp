package com.codingpit.muviss.feature.cowatch.data

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import com.codingpit.muviss.core.common.AppDispatchers
import com.codingpit.muviss.core.database.MuvissDatabase
import com.codingpit.muviss.core.sync.SyncBackend
import com.codingpit.muviss.core.sync.SyncCursor
import com.codingpit.muviss.core.sync.companion.CompanionBackend
import com.codingpit.muviss.core.sync.companion.CompanionChangeSet
import com.codingpit.muviss.core.sync.companion.CompanionLinkChange
import com.codingpit.muviss.core.sync.companion.CompanionPage
import com.codingpit.muviss.core.sync.companion.CompanionPoolEntryChange
import com.codingpit.muviss.core.sync.companion.CompanionTable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Moves co-watch rows to and from the cloud (EPIC 41, ADR 0022).
 *
 * The part worth reading is [applyPage]. A pull returns rows in both
 * directions — the policy grants a read to the author and to the addressee — so
 * every inbound row is classified before anything is written:
 *
 * - **Authored by this account** (`user_id == me`): our own copy coming back.
 *   Nothing to do; the local row is the original.
 * - **Addressed to this account** (`recipient_id == me`): a Companion's data. It
 *   lands in cache — `companionPoolIn`, or `companionLink.remoteState` — and
 *   never in anything this account owns.
 * - **Neither**: impossible if RLS is doing its job, and therefore exactly the
 *   thing not to trust. Dropped rather than stored.
 *
 * That last branch is the one to keep. RLS is the only boundary this system has
 * (the anon key ships in the binary), so the client refusing to store a row it
 * was never supposed to see is the difference between a policy bug being a
 * visible nothing and a silent leak into someone's local database.
 */
internal class CompanionSyncer(
    private val database: MuvissDatabase,
    private val backend: SyncBackend,
    private val companionBackend: CompanionBackend,
    private val dispatchers: AppDispatchers,
) {
    private val links = database.companionLinkQueries
    private val pools = database.companionPoolQueries
    private val cursors = database.syncCursorQueries

    /** One cycle: push what changed, then pull and apply. Mirrors `SyncEngine.runSync`'s order, for the same reason (ADR 0009). */
    suspend fun syncNow(): Result<Unit> = withContext(dispatchers.io) {
        val me = backend.session.first()?.userId ?: return@withContext Result.success(Unit)
        runCatching {
            push(me).getOrThrow()
            pull(me).getOrThrow()
        }
    }

    private suspend fun push(me: String): Result<Unit> {
        val dirtyLinks = links.selectDirty().awaitAsList()
        val dirtyPool = pools.selectDirtyOut().awaitAsList()
        if (dirtyLinks.isEmpty() && dirtyPool.isEmpty()) return Result.success(Unit)

        val changes = CompanionChangeSet(
            links = dirtyLinks.map { row ->
                CompanionLinkChange(
                    userId = me,
                    recipientId = row.companionUserId,
                    state = row.state,
                    nonce = row.nonce,
                    createdAtEpochMs = row.createdAtEpochMs,
                    updatedAtEpochMs = row.updatedAtEpochMs,
                    deleted = row.deleted,
                )
            },
            poolEntries = dirtyPool.map { row ->
                CompanionPoolEntryChange(
                    userId = me,
                    recipientId = row.companionUserId,
                    mediaId = row.mediaId,
                    mediaType = row.mediaType,
                    title = row.title,
                    posterUrl = row.posterUrl,
                    genres = row.genres,
                    runtimeMinutes = row.runtimeMinutes?.toInt(),
                    started = row.started,
                    seen = row.seen,
                    pinned = row.pinned,
                    updatedAtEpochMs = row.updatedAtEpochMs,
                    deleted = row.deleted,
                    flatrateProviderIds = row.flatrateProviderIds,
                )
            },
        )

        return companionBackend.push(changes).onSuccess {
            // Conditional on the stamp, like every other `clearDirty` since
            // EPIC 39: the push is an awaited network call and the user can edit
            // the same row while it is in flight. Clearing by key alone would
            // mark that newer edit as sent and it would never go.
            dirtyLinks.forEach { links.clearDirty(companionUserId = it.companionUserId, pushedAt = it.updatedAtEpochMs) }
            dirtyPool.forEach {
                pools.clearDirtyOut(
                    companionUserId = it.companionUserId,
                    mediaId = it.mediaId,
                    pushedAt = it.updatedAtEpochMs,
                )
            }
        }
    }

    private suspend fun pull(me: String): Result<Unit> {
        val after = CompanionTable.entries.mapNotNull { table ->
            cursors.selectAll().awaitAsList().firstOrNull { it.tableName == table.cursorKey }
                ?.let { table to SyncCursor(it.seq) }
        }.toMap()

        return companionBackend.pull(after) { page -> applyPage(page, me) }
    }

    private suspend fun applyPage(page: CompanionPage, me: String) {
        page.changes.links.forEach { change -> applyLink(change, me) }
        page.changes.poolEntries.forEach { change -> applyPoolEntry(change, me) }
        cursors.upsert(tableName = page.table.cursorKey, seq = page.cursor.position)
    }

    private suspend fun applyLink(change: CompanionLinkChange, me: String) {
        if (change.userId == me) return // our own statement, coming back
        if (change.recipientId != me) return // not ours to hold — see the class KDoc
        val companionUserId = change.userId
        val existing = links.selectById(companionUserId).awaitAsOneOrNull()
        val remoteState = if (change.deleted) SqlDelightCoWatchRepository.STATE_REVOKED else change.state
        if (existing == null) {
            // They invited us, or answered a code we issued on another device.
            // Our own half starts un-accepted: both sides confirm (ADR 0022).
            links.upsert(
                companionUserId = companionUserId,
                localName = null,
                nonce = change.nonce,
                state = SqlDelightCoWatchRepository.STATE_INVITED,
                remoteState = remoteState,
                createdAtEpochMs = change.createdAtEpochMs,
                updatedAtEpochMs = change.updatedAtEpochMs,
                // Not dirty: nothing here is ours to push until the user acts.
                isDirty = false,
                deleted = false,
            )
        } else {
            // Cache only. Their statement never rewrites ours.
            links.setRemoteState(remoteState = remoteState, companionUserId = companionUserId)
        }
    }

    private suspend fun applyPoolEntry(change: CompanionPoolEntryChange, me: String) {
        if (change.userId == me) return
        if (change.recipientId != me) return
        if (change.deleted) {
            // Cache has nothing to preserve, so a tombstone simply removes it.
            pools.deleteIn(companionUserId = change.userId, mediaId = change.mediaId)
            return
        }
        pools.upsertIn(
            companionUserId = change.userId,
            mediaId = change.mediaId,
            mediaType = change.mediaType,
            title = change.title,
            posterUrl = change.posterUrl,
            genres = change.genres,
            runtimeMinutes = change.runtimeMinutes?.toLong(),
            started = change.started,
            seen = change.seen,
            pinned = change.pinned,
            publishedAtEpochMs = change.updatedAtEpochMs,
            flatrateProviderIds = change.flatrateProviderIds,
        )
    }

    private val CompanionTable.cursorKey: String get() = wireName
}
