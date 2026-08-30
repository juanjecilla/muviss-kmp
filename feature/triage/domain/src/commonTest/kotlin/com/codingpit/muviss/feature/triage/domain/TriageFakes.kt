package com.codingpit.muviss.feature.triage.domain

import com.codingpit.muviss.feature.collection.api.CollectionApi
import com.codingpit.muviss.feature.collection.api.CollectionMembership
import com.codingpit.muviss.feature.collection.api.CollectionSummary
import com.codingpit.muviss.feature.collection.api.NewEpisodesResult
import com.codingpit.muviss.feature.progress.api.EpisodePlay
import com.codingpit.muviss.feature.progress.api.ProgressApi
import com.codingpit.muviss.feature.progress.api.WatchNextItem
import com.codingpit.muviss.feature.triage.api.TriageVerdict
import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.Season
import com.codingpit.muviss.models.WatchStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

internal class FakeTriageDecisionRepository : TriageDecisionRepository {
    val decisions = MutableStateFlow<Map<MediaId, TriageDecision>>(emptyMap())
    val restored = mutableListOf<MediaId>()

    override fun observeByVerdict(verdict: TriageVerdict): Flow<List<TriageDecision>> = decisions.map { all ->
        all.values.filter { it.verdict == verdict }.sortedByDescending { it.decidedAtEpochMs }
    }

    override fun observeDecidedIds(): Flow<Set<MediaId>> = decisions.map { it.keys }

    override fun observeDecision(mediaId: MediaId): Flow<TriageDecision?> = decisions.map { it[mediaId] }

    override suspend fun record(decision: TriageDecision) {
        decisions.value = decisions.value + (decision.mediaId to decision)
    }

    override suspend fun markResolved(mediaId: MediaId, resolved: Boolean) {
        decisions.value[mediaId]?.let { decisions.value = decisions.value + (mediaId to it.copy(resolved = resolved)) }
    }

    override suspend fun restore(mediaId: MediaId) {
        restored += mediaId
        decisions.value = decisions.value - mediaId
    }

    override suspend fun unresolved(): List<TriageDecision> = decisions.value.values.filterNot { it.resolved }
}

internal class FakeCollectionApi(initial: List<CollectionSummary> = emptyList()) : CollectionApi {
    val summaries = MutableStateFlow(initial)
    val added = mutableListOf<MediaDetails>()
    val removed = mutableListOf<MediaId>()

    override fun observeMembership(mediaId: MediaId): Flow<CollectionMembership?> = summaries.map { all ->
        all.firstOrNull { it.mediaId == mediaId }?.let { CollectionMembership(it.mediaId, it.favorite, it.notificationsMuted, it.rating, null) }
    }

    override fun observeSummaries(): Flow<List<CollectionSummary>> = summaries

    override suspend fun add(details: MediaDetails) {
        added += details
        summaries.value = summaries.value + CollectionSummary(
            mediaId = details.id,
            title = details.summary.title,
            posterUrl = details.summary.posterUrl,
            status = WatchStatus.NOT_STARTED,
        )
    }

    override suspend fun remove(mediaId: MediaId) {
        removed += mediaId
        summaries.value = summaries.value.filterNot { it.mediaId == mediaId }
    }

    override suspend fun setFavorite(mediaId: MediaId, favorite: Boolean) = Unit

    override suspend fun setNotificationsMuted(mediaId: MediaId, muted: Boolean) = Unit

    override suspend fun setRating(mediaId: MediaId, rating: Int?) = Unit

    override suspend fun setNote(mediaId: MediaId, note: String?) = Unit

    override suspend fun refreshAndFindNewEpisodes(): List<NewEpisodesResult> = emptyList()
}

internal class FakeProgressApi : ProgressApi {
    val ticked = mutableListOf<EpisodeId>()
    val markedAllAired = mutableListOf<Pair<List<Season>, Long>>()
    val moviesWatched = mutableListOf<MediaId>()
    val cleared = mutableListOf<MediaId>()

    override fun observeSeenEpisodes(mediaId: MediaId): Flow<Set<EpisodeId>> = MutableStateFlow(emptySet())

    // Watch-next (EPIC 22) — this fake's subject never asks for it.
    override fun observeWatchNext(): Flow<List<WatchNextItem>> = flowOf(emptyList())
    override suspend fun refreshWatchNextCatalogs() = Unit

    override fun observeSeenActivityEpochDays(): Flow<Set<Long>> = MutableStateFlow(emptySet())

    override suspend fun setEpisodeSeen(episodeId: EpisodeId, seen: Boolean) {
        ticked += episodeId
    }

    override fun observePlayCounts(mediaId: MediaId): Flow<Map<EpisodeId, Int>> = flowOf(emptyMap())
    override fun observePlays(episodeId: EpisodeId): Flow<List<EpisodePlay>> = flowOf(emptyList())

    override fun observeRewatchCounts(sinceEpochMs: Long): Flow<Map<MediaId, Int>> = flowOf(emptyMap())

    override fun observeRewatchTimestamps(sinceEpochMs: Long): Flow<List<Long>> = flowOf(emptyList())
    override suspend fun recordPlay(episodeId: EpisodeId) = Unit
    override suspend fun removeLatestPlay(episodeId: EpisodeId) = Unit
    override suspend fun clearPlays(episodeId: EpisodeId) = Unit
    override suspend fun markSeasonAiredSeen(season: Season, todayEpochDay: Long): List<EpisodeId> = emptyList()
    override suspend fun markShowAiredSeen(seasons: List<Season>, todayEpochDay: Long): List<EpisodeId> = emptyList()
    override suspend fun unmarkSeason(season: Season) = Unit
    override suspend fun unmarkShow(seasons: List<Season>) = Unit
    override suspend fun undoBulkMark(episodeIds: List<EpisodeId>) = Unit

    override suspend fun markPreviousSeen(seasons: List<Season>, target: EpisodeId) = error("triage uses markAllAiredSeen — see EpisodeOrdering.airedBy")

    override suspend fun markAllAiredSeen(seasons: List<Season>, todayEpochDay: Long) {
        markedAllAired += seasons to todayEpochDay
    }

    override suspend fun clearProgress(mediaId: MediaId) {
        cleared += mediaId
    }

    override suspend fun setMovieWatched(mediaId: MediaId, watched: Boolean) {
        moviesWatched += mediaId
    }
}

internal class FakeTriageDetailsSource(
    private val byId: Map<MediaId, MediaDetails> = emptyMap(),
    private val failure: Throwable? = null,
) : TriageDetailsSource {
    var fetchCalls = 0
        private set

    override suspend fun fetch(mediaId: MediaId): Result<MediaDetails> {
        fetchCalls++
        failure?.let { return Result.failure(it) }
        return byId[mediaId]?.let { Result.success(it) }
            ?: Result.failure(IllegalStateException("no details for $mediaId"))
    }
}
