package com.codingpit.muviss.feature.cowatch.data

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.core.common.AppDispatchers
import com.codingpit.muviss.core.database.MuvissDatabase
import com.codingpit.muviss.core.sync.SyncBackend
import com.codingpit.muviss.feature.cowatch.api.CompanionState
import com.codingpit.muviss.feature.cowatch.api.LinkedCompanion
import com.codingpit.muviss.feature.cowatch.api.PoolSettings
import com.codingpit.muviss.feature.cowatch.api.PoolSource
import com.codingpit.muviss.feature.cowatch.domain.CoWatchRepository
import com.codingpit.muviss.feature.cowatch.domain.InviteCode
import com.codingpit.muviss.feature.cowatch.domain.PoolItem
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import com.codingpit.muviss.core.database.CompanionLink as CompanionLinkRow
import com.codingpit.muviss.core.database.CompanionPoolIn as CompanionPoolInRow

/**
 * Local storage for co-watch (EPIC 41, ADR 0022).
 *
 * Two things about this class are load-bearing rather than incidental.
 *
 * **Link state is two statements, not one.** `companionLink.state` is what this
 * account said; `remoteState` is what they said, and it is cache — pulled,
 * replaced, never pushed. [CompanionState] is derived from the pair, which is
 * how a relation exists at all without a row that two accounts can write.
 *
 * **Publishing is a diff, and removals are tombstones.** [publishPool] writes
 * what is in the pool now and soft-deletes what used to be. Last-write-wins has
 * nothing to compare an absent row against, so a hard delete is undone by the
 * next pull — the same trap ADR 0013 documents for rewatch history.
 */
internal class SqlDelightCoWatchRepository(
    private val database: MuvissDatabase,
    private val backend: SyncBackend,
    private val clock: AppClock,
    private val dispatchers: AppDispatchers,
) : CoWatchRepository {

    private val links = database.companionLinkQueries
    private val pools = database.companionPoolQueries
    private val settings = database.appSettingsQueries

    override fun observeCompanions(): Flow<List<LinkedCompanion>> = links.selectAll().asFlow().mapToList(dispatchers.io).map { rows -> rows.map { it.toCompanion() } }

    override fun observeCompanionPool(companionUserId: String): Flow<List<PoolItem>> = pools.selectInForCompanion(companionUserId).asFlow().mapToList(dispatchers.io)
        .map { rows -> rows.map { it.toPoolItem() } }

    override fun observePoolSettings(): Flow<PoolSettings> = settings.selectSettings().asFlow().mapToOneOrNull(dispatchers.io).map { row ->
        PoolSettings(
            source = row?.coWatchPoolListId?.let { PoolSource.Named(it) } ?: PoolSource.NotStarted,
            includeSeenByDefault = row?.coWatchIncludeSeen ?: true,
        )
    }

    override suspend fun currentUserId(): String? = backend.session.first()?.userId

    override suspend fun recordIssuedInvite(code: InviteCode) = withContext(dispatchers.io) {
        // Nothing is addressed yet: this row only remembers the nonce, so an
        // acceptance that comes back can be matched against a code this account
        // actually issued. It is not pushed until somebody answers.
        Unit
    }

    override suspend fun acceptInvite(code: InviteCode): Result<Unit> = withContext(dispatchers.io) {
        runCatching<Unit> {
            val now = clock.nowEpochMs()
            val existing = links.selectById(code.userId).awaitAsOneOrNull()
            links.upsert(
                companionUserId = code.userId,
                localName = existing?.localName,
                nonce = code.nonce,
                state = STATE_ACCEPTED,
                remoteState = existing?.remoteState,
                createdAtEpochMs = existing?.createdAtEpochMs ?: now,
                updatedAtEpochMs = now,
                isDirty = true,
                deleted = false,
            )
        }
    }

    override suspend fun confirmCompanion(companionUserId: String) = withContext(dispatchers.io) {
        links.setState(state = STATE_ACCEPTED, now = clock.nowEpochMs(), companionUserId = companionUserId)
        Unit
    }

    override suspend fun setLocalName(companionUserId: String, localName: String?) = withContext(dispatchers.io) {
        // Not stamped and not dirtied: what one person calls another never
        // leaves this device, so it is not a change anyone else can observe.
        links.setLocalName(localName = localName?.trim()?.ifBlank { null }, companionUserId = companionUserId)
        Unit
    }

    override suspend fun unlink(companionUserId: String) = withContext(dispatchers.io) {
        val now = clock.nowEpochMs()
        links.transaction {
            // Both halves together. Revoking the link but leaving the pool rows
            // would leave a readable copy on the server, addressed to someone
            // this account no longer has a relationship with.
            links.softDelete(now = now, companionUserId = companionUserId)
            pools.softDeleteAllOutForCompanion(now = now, companionUserId = companionUserId)
            // Their copy is cache and ours to drop immediately. What their own
            // device already pulled is beyond reach until it next syncs — the
            // UI says so rather than implying otherwise (ADR 0022).
            pools.deleteInForCompanion(companionUserId)
        }
    }

    override suspend fun setPoolSource(source: PoolSource) = withContext(dispatchers.io) {
        settings.ensureRow()
        settings.updateCoWatchPoolListId((source as? PoolSource.Named)?.listId)
        Unit
    }

    override suspend fun setIncludeSeenByDefault(include: Boolean) = withContext(dispatchers.io) {
        settings.ensureRow()
        settings.updateCoWatchIncludeSeen(include)
        Unit
    }

    override suspend fun publishPool(items: List<PoolItem>) = withContext(dispatchers.io) {
        val now = clock.nowEpochMs()
        val recipients = links.selectAll().awaitAsList().filter { it.toCompanion().state == CompanionState.ACTIVE }
        val wanted = items.associateBy { it.mediaId.toString() }
        pools.transaction {
            recipients.forEach { link ->
                val published = pools.selectOutForCompanion(link.companionUserId).awaitAsList()
                published.filterNot { it.mediaId in wanted }.forEach { gone ->
                    pools.softDeleteOut(now = now, companionUserId = link.companionUserId, mediaId = gone.mediaId)
                }
                wanted.values.forEach { item ->
                    val before = published.firstOrNull { it.mediaId == item.mediaId.toString() }
                    // Only re-stamp what actually changed: a stamp is what the
                    // server compares, so restamping an unchanged row would let
                    // this device beat another one's real edit for no reason.
                    if (before != null && before.unchangedFrom(item)) return@forEach
                    pools.upsertOut(
                        companionUserId = link.companionUserId,
                        mediaId = item.mediaId.toString(),
                        mediaType = item.mediaType.wireName,
                        title = item.title,
                        posterUrl = item.posterUrl,
                        genres = item.genres.joinToString(","),
                        runtimeMinutes = item.runtimeMinutes?.toLong(),
                        started = item.started,
                        seen = item.seen,
                        pinned = item.pinned,
                        updatedAtEpochMs = now,
                        isDirty = true,
                        deleted = false,
                    )
                }
            }
        }
    }

    private suspend fun CompanionLinkRow.toCompanion() = LinkedCompanion(
        userId = companionUserId,
        localName = localName,
        state = stateOf(state, remoteState, deleted),
        poolPublishedAtEpochMs = pools.lastPublishedAt(companionUserId).awaitAsOneOrNull()?.lastPublishedAtEpochMs,
    )

    private fun CompanionPoolInRow.toPoolItem() = PoolItem(
        mediaId = MediaId.parse(mediaId),
        mediaType = MediaType.fromWire(mediaType),
        title = title,
        posterUrl = posterUrl,
        genres = if (genres.isBlank()) emptyList() else genres.split(","),
        runtimeMinutes = runtimeMinutes?.toInt(),
        started = started,
        seen = seen,
        pinned = pinned,
    )

    private fun com.codingpit.muviss.core.database.CompanionPoolOut.unchangedFrom(item: PoolItem) = title == item.title &&
        posterUrl == item.posterUrl &&
        started == item.started &&
        seen == item.seen &&
        pinned == item.pinned &&
        runtimeMinutes?.toInt() == item.runtimeMinutes &&
        !deleted

    internal companion object {
        const val STATE_ACCEPTED = "ACCEPTED"
        const val STATE_INVITED = "INVITED"
        const val STATE_REVOKED = "REVOKED"

        /**
         * Both halves decide the state, which is why neither column alone is
         * enough: a link is an agreement between two rows with different owners.
         */
        fun stateOf(mine: String, theirs: String?, deleted: Boolean): CompanionState = when {
            deleted || mine == STATE_REVOKED || theirs == STATE_REVOKED -> CompanionState.REVOKED
            mine == STATE_ACCEPTED && theirs == STATE_ACCEPTED -> CompanionState.ACTIVE
            theirs == STATE_ACCEPTED -> CompanionState.AWAITING_CONFIRMATION
            else -> CompanionState.INVITED
        }
    }
}
