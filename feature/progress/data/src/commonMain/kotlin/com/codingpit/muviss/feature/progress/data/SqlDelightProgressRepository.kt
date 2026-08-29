package com.codingpit.muviss.feature.progress.data

import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOne
import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.core.common.AppDispatchers
import com.codingpit.muviss.core.common.epochDayOf
import com.codingpit.muviss.core.database.EpisodePlayQueries
import com.codingpit.muviss.core.database.EpisodeProgressQueries
import com.codingpit.muviss.feature.progress.api.EpisodePlay
import com.codingpit.muviss.feature.progress.domain.EpisodeProgress
import com.codingpit.muviss.feature.progress.domain.ProgressRepository
import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.MediaId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import com.codingpit.muviss.core.database.EpisodeProgress as EpisodeProgressRow

/**
 * SQLDelight-backed [ProgressRepository] over `EpisodeProgress.sq` and
 * `EpisodePlay.sq`.
 *
 * Every tick goes through `upsert` (`INSERT OR REPLACE`) rather than the
 * schema's dedicated `setSeen` `UPDATE`, since the very first tick of an
 * episode has no existing row to update — `upsert` handles create-or-update
 * uniformly given the full [EpisodeId] identity a caller always has in hand.
 *
 * `seen` and the play rows are always written together, inside one
 * transaction, and only here (ADR 0011). `seen` remains the stored column —
 * status derivation and the sync change-log key off it, and un-ticking
 * propagates as `seen = 0` — while the play rows carry the history it can't
 * express. Keeping both writes in one place is what stops "seen with no
 * viewings" from ever existing.
 */
class SqlDelightProgressRepository(
    private val queries: EpisodeProgressQueries,
    private val playQueries: EpisodePlayQueries,
    private val dispatchers: AppDispatchers,
    private val clock: AppClock,
) : ProgressRepository {

    override fun observeForMedia(mediaId: MediaId): Flow<List<EpisodeProgress>> = queries.selectForMedia(mediaId.toString())
        .asFlow()
        .mapToList(dispatchers.io)
        .map { rows -> rows.map(::toDomain) }

    override fun observeSeenCount(mediaId: MediaId): Flow<Int> = queries.countSeenForMedia(mediaId.toString())
        .asFlow()
        .mapToOne(dispatchers.io)
        .map { it.toInt() }

    override fun observeSeenActivityEpochDays(): Flow<Set<Long>> = playQueries.selectAllWatchedAt()
        .asFlow()
        .mapToList(dispatchers.io)
        .map { timestamps -> timestamps.map(::epochDayOf).toSet() }

    override fun observePlayCounts(mediaId: MediaId): Flow<Map<EpisodeId, Int>> = playQueries.countsForMedia(mediaId.toString())
        .asFlow()
        .mapToList(dispatchers.io)
        .map { rows -> rows.associate { EpisodeId.parse(it.episodeId) to it.playCount.toInt() } }

    override fun observePlays(episodeId: EpisodeId): Flow<List<EpisodePlay>> = playQueries.selectForEpisode(episodeId.toString())
        .asFlow()
        .mapToList(dispatchers.io)
        .map { rows -> rows.map { EpisodePlay(episodeId, it.watchedAtEpochMs) } }

    override fun observeRewatchCounts(sinceEpochMs: Long): Flow<Map<MediaId, Int>> = playQueries.rewatchCountsByMedia(sinceEpochMs)
        .asFlow()
        .mapToList(dispatchers.io)
        .map { rows -> rows.associate { MediaId.parse(it.mediaId) to it.rewatches.toInt() } }

    override fun observeRewatchTimestamps(sinceEpochMs: Long): Flow<List<Long>> = playQueries.rewatchTimestamps(sinceEpochMs)
        .asFlow()
        .mapToList(dispatchers.io)

    override suspend fun setSeen(episodeId: EpisodeId, seen: Boolean) = withContext(dispatchers.io) {
        val now = clock.nowEpochMs()
        queries.transaction {
            if (seen) recordFirstPlayIfUnseen(episodeId, now) else forget(episodeId, now)
        }
    }

    override suspend fun setSeenBulk(episodeIds: List<EpisodeId>, seen: Boolean) = withContext(dispatchers.io) {
        val now = clock.nowEpochMs()
        queries.transaction {
            episodeIds.forEach { episodeId ->
                if (seen) recordFirstPlayIfUnseen(episodeId, now) else forget(episodeId, now)
            }
        }
    }

    override suspend fun recordPlay(episodeId: EpisodeId) = withContext(dispatchers.io) {
        val now = clock.nowEpochMs()
        queries.transaction {
            insertPlay(episodeId, now)
            upsertSeen(episodeId, seen = true, now = now)
        }
    }

    override suspend fun recordPlaysForUnseen(episodeIds: List<EpisodeId>): List<EpisodeId> = withContext(dispatchers.io) {
        val now = clock.nowEpochMs()
        val written = mutableListOf<EpisodeId>()
        queries.transaction {
            episodeIds.forEach { episodeId ->
                if (playCountOf(episodeId) == 0L) {
                    insertPlay(episodeId, now)
                    upsertSeen(episodeId, seen = true, now = now)
                    written += episodeId
                }
            }
        }
        written
    }

    override suspend fun removeLatestPlay(episodeId: EpisodeId) = withContext(dispatchers.io) {
        val now = clock.nowEpochMs()
        queries.transaction { dropLatestPlay(episodeId, now) }
    }

    override suspend fun removeLatestPlays(episodeIds: List<EpisodeId>) = withContext(dispatchers.io) {
        val now = clock.nowEpochMs()
        queries.transaction {
            episodeIds.forEach { episodeId -> dropLatestPlay(episodeId, now) }
        }
    }

    override suspend fun clearPlays(episodeId: EpisodeId) = withContext(dispatchers.io) {
        val now = clock.nowEpochMs()
        queries.transaction { forget(episodeId, now) }
    }

    override suspend fun clearForMedia(mediaId: MediaId) = withContext(dispatchers.io) {
        val now = clock.nowEpochMs()
        queries.transaction {
            playQueries.deleteAllForMedia(mediaId.toString())
            queries.clearForMedia(now = now, mediaId = mediaId.toString())
        }
    }

    /**
     * Re-asserting "seen" is not "watched again", so this is a no-op once the
     * episode has any history — the distinct action is [recordPlay].
     */
    private suspend fun recordFirstPlayIfUnseen(episodeId: EpisodeId, now: Long) {
        if (playCountOf(episodeId) == 0L) insertPlay(episodeId, now)
        upsertSeen(episodeId, seen = true, now = now)
    }

    private suspend fun forget(episodeId: EpisodeId, now: Long) {
        playQueries.deleteAllForEpisode(episodeId.toString())
        upsertSeen(episodeId, seen = false, now = now)
    }

    /** `seen` follows the remaining history: the last viewing removed is what un-ticks the episode. */
    private suspend fun dropLatestPlay(episodeId: EpisodeId, now: Long) {
        if (playCountOf(episodeId) == 0L) return
        playQueries.deleteLatestForEpisode(episodeId.toString())
        upsertSeen(episodeId, seen = playCountOf(episodeId) > 0L, now = now)
    }

    private suspend fun playCountOf(episodeId: EpisodeId): Long = playQueries.countForEpisode(episodeId.toString()).awaitAsOne()

    private suspend fun insertPlay(episodeId: EpisodeId, now: Long) {
        playQueries.insert(
            episodeId = episodeId.toString(),
            mediaId = episodeId.show.toString(),
            watchedAtEpochMs = now,
            isDirty = true,
        )
    }

    private suspend fun upsertSeen(episodeId: EpisodeId, seen: Boolean, now: Long) {
        queries.upsert(
            episodeId = episodeId.toString(),
            mediaId = episodeId.show.toString(),
            seasonNumber = episodeId.seasonNumber.toLong(),
            episodeNumber = episodeId.episodeNumber.toLong(),
            seen = seen,
            updatedAtEpochMs = now,
            isDirty = true,
        )
    }

    private fun toDomain(row: EpisodeProgressRow): EpisodeProgress = EpisodeProgress(
        episodeId = EpisodeId(
            show = MediaId.parse(row.mediaId),
            seasonNumber = row.seasonNumber.toInt(),
            episodeNumber = row.episodeNumber.toInt(),
        ),
        seen = row.seen,
        updatedAtEpochMs = row.updatedAtEpochMs,
    )
}
