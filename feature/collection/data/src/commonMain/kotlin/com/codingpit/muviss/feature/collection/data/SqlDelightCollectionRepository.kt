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
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaType
import com.codingpit.muviss.models.ProductionStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * SQLDelight-backed [CollectionRepository] over `CollectionEntry.sq`.
 * [upsertSnapshot] merges into any existing row: re-saving a removed title
 * un-deletes it, and saving a title that is already in the library preserves
 * its favorite flag, rating, note, and original add date. [refreshSnapshot] is
 * the provider-data half on its own, and is not a synced write (EPIC 39).
 *
 * [seenEpisodes][CollectionEntry.seenEpisodes] is never stored here: both
 * reads join it in from `episodeProgress` in SQL (`selectAllWithSeen`), so
 * [CollectionEntry.status] reflects real ticks (ADR 0005) and the Library is
 * one query per invalidation however large it is (EPIC 28, #70). It used to
 * open one `ProgressApi.observeSeenEpisodes` Flow per entry. Like the data
 * export, this reads the shared table rather than the progress feature's
 * `:api`: a count over `:core:database`'s rows, not progress's domain.
 */
class SqlDelightCollectionRepository(
    private val queries: CollectionEntryQueries,
    private val dispatchers: AppDispatchers,
    private val clock: AppClock,
) : CollectionRepository {

    override fun observeAll(): Flow<List<CollectionEntry>> = queries.selectAllWithSeen(::entryOf)
        .asFlow()
        .mapToList(dispatchers.io)

    override fun observeEntry(mediaId: MediaId): Flow<CollectionEntry?> = queries.selectByIdWithSeen(mediaId.toString(), ::visibleEntryOf)
        .asFlow()
        .mapToOneOrNull(dispatchers.io)
        .map { it?.entry }

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

    /** A row of `selectByIdWithSeen`, with a deleted entry read as absent. A wrapper because a query mapper cannot return null. */
    private class Visible(val entry: CollectionEntry?)

    @Suppress("LongParameterList", "UNUSED_PARAMETER") // the query's columns, in order; the sync bookkeeping ones are not the domain's
    private fun visibleEntryOf(
        mediaId: String,
        mediaType: String,
        title: String,
        posterUrl: String?,
        releaseYear: Long?,
        productionStatus: String,
        totalEpisodes: Long,
        airedEpisodes: Long,
        favorite: Boolean,
        genres: String,
        runtimeMinutes: Long?,
        addedAtEpochMs: Long,
        updatedAtEpochMs: Long,
        isDirty: Boolean,
        deleted: Boolean,
        notificationsMuted: Boolean,
        rating: Long?,
        note: String?,
        revisitWillingness: Boolean?,
        coWatchPinned: Boolean,
        seenCount: Long,
    ): Visible = Visible(
        entry = if (deleted) {
            null
        } else {
            entryOf(
                mediaId, mediaType, title, posterUrl, releaseYear, productionStatus, totalEpisodes, airedEpisodes,
                favorite, genres, runtimeMinutes, addedAtEpochMs, updatedAtEpochMs, isDirty, deleted,
                notificationsMuted, rating, note, revisitWillingness, coWatchPinned, seenCount,
            )
        },
    )

    @Suppress("LongParameterList", "UNUSED_PARAMETER") // the query's columns, in order; the sync bookkeeping ones are not the domain's
    private fun entryOf(
        mediaId: String,
        mediaType: String,
        title: String,
        posterUrl: String?,
        releaseYear: Long?,
        productionStatus: String,
        totalEpisodes: Long,
        airedEpisodes: Long,
        favorite: Boolean,
        genres: String,
        runtimeMinutes: Long?,
        addedAtEpochMs: Long,
        updatedAtEpochMs: Long,
        isDirty: Boolean,
        deleted: Boolean,
        notificationsMuted: Boolean,
        rating: Long?,
        note: String?,
        revisitWillingness: Boolean?,
        coWatchPinned: Boolean,
        seenCount: Long,
    ): CollectionEntry = CollectionEntry(
        mediaId = MediaId.parse(mediaId),
        title = title,
        posterUrl = posterUrl,
        releaseYear = releaseYear?.toInt(),
        productionStatus = ProductionStatus.valueOf(productionStatus),
        totalEpisodes = totalEpisodes.toInt(),
        airedEpisodes = airedEpisodes.toInt(),
        favorite = favorite,
        addedAtEpochMs = addedAtEpochMs,
        seenEpisodes = seenCount.toInt(),
        genres = genres.toGenreList(),
        runtimeMinutes = runtimeMinutes?.toInt(),
        notificationsMuted = notificationsMuted,
        rating = rating?.toInt(),
        note = note,
        revisitWillingness = revisitWillingness,
        coWatchPinned = coWatchPinned,
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
