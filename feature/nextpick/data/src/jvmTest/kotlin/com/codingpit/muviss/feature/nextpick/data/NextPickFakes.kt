package com.codingpit.muviss.feature.nextpick.data

import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.core.network.DiscoverFilters
import com.codingpit.muviss.core.network.MetadataProvider
import com.codingpit.muviss.feature.collection.api.CollectionApi
import com.codingpit.muviss.feature.collection.api.CollectionMembership
import com.codingpit.muviss.feature.collection.api.CollectionSummary
import com.codingpit.muviss.feature.collection.api.NewEpisodesResult
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.MediaType
import com.codingpit.muviss.models.MetadataError
import com.codingpit.muviss.models.PagedResult
import com.codingpit.muviss.models.SourceId
import com.codingpit.muviss.models.WatchStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** Epoch milliseconds for 2027-01-15, so "this year" is 2027 and the library's day counts are stable. */
internal const val NOW_MS = 1_800_000_000_000L

internal val fixedClock = object : AppClock {
    override fun nowEpochMs(): Long = NOW_MS
}

internal fun movie(id: String) = MediaSummary(MediaId.tmdbMovie(id), "Movie $id")

internal fun show(id: String) = MediaSummary(MediaId.tmdbTv(id), "Show $id")

internal fun libraryTitle(summary: MediaSummary, status: WatchStatus) = CollectionSummary(
    mediaId = summary.id,
    title = summary.title,
    posterUrl = null,
    status = status,
)

/**
 * A TMDB double that counts the requests a pick makes, at the [MetadataProvider] seam. Each of these
 * methods is one HTTP request on [com.codingpit.muviss.core.network.tmdb.TmdbProvider] (checked against its
 * `get` calls), so a count here is the request count the person's device sends. Methods the pick flow never
 * reaches throw, so a regression that starts calling them fails loudly instead of being counted.
 */
internal class CountingMetadata(
    private val discover: Map<MediaType, List<MediaSummary>> = emptyMap(),
    private val recommended: List<MediaSummary> = emptyList(),
    private val similarTitles: List<MediaSummary> = emptyList(),
    private val failRecommendations: MetadataError? = null,
) : MetadataProvider {

    override val source: SourceId = SourceId.TMDB

    private val counts = mutableMapOf<String, Int>()

    /** Requests made so far, by endpoint. */
    val requests: Map<String, Int> get() = counts.toMap()

    val total: Int get() = counts.values.sum()

    private fun count(endpoint: String) {
        counts[endpoint] = (counts[endpoint] ?: 0) + 1
    }

    override suspend fun search(query: String, page: Int): PagedResult<MediaSummary> = error("search is not part of a pick")

    override suspend fun trending(): List<MediaSummary> = error("trending is not part of a pick")

    override suspend fun details(id: MediaId): MediaDetails = error("a pick reads the library, not TMDB details")

    override suspend fun discoverFiltered(type: MediaType, page: Int, filters: DiscoverFilters): PagedResult<MediaSummary> {
        count("discover")
        return PagedResult(discover[type].orEmpty(), page = page, totalPages = 1)
    }

    override suspend fun recommendations(id: MediaId, page: Int): PagedResult<MediaSummary> {
        count("recommendations")
        failRecommendations?.let { throw it }
        return PagedResult(recommended, page = page, totalPages = 1)
    }

    override suspend fun similar(id: MediaId, page: Int): PagedResult<MediaSummary> {
        count("similar")
        return PagedResult(similarTitles, page = page, totalPages = 1)
    }
}

/** The library as the pick sources read it: only [observeSummaries] is used by a pick, the rest is unreachable. */
internal class FakeLibrary(private val summaries: List<CollectionSummary> = emptyList()) : CollectionApi {
    override fun observeMembership(mediaId: MediaId): Flow<CollectionMembership?> = flowOf(null)

    override fun observeSummaries(): Flow<List<CollectionSummary>> = flowOf(summaries)

    override suspend fun savedDetails(mediaId: MediaId): MediaDetails? = summaries.firstOrNull { it.mediaId == mediaId }?.let { MediaDetails(MediaSummary(it.mediaId, it.title)) }

    override suspend fun add(details: MediaDetails) = error("a pick never adds")

    override suspend fun remove(mediaId: MediaId) = error("a pick never removes")

    override suspend fun setFavorite(mediaId: MediaId, favorite: Boolean) = error("a pick never edits the library")

    override suspend fun setNotificationsMuted(mediaId: MediaId, muted: Boolean) = error("a pick never edits the library")

    override suspend fun setRating(mediaId: MediaId, rating: Int?) = error("a pick never edits the library")

    override suspend fun setNote(mediaId: MediaId, note: String?) = error("a pick never edits the library")

    override suspend fun setRevisitWillingness(mediaId: MediaId, willing: Boolean?) = error("a pick never edits the library")

    override suspend fun setCoWatchPinned(mediaId: MediaId, pinned: Boolean) = error("a pick never edits the library")

    override suspend fun refreshAndFindNewEpisodes(): List<NewEpisodesResult> = error("a pick never refreshes")
}
