package com.codingpit.muviss.feature.nextpick.data

import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.core.network.DiscoverFilters
import com.codingpit.muviss.core.network.MetadataProvider
import com.codingpit.muviss.core.network.WatchProviderFill
import com.codingpit.muviss.core.network.WatchProviderLookup
import com.codingpit.muviss.feature.collection.api.CollectionApi
import com.codingpit.muviss.feature.collection.api.CollectionMembership
import com.codingpit.muviss.feature.collection.api.CollectionSummary
import com.codingpit.muviss.feature.collection.api.NewEpisodesResult
import com.codingpit.muviss.feature.settings.api.TasteItem
import com.codingpit.muviss.feature.settings.api.TasteProfileApi
import com.codingpit.muviss.models.Genre
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.MediaType
import com.codingpit.muviss.models.MetadataError
import com.codingpit.muviss.models.PagedResult
import com.codingpit.muviss.models.SourceId
import com.codingpit.muviss.models.WatchProvider
import com.codingpit.muviss.models.WatchProviders
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
    /** The flatrate provider ids TMDB reports for a title; what [watchProviders] answers with. */
    private val flatrateOf: (MediaId) -> Set<String> = { emptySet() },
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

    override suspend fun genres(type: MediaType): List<Genre> {
        count("genres")
        return emptyList()
    }

    override suspend fun watchProviders(id: MediaId, region: String): WatchProviders {
        count("watch")
        return WatchProviders(flatrate = flatrateOf(id).map { WatchProvider(id = it, name = "provider $it") })
    }
}

/** The cache the fill writes and the lookup reads, keyed by title and holding one region's answer, as the real cache does. */
internal class MutableWatchCache : WatchProviderLookup {
    val entries = mutableMapOf<MediaId, Pair<String, Set<String>>>()

    override suspend fun cachedFlatrateIds(mediaIds: Set<MediaId>, region: String): Map<MediaId, Set<String>> = entries
        .filterKeys { it in mediaIds }
        .filterValues { (cachedRegion, _) -> cachedRegion == region }
        .mapValues { (_, cached) -> cached.second }
}

/**
 * Fills like the production fill: one TMDB request per title, through [metadata] so it is counted, with the
 * answer written to [cache]. [filled] records every title asked about, in order.
 */
internal class MetadataWatchFill(private val metadata: CountingMetadata, private val cache: MutableWatchCache) : WatchProviderFill {
    val filled = mutableListOf<MediaId>()

    override suspend fun fill(mediaIds: List<MediaId>, region: String) {
        for (id in mediaIds) {
            filled += id
            val flatrate = metadata.watchProviders(id, region).flatrate.map { it.id }.toSet()
            cache.entries[id] = region to flatrate
        }
    }
}

/** The Taste Profile as [TasteProfileApi] shows it: [items] are what `observe` emits, already without muted genres. */
internal class FakeTasteProfile(private val items: List<TasteItem> = emptyList()) : TasteProfileApi {
    override fun observe(): Flow<List<TasteItem>> = flowOf(items)

    override suspend fun setLiked(item: TasteItem, liked: Boolean): Boolean = error("unused")
}
