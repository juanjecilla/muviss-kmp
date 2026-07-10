package com.codingpit.muviss.feature.collection.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.core.common.AppDispatchers
import com.codingpit.muviss.core.common.todayEpochDay
import com.codingpit.muviss.core.database.CollectionEntryQueries
import com.codingpit.muviss.feature.collection.domain.CollectionEntry
import com.codingpit.muviss.feature.collection.domain.CollectionRepository
import com.codingpit.muviss.feature.progress.api.ProgressApi
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaType
import com.codingpit.muviss.models.ProductionStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import com.codingpit.muviss.core.database.CollectionEntry as CollectionEntryRow

/**
 * SQLDelight-backed [CollectionRepository] over `CollectionEntry.sq`.
 * [upsertSnapshot] merges into any existing row: re-saving a removed title
 * un-deletes it, and refreshing a saved title's snapshot preserves its
 * favorite flag and original add date.
 *
 * [seenEpisodes][CollectionEntry.seenEpisodes] is never stored here — it is
 * joined in reactively from the progress feature via [progressApi] (its
 * `:api`, per ADR 0004's cross-feature rule) so [CollectionEntry.status]
 * reflects real ticks (ADR 0005).
 */
class SqlDelightCollectionRepository(
    private val queries: CollectionEntryQueries,
    private val dispatchers: AppDispatchers,
    private val clock: AppClock,
    private val progressApi: ProgressApi,
) : CollectionRepository {

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeAll(): Flow<List<CollectionEntry>> = queries.selectAll()
        .asFlow()
        .mapToList(dispatchers.io)
        .flatMapLatest { rows -> combineWithProgress(rows) }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeEntry(mediaId: MediaId): Flow<CollectionEntry?> = queries.selectById(mediaId.toString())
        .asFlow()
        .mapToOneOrNull(dispatchers.io)
        .flatMapLatest { row ->
            val visible = row?.takeUnless { it.deleted }
            if (visible == null) flowOf(null) else withSeenEpisodes(visible)
        }

    /** Joins each row with its live seen-episode count, recombining whenever either side changes. */
    private fun combineWithProgress(rows: List<CollectionEntryRow>): Flow<List<CollectionEntry>> = when {
        rows.isEmpty() -> flowOf(emptyList())
        else -> combine(rows.map { row -> withSeenEpisodes(row) }) { it.toList() }
    }

    private fun withSeenEpisodes(row: CollectionEntryRow): Flow<CollectionEntry> = progressApi
        .observeSeenEpisodes(MediaId.parse(row.mediaId))
        .map { seen -> toDomain(row, seenEpisodes = seen.size) }

    override suspend fun upsertSnapshot(details: MediaDetails) = withContext(dispatchers.io) {
        val id = details.summary.id.toString()
        val existing = queries.selectById(id).executeAsOneOrNull()
        val now = clock.nowEpochMs()
        val todayEpochDay = clock.todayEpochDay()
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
            genres = details.genres.joinToString(","),
            runtimeMinutes = details.estimatedRuntimeMinutes()?.toLong(),
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

    private fun toDomain(row: CollectionEntryRow, seenEpisodes: Int = 0): CollectionEntry = CollectionEntry(
        mediaId = MediaId.parse(row.mediaId),
        title = row.title,
        posterUrl = row.posterUrl,
        releaseYear = row.releaseYear?.toInt(),
        productionStatus = ProductionStatus.valueOf(row.productionStatus),
        totalEpisodes = row.totalEpisodes.toInt(),
        airedEpisodes = row.airedEpisodes.toInt(),
        favorite = row.favorite,
        addedAtEpochMs = row.addedAtEpochMs,
        seenEpisodes = seenEpisodes,
        genres = row.genres.toGenreList(),
        runtimeMinutes = row.runtimeMinutes?.toInt(),
    )
}

private fun String.toGenreList(): List<String> = if (isBlank()) emptyList() else split(",")

/**
 * The value stored in `collectionEntry.runtimeMinutes` for stats' hours-watched
 * estimate: a movie's own runtime, or a TV show's average per-episode runtime
 * across whichever episodes TMDB reported one for (not every episode always
 * carries it) — null if none do, so the profile feature falls back to a fixed
 * default per episode (see `ProfileStatsCalculator`).
 */
private fun MediaDetails.estimatedRuntimeMinutes(): Int? = when (type) {
    MediaType.MOVIE -> runtimeMinutes
    MediaType.TV -> seasons.flatMap { it.episodes }.mapNotNull { it.runtimeMinutes }.averageOrNull()
}

private fun List<Int>.averageOrNull(): Int? = if (isEmpty()) null else (sum().toDouble() / size).roundToInt()

// Movies count as a single "episode": both aired and total are 1 once released,
// matching WatchProgress's movie convention (core/model/WatchProgress.kt).
private fun MediaDetails.totalEpisodeCount(): Int = when (type) {
    MediaType.MOVIE -> 1
    MediaType.TV -> seasons.sumOf { it.episodes.size }
}

private fun MediaDetails.airedEpisodeCount(todayEpochDay: Long): Int = when (type) {
    MediaType.MOVIE -> 1

    MediaType.TV -> seasons.sumOf { season ->
        season.episodes.count { episode ->
            val airDate = episode.airDateEpochDay
            airDate != null && airDate <= todayEpochDay
        }
    }
}
