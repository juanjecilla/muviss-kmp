package com.codingpit.muviss.feature.progress.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOne
import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.core.common.AppDispatchers
import com.codingpit.muviss.core.common.epochDayOf
import com.codingpit.muviss.core.database.EpisodeProgressQueries
import com.codingpit.muviss.feature.progress.domain.EpisodeProgress
import com.codingpit.muviss.feature.progress.domain.ProgressRepository
import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.MediaId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import com.codingpit.muviss.core.database.EpisodeProgress as EpisodeProgressRow

/**
 * SQLDelight-backed [ProgressRepository] over `EpisodeProgress.sq`. Every
 * tick goes through `upsert` (`INSERT OR REPLACE`) rather than the schema's
 * dedicated `setSeen` `UPDATE`, since the very first tick of an episode has
 * no existing row to update — `upsert` handles create-or-update uniformly
 * given the full [EpisodeId] identity a caller always has in hand.
 */
class SqlDelightProgressRepository(
    private val queries: EpisodeProgressQueries,
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

    override fun observeSeenActivityEpochDays(): Flow<Set<Long>> = queries.selectSeenUpdatedAt()
        .asFlow()
        .mapToList(dispatchers.io)
        .map { timestamps -> timestamps.map(::epochDayOf).toSet() }

    override suspend fun setSeen(episodeId: EpisodeId, seen: Boolean) = withContext(dispatchers.io) {
        upsert(episodeId, seen, clock.nowEpochMs())
    }

    override suspend fun setSeenBulk(episodeIds: List<EpisodeId>, seen: Boolean) = withContext(dispatchers.io) {
        val now = clock.nowEpochMs()
        queries.transaction {
            episodeIds.forEach { episodeId -> upsert(episodeId, seen, now) }
        }
    }

    private fun upsert(episodeId: EpisodeId, seen: Boolean, now: Long) {
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
