@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.triage.data

import com.codingpit.muviss.feature.collection.api.CollectionApi
import com.codingpit.muviss.feature.collection.api.CollectionMembership
import com.codingpit.muviss.feature.collection.api.CollectionSummary
import com.codingpit.muviss.feature.collection.api.NewEpisodesResult
import com.codingpit.muviss.feature.collection.data.SqlDelightCollectionRepository
import com.codingpit.muviss.feature.progress.api.EpisodePlay
import com.codingpit.muviss.feature.progress.api.ProgressApi
import com.codingpit.muviss.feature.progress.api.WatchNextItem
import com.codingpit.muviss.feature.progress.data.SqlDelightProgressRepository
import com.codingpit.muviss.feature.progress.domain.EpisodeOrdering
import com.codingpit.muviss.feature.triage.domain.TriageDetailsSource
import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.Season
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * The real collection and progress slices, wired over the same in-memory
 * database as triage's own repository. Test-only dependencies (see this
 * module's `build.gradle.kts`) — production code reaches both features
 * through their `:api` alone (ADR 0004).
 *
 * These are deliberately thin: every method delegates straight to the real
 * repository, so what the integration test exercises is production logic, not
 * a re-implementation of it.
 */
internal class RealCollectionApi(private val repository: SqlDelightCollectionRepository) : CollectionApi {
    override fun observeMembership(mediaId: MediaId): Flow<CollectionMembership?> = repository.observeEntry(mediaId)
        .map { entry -> entry?.let { CollectionMembership(it.mediaId, it.favorite, it.notificationsMuted, it.rating, it.note) } }

    override fun observeSummaries(): Flow<List<CollectionSummary>> = repository.observeAll()
        .map { entries ->
            entries.map { entry ->
                CollectionSummary(
                    mediaId = entry.mediaId,
                    title = entry.title,
                    posterUrl = entry.posterUrl,
                    status = entry.status,
                    seenEpisodes = entry.seenEpisodes,
                    favorite = entry.favorite,
                    addedAtEpochMs = entry.addedAtEpochMs,
                )
            }
        }

    override suspend fun add(details: MediaDetails) = repository.upsertSnapshot(details)

    override suspend fun remove(mediaId: MediaId) = repository.remove(mediaId)

    override suspend fun setFavorite(mediaId: MediaId, favorite: Boolean) = repository.setFavorite(mediaId, favorite)

    override suspend fun setNotificationsMuted(mediaId: MediaId, muted: Boolean) = repository.setNotificationsMuted(mediaId, muted)

    override suspend fun setRating(mediaId: MediaId, rating: Int?) = repository.setRating(mediaId, rating)

    override suspend fun setNote(mediaId: MediaId, note: String?) = repository.setNote(mediaId, note)

    override suspend fun setRevisitWillingness(mediaId: MediaId, willing: Boolean?) = error("not used")

    override suspend fun setCoWatchPinned(mediaId: MediaId, pinned: Boolean) = error("not used")

    override suspend fun refreshAndFindNewEpisodes(): List<NewEpisodesResult> = emptyList()
}

internal class RealProgressApi(private val repository: SqlDelightProgressRepository) : ProgressApi {
    override fun observeSeenEpisodes(mediaId: MediaId): Flow<Set<EpisodeId>> = repository.observeForMedia(mediaId)
        .map { rows -> rows.filter { it.seen }.map { it.episodeId }.toSet() }

    // Watch-next (EPIC 22) — this fake's subject never asks for it.
    override fun observeWatchNext(): Flow<List<WatchNextItem>> = flowOf(emptyList())
    override suspend fun refreshWatchNextCatalogs() = Unit

    override fun observeSeenActivityEpochDays(): Flow<Set<Long>> = repository.observeSeenActivityEpochDays()

    override suspend fun setEpisodeSeen(episodeId: EpisodeId, seen: Boolean) = repository.setSeen(episodeId, seen)

    override fun observePlayCounts(mediaId: MediaId): Flow<Map<EpisodeId, Int>> = repository.observePlayCounts(mediaId)
    override fun observePlays(episodeId: EpisodeId): Flow<List<EpisodePlay>> = repository.observePlays(episodeId)

    override fun observeRewatchCounts(sinceEpochMs: Long): Flow<Map<MediaId, Int>> = repository.observeRewatchCounts(sinceEpochMs)
    override fun observeRewatchTimestamps(sinceEpochMs: Long): Flow<List<Long>> = repository.observeRewatchTimestamps(sinceEpochMs)
    override suspend fun recordPlay(episodeId: EpisodeId) = repository.recordPlay(episodeId)
    override suspend fun removeLatestPlay(episodeId: EpisodeId) = repository.removeLatestPlay(episodeId)
    override suspend fun clearPlays(episodeId: EpisodeId) = repository.clearPlays(episodeId)
    override suspend fun markSeasonAiredSeen(season: Season, todayEpochDay: Long): List<EpisodeId> = repository.recordPlaysForUnseen(EpisodeOrdering.airedBy(listOf(season), todayEpochDay))
    override suspend fun markShowAiredSeen(seasons: List<Season>, todayEpochDay: Long): List<EpisodeId> = repository.recordPlaysForUnseen(EpisodeOrdering.airedBy(seasons, todayEpochDay))
    override suspend fun unmarkSeason(season: Season) = repository.removeLatestPlays(season.episodes.map { it.id })
    override suspend fun unmarkShow(seasons: List<Season>) = repository.removeLatestPlays(seasons.flatMap { it.episodes }.map { it.id })
    override suspend fun undoBulkMark(episodeIds: List<EpisodeId>) = repository.removeLatestPlays(episodeIds)

    override suspend fun markPreviousSeen(seasons: List<Season>, target: EpisodeId) = repository.setSeenBulk(EpisodeOrdering.upToInclusive(seasons, target), seen = true)

    override suspend fun markAllAiredSeen(seasons: List<Season>, todayEpochDay: Long) = repository.setSeenBulk(EpisodeOrdering.airedBy(seasons, todayEpochDay), seen = true)

    override suspend fun clearProgress(mediaId: MediaId) = repository.clearForMedia(mediaId)

    override suspend fun setMovieWatched(mediaId: MediaId, watched: Boolean) = repository.setSeen(EpisodeId.forMovie(mediaId), watched)
}

/** Serves canned details and counts fetches, so the integration test can assert network cost. */
internal class CountingDetailsSource(private val byId: Map<MediaId, MediaDetails>) : TriageDetailsSource {
    var fetchCalls = 0
        private set

    override suspend fun fetch(mediaId: MediaId): Result<MediaDetails> {
        fetchCalls++
        return byId[mediaId]?.let { Result.success(it) } ?: Result.failure(IllegalStateException("no details for $mediaId"))
    }
}
