package com.codingpit.muviss.feature.search.ui

import com.codingpit.muviss.feature.collection.api.CollectionApi
import com.codingpit.muviss.feature.collection.api.CollectionMembership
import com.codingpit.muviss.feature.collection.api.CollectionSummary
import com.codingpit.muviss.feature.collection.api.NewEpisodesResult
import com.codingpit.muviss.feature.search.domain.SearchRepository
import com.codingpit.muviss.feature.triage.api.SkippedTitle
import com.codingpit.muviss.feature.triage.api.SnoozedTitle
import com.codingpit.muviss.feature.triage.api.TriageApi
import com.codingpit.muviss.feature.triage.api.TriageDecisionSummary
import com.codingpit.muviss.feature.triage.api.TriageVerdict
import com.codingpit.muviss.models.EpisodeDetails
import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.Genre
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.MediaType
import com.codingpit.muviss.models.PagedResult
import com.codingpit.muviss.models.WatchProviders
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * Stand-in for the triage feature. Search reads it for three things only:
 * excluding skipped and snoozed titles from "For you", and the Detail
 * screen's two undo lines.
 */
internal class FakeTriageApi(initial: Map<MediaId, TriageVerdict> = emptyMap()) : TriageApi {
    val decisions = MutableStateFlow(initial)
    val restored = mutableListOf<MediaId>()

    override fun observeDecidedIds(): Flow<Set<MediaId>> = decisions.map { it.keys }

    override fun observeSkipped(): Flow<List<SkippedTitle>> = decisions.map { all ->
        all.filterValues { it == TriageVerdict.SKIP }.keys.map { SkippedTitle(it, it.external, null, 0L) }
    }

    override fun observeDecision(mediaId: MediaId): Flow<TriageDecisionSummary?> = decisions.map { all ->
        all[mediaId]?.let { TriageDecisionSummary(mediaId, it, decidedAtEpochMs = 0L) }
    }

    override suspend fun restore(mediaId: MediaId) {
        restored += mediaId
        decisions.value = decisions.value - mediaId
    }

    val snoozes = MutableStateFlow<Map<MediaId, Long>>(emptyMap())
    val unsnoozed = mutableListOf<MediaId>()

    override fun observeSnoozedIds(): Flow<Set<MediaId>> = snoozes.map { it.keys }

    override fun observeSnoozed(): Flow<List<SnoozedTitle>> = snoozes.map { all ->
        all.map { (id, due) -> SnoozedTitle(id, id.external, null, snoozedAtEpochMs = 0L, dueAtEpochDay = due) }
    }

    override fun observeSnooze(mediaId: MediaId): Flow<SnoozedTitle?> = snoozes.map { all ->
        all[mediaId]?.let { SnoozedTitle(mediaId, mediaId.external, null, snoozedAtEpochMs = 0L, dueAtEpochDay = it) }
    }

    override suspend fun unsnooze(mediaId: MediaId) {
        unsnoozed += mediaId
        snoozes.value = snoozes.value - mediaId
    }
}

internal class FakeRepo(
    private val searchResult: (page: Int) -> Result<PagedResult<MediaSummary>> = { Result.success(PagedResult(emptyList(), it, it)) },
    private val movieGenresResult: Result<List<Genre>> = Result.success(emptyList()),
    private val tvGenresResult: Result<List<Genre>> = Result.success(emptyList()),
    private val discoverResult: (type: MediaType, page: Int, genreId: String?) -> Result<PagedResult<MediaSummary>> =
        { _, page, _ -> Result.success(PagedResult(emptyList(), page, page)) },
    private val recommendationsResult: (id: MediaId) -> Result<PagedResult<MediaSummary>> = { Result.success(PagedResult(emptyList(), 1, 1)) },
) : SearchRepository {
    override suspend fun search(query: String, page: Int) = searchResult(page)
    override suspend fun trending() = Result.success(emptyList<MediaSummary>())
    override suspend fun details(id: MediaId) = Result.success(MediaDetails(MediaSummary(id, "t")))
    override suspend fun discover(type: MediaType, page: Int, genreId: String?) = discoverResult(type, page, genreId)
    override suspend fun genres(type: MediaType) = if (type == MediaType.MOVIE) movieGenresResult else tvGenresResult
    override suspend fun watchProviders(id: MediaId) = Result.success(WatchProviders())
    override suspend fun recommendations(id: MediaId, page: Int) = recommendationsResult(id)
    override suspend fun similar(id: MediaId, page: Int) = Result.success(PagedResult(emptyList<MediaSummary>(), 1, 1))
    override suspend fun episodeDetails(episodeId: EpisodeId) = Result.success(EpisodeDetails(episodeId, "Episode", episodeId.seasonNumber, episodeId.episodeNumber))
}

internal class FakeSearchCollectionApi(initialLibrary: List<CollectionSummary> = emptyList()) : CollectionApi {
    val library = MutableStateFlow(initialLibrary)

    override fun observeMembership(mediaId: MediaId): Flow<CollectionMembership?> = error("not used")
    override fun observeSummaries(): Flow<List<CollectionSummary>> = library
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
