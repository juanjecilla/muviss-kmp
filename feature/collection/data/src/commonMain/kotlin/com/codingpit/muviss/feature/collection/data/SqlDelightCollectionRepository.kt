package com.codingpit.muviss.feature.collection.data

import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.core.common.AppDispatchers
import com.codingpit.muviss.core.common.todayEpochDay
import com.codingpit.muviss.core.database.CollectionEntryQueries
import com.codingpit.muviss.feature.collection.domain.CollectionEntry
import com.codingpit.muviss.feature.collection.domain.CollectionRepository
import com.codingpit.muviss.feature.collection.domain.airedEpisodeCount
import com.codingpit.muviss.feature.collection.domain.totalEpisodeCount
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
 * un-deletes it, and saving a title that is already in the library preserves
 * its favorite flag, rating, note, and original add date. [refreshSnapshot] is
 * the provider-data half on its own, and is not a synced write (EPIC 39).
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
        val existing = queries.selectById(id).awaitAsOneOrNull()
        // A title that is already in the library changes nothing the user said
        // by being "saved" again, so it takes the refresh path and is not synced.
        if (existing != null && !existing.deleted) {
            updateSnapshot(id, details)
            return@withContext
        }
        val now = clock.nowEpochMs()
        queries.upsert(
            mediaId = id,
            mediaType = details.type.wireName,
            title = details.summary.title,
            posterUrl = details.summary.posterUrl,
            releaseYear = details.summary.year?.toLong(),
            productionStatus = details.productionStatus.name,
            totalEpisodes = details.totalEpisodeCount().toLong(),
            airedEpisodes = details.airedEpisodeCount(clock.todayEpochDay()).toLong(),
            favorite = existing?.favorite ?: false,
            genres = details.genres.joinToString(","),
            runtimeMinutes = details.estimatedRuntimeMinutes()?.toLong(),
            addedAtEpochMs = existing?.addedAtEpochMs ?: now,
            // A genuine user action — a new title, or a removed one added back — so it is stamped and synced.
            updatedAtEpochMs = now,
            isDirty = true,
            deleted = false,
            notificationsMuted = existing?.notificationsMuted ?: false,
            rating = existing?.rating,
            note = existing?.note,
            // Same category as rating/note: the user's own answers survive a
            // title being removed and added back (EPIC 41, ADR 0022).
            revisitWillingness = existing?.revisitWillingness,
            coWatchPinned = existing?.coWatchPinned ?: false,
        )
        Unit
    }

    override suspend fun refreshSnapshot(details: MediaDetails) = withContext(dispatchers.io) {
        updateSnapshot(details.summary.id.toString(), details)
    }

    /**
     * Writes only what the provider said, to a row that is live. The row's stamp
     * and dirty flag are deliberately left alone (see `updateSnapshot` in
     * `CollectionEntry.sq`): a refresh is not a user edit and must not compete
     * with one in last-write-wins.
     */
    private suspend fun updateSnapshot(id: String, details: MediaDetails) {
        queries.updateSnapshot(
            title = details.summary.title,
            posterUrl = details.summary.posterUrl,
            releaseYear = details.summary.year?.toLong(),
            productionStatus = details.productionStatus.name,
            totalEpisodes = details.totalEpisodeCount().toLong(),
            airedEpisodes = details.airedEpisodeCount(clock.todayEpochDay()).toLong(),
            genres = details.genres.joinToString(","),
            runtimeMinutes = details.estimatedRuntimeMinutes()?.toLong(),
            mediaId = id,
        )
    }

    override suspend fun remove(mediaId: MediaId) = withContext(dispatchers.io) {
        queries.softDelete(now = clock.nowEpochMs(), mediaId = mediaId.toString())
        Unit
    }

    override suspend fun setFavorite(mediaId: MediaId, favorite: Boolean) = withContext(dispatchers.io) {
        queries.setFavorite(favorite = favorite, now = clock.nowEpochMs(), mediaId = mediaId.toString())
        Unit
    }

    override suspend fun setNotificationsMuted(mediaId: MediaId, muted: Boolean) = withContext(dispatchers.io) {
        queries.setNotificationsMuted(muted = muted, now = clock.nowEpochMs(), mediaId = mediaId.toString())
        Unit
    }

    override suspend fun setRating(mediaId: MediaId, rating: Int?) = withContext(dispatchers.io) {
        queries.setRating(rating = rating?.toLong(), now = clock.nowEpochMs(), mediaId = mediaId.toString())
        Unit
    }

    override suspend fun setNote(mediaId: MediaId, note: String?) = withContext(dispatchers.io) {
        queries.setNote(note = note, now = clock.nowEpochMs(), mediaId = mediaId.toString())
        Unit
    }

    override suspend fun setRevisitWillingness(mediaId: MediaId, willing: Boolean?) = withContext(dispatchers.io) {
        queries.setRevisitWillingness(willing = willing, now = clock.nowEpochMs(), mediaId = mediaId.toString())
        Unit
    }

    override suspend fun setCoWatchPinned(mediaId: MediaId, pinned: Boolean) = withContext(dispatchers.io) {
        queries.setCoWatchPinned(pinned = pinned, now = clock.nowEpochMs(), mediaId = mediaId.toString())
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
        notificationsMuted = row.notificationsMuted,
        rating = row.rating?.toInt(),
        note = row.note,
        revisitWillingness = row.revisitWillingness,
        coWatchPinned = row.coWatchPinned,
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
