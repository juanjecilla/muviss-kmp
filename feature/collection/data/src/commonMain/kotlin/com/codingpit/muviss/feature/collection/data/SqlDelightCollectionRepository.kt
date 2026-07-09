package com.codingpit.muviss.feature.collection.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.core.common.AppDispatchers
import com.codingpit.muviss.core.database.CollectionEntryQueries
import com.codingpit.muviss.feature.collection.domain.CollectionEntry
import com.codingpit.muviss.feature.collection.domain.CollectionRepository
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaType
import com.codingpit.muviss.models.ProductionStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import com.codingpit.muviss.core.database.CollectionEntry as CollectionEntryRow

private const val MILLIS_PER_DAY = 86_400_000L

/**
 * SQLDelight-backed [CollectionRepository] over `CollectionEntry.sq`.
 * [upsertSnapshot] merges into any existing row: re-saving a removed title
 * un-deletes it, and refreshing a saved title's snapshot preserves its
 * favorite flag and original add date.
 */
class SqlDelightCollectionRepository(
    private val queries: CollectionEntryQueries,
    private val dispatchers: AppDispatchers,
    private val clock: AppClock,
) : CollectionRepository {

    override fun observeAll(): Flow<List<CollectionEntry>> = queries.selectAll()
        .asFlow()
        .mapToList(dispatchers.io)
        .map { rows -> rows.map(::toDomain) }

    override fun observeEntry(mediaId: MediaId): Flow<CollectionEntry?> = queries.selectById(mediaId.toString())
        .asFlow()
        .mapToOneOrNull(dispatchers.io)
        .map { row -> row?.takeUnless { it.deleted }?.let(::toDomain) }

    override suspend fun upsertSnapshot(details: MediaDetails) = withContext(dispatchers.io) {
        val id = details.summary.id.toString()
        val existing = queries.selectById(id).executeAsOneOrNull()
        val now = clock.nowEpochMs()
        val todayEpochDay = now / MILLIS_PER_DAY
        queries.upsert(
            mediaId = id,
            mediaType = details.type.wireName,
            title = details.summary.title,
            posterUrl = details.summary.posterUrl,
            releaseYear = details.summary.year?.toLong(),
            productionStatus = details.productionStatus.name,
            totalEpisodes = details.totalEpisodeCount().toLong(),
            airedEpisodes = details.airedEpisodeCount(todayEpochDay).toLong(),
            favorite = existing?.favorite ?: false,
            addedAtEpochMs = existing?.addedAtEpochMs ?: now,
            updatedAtEpochMs = now,
            isDirty = true,
            deleted = false,
        )
        Unit
    }

    override suspend fun remove(mediaId: MediaId) = withContext(dispatchers.io) {
        queries.softDelete(now = clock.nowEpochMs(), mediaId = mediaId.toString())
        Unit
    }

    override suspend fun setFavorite(mediaId: MediaId, favorite: Boolean) = withContext(dispatchers.io) {
        queries.setFavorite(favorite = favorite, now = clock.nowEpochMs(), mediaId = mediaId.toString())
        Unit
    }

    private fun toDomain(row: CollectionEntryRow): CollectionEntry = CollectionEntry(
        mediaId = MediaId.parse(row.mediaId),
        title = row.title,
        posterUrl = row.posterUrl,
        releaseYear = row.releaseYear?.toInt(),
        productionStatus = ProductionStatus.valueOf(row.productionStatus),
        totalEpisodes = row.totalEpisodes.toInt(),
        airedEpisodes = row.airedEpisodes.toInt(),
        favorite = row.favorite,
        addedAtEpochMs = row.addedAtEpochMs,
    )
}

// Movies count as a single "episode": both aired and total are 1 once released,
// matching WatchProgress's movie convention (core/model/WatchProgress.kt).
private fun MediaDetails.totalEpisodeCount(): Int = when (type) {
    MediaType.MOVIE -> 1
    MediaType.TV -> seasons.sumOf { it.episodes.size }
}

// NOTE: TmdbMapper does not populate Episode.airDateEpochDay yet (see
// core/network/tmdb/TmdbMapper.seasonToModel), so this is always 0 for TV
// today. The logic here is correct and will start reflecting real aired
// counts as soon as that gap is closed — see the deviation noted in the
// EPIC 2 report. It has no visible effect yet because WatchStatusCalculator
// derives NOT_STARTED whenever seenEpisodes is 0, regardless of airedEpisodes.
private fun MediaDetails.airedEpisodeCount(todayEpochDay: Long): Int = when (type) {
    MediaType.MOVIE -> 1

    MediaType.TV -> seasons.sumOf { season ->
        season.episodes.count { episode ->
            val airDate = episode.airDateEpochDay
            airDate != null && airDate <= todayEpochDay
        }
    }
}
