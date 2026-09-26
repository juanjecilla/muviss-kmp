package com.codingpit.muviss.feature.progress.domain

import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.feature.collection.api.CollectionApi
import com.codingpit.muviss.feature.collection.api.CollectionMembership
import com.codingpit.muviss.feature.collection.api.CollectionSummary
import com.codingpit.muviss.feature.collection.api.NewEpisodesResult
import com.codingpit.muviss.feature.progress.api.EpisodePlay
import com.codingpit.muviss.models.Episode
import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.Season
import com.codingpit.muviss.models.WatchStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * Fakes shared by [WatchNextUseCaseTest] and anything else exercising the
 * watch-next join. They live in `:domain`'s tests because the join does — the
 * same set used to sit in `ProgressViewModelTest` when the ViewModel owned it.
 */
fun collectionSummary(
    id: MediaId,
    title: String = id.toString(),
    status: WatchStatus = WatchStatus.WATCHING,
) = CollectionSummary(id, title, posterUrl = null, status = status)

class FakeCollectionApi(summaries: List<CollectionSummary>) : CollectionApi {
    val flow = MutableStateFlow(summaries)
    override fun observeMembership(mediaId: MediaId): Flow<CollectionMembership?> = error("not used")
    override fun observeSummaries(): Flow<List<CollectionSummary>> = flow
    override suspend fun add(details: MediaDetails) = error("not used")
    override suspend fun remove(mediaId: MediaId) = error("not used")
    override suspend fun setFavorite(mediaId: MediaId, favorite: Boolean) = error("not used")
    override suspend fun setNotificationsMuted(mediaId: MediaId, muted: Boolean) = error("not used")
    override suspend fun setRating(mediaId: MediaId, rating: Int?) = error("not used")
    override suspend fun setNote(mediaId: MediaId, note: String?) = error("not used")

    override suspend fun setRevisitWillingness(mediaId: MediaId, willing: Boolean?) = error("not used")

    override suspend fun setCoWatchPinned(mediaId: MediaId, pinned: Boolean) = error("not used")
    override suspend fun refreshAndFindNewEpisodes(): List<NewEpisodesResult> = error("not used")
}

class FakeProgressRepository : ProgressRepository {
    private val seenByMedia = mutableMapOf<MediaId, MutableStateFlow<Set<EpisodeId>>>()
    val tickedEpisodes = mutableListOf<Pair<EpisodeId, Boolean>>()

    private fun flowFor(mediaId: MediaId) = seenByMedia.getOrPut(mediaId) { MutableStateFlow(emptySet()) }

    override fun observeForMedia(mediaId: MediaId): Flow<List<EpisodeProgress>> = flowFor(mediaId)
        .map { seen -> seen.map { EpisodeProgress(it, seen = true, updatedAtEpochMs = 0L) } }

    override fun observeSeenCount(mediaId: MediaId): Flow<Int> = flowFor(mediaId).map { it.size }

    override fun observeSeenActivityEpochDays(): Flow<Set<Long>> = error("not used")

    override suspend fun setSeen(episodeId: EpisodeId, seen: Boolean) {
        tickedEpisodes += episodeId to seen
        val flow = flowFor(episodeId.show)
        flow.value = if (seen) flow.value + episodeId else flow.value - episodeId
    }

    override suspend fun setSeenBulk(episodeIds: List<EpisodeId>, seen: Boolean) {
        episodeIds.forEach { setSeen(it, seen) }
    }

    override suspend fun clearForMedia(mediaId: MediaId) {
        flowFor(mediaId).value = emptySet()
    }

    // Rewatch history (ADR 0011): watch-next only ever asks "seen or not", so
    // the play side is modelled as one viewing per seen episode.
    override fun observePlayCounts(mediaId: MediaId): Flow<Map<EpisodeId, Int>> = flowFor(mediaId).map { seen -> seen.associateWith { 1 } }

    override fun observePlays(episodeId: EpisodeId): Flow<List<EpisodePlay>> = flowFor(episodeId.show).map { seen ->
        if (episodeId in seen) listOf(EpisodePlay(episodeId, 0L)) else emptyList()
    }

    override fun observeRewatchCounts(sinceEpochMs: Long): Flow<Map<MediaId, Int>> = flowOf(emptyMap())

    override fun observeRewatchTimestamps(sinceEpochMs: Long): Flow<List<Long>> = flowOf(emptyList())

    override suspend fun recordPlay(episodeId: EpisodeId) = setSeen(episodeId, true)

    override suspend fun recordPlaysForUnseen(episodeIds: List<EpisodeId>): List<EpisodeId> {
        val unseen = episodeIds.filterNot { it in flowFor(it.show).value }
        unseen.forEach { setSeen(it, true) }
        return unseen
    }

    override suspend fun removeLatestPlay(episodeId: EpisodeId) = setSeen(episodeId, false)

    override suspend fun removeLatestPlays(episodeIds: List<EpisodeId>) {
        episodeIds.filter { it in flowFor(it.show).value }.forEach { setSeen(it, false) }
    }

    override suspend fun clearPlays(episodeId: EpisodeId) = setSeen(episodeId, false)
}

class MapEpisodeCatalogSource(private val bySeasons: Map<MediaId, List<Season>>) : EpisodeCatalogSource {
    override suspend fun fetch(mediaId: MediaId): Result<List<Season>> = Result.success(bySeasons[mediaId].orEmpty())
}

class FixedClock(private val millis: Long) : AppClock {
    override fun nowEpochMs(): Long = millis
}

fun testEpisode(show: MediaId, season: Int, number: Int, airDay: Long?) = Episode(
    id = EpisodeId(show, season, number),
    seasonNumber = season,
    episodeNumber = number,
    name = "S${season}E$number",
    airDateEpochDay = airDay,
)
