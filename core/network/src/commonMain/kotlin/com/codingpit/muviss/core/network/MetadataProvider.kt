package com.codingpit.muviss.core.network

import com.codingpit.muviss.models.EpisodeDetails
import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.Genre
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.MediaType
import com.codingpit.muviss.models.PagedResult
import com.codingpit.muviss.models.SourceId
import com.codingpit.muviss.models.WatchProviders

/**
 * The extensibility seam for external media sources. TMDB is the only
 * implementation today ([com.codingpit.muviss.core.network.tmdb.TmdbProvider]);
 * TVmaze/Trakt/etc. plug in later by implementing this interface and registering
 * with the [MetadataProviderRegistry] — no change to the domain or UI.
 *
 * [discover], [genres], [watchProviders], [recommendations] and [similar]
 * default to an empty result so a provider that has no equivalent (e.g. one
 * with no watch-provider data) need not implement them; TMDB overrides all
 * five.
 */
interface MetadataProvider {
    val source: SourceId

    suspend fun search(query: String, page: Int = 1): PagedResult<MediaSummary>

    suspend fun trending(): List<MediaSummary>

    suspend fun details(id: MediaId): MediaDetails

    /** Popularity-sorted browse for [type], optionally narrowed to one of [genres]' ids. */
    suspend fun discover(type: MediaType, page: Int = 1, genreId: String? = null): PagedResult<MediaSummary> = PagedResult(emptyList(), page = page, totalPages = page)

    /** Localized genre list for [type], used to populate discover's filter chips. */
    suspend fun genres(type: MediaType): List<Genre> = emptyList()

    /** Streaming/rent/buy availability for [id] in [region] (ISO 3166-1 alpha-2). */
    suspend fun watchProviders(id: MediaId, region: String): WatchProviders = WatchProviders()

    /** Titles the source recommends alongside [id] (EPIC 16's "More like this"), paged. */
    suspend fun recommendations(id: MediaId, page: Int = 1): PagedResult<MediaSummary> = PagedResult(emptyList(), page = page, totalPages = page)

    /** Titles similar to [id] by genre/keyword overlap, paged — the Detail screen's fallback when [recommendations] is thin. */
    suspend fun similar(id: MediaId, page: Int = 1): PagedResult<MediaSummary> = PagedResult(emptyList(), page = page, totalPages = page)

    /**
     * Resolves an external id from another source (currently only IMDb ids,
     * e.g. `tt0944947`) to this source's own [MediaSummary] — the EPIC 18
     * import seam: a Trakt/TV Time export carries IMDb ids, not this source's
     * native ids, so a title has to be looked up before it can become a
     * [MediaId]. [type] narrows the search when the caller already knows
     * movie vs TV (most import rows do); left null, an implementation should
     * return whichever match it finds first. Defaults to null (unsupported)
     * so a provider with no equivalent lookup need not implement it — TMDB
     * overrides it via `/find/{external_id}`.
     */
    suspend fun findByExternalId(externalId: String, type: MediaType? = null): MediaSummary? = null

    /**
     * Everything about one episode — overview, guest cast, crew, public
     * average — for the episode detail screen.
     *
     * A dedicated call rather than a richer [details] response: the
     * season/episode structure is fetched for every show in the library on
     * every refresh, and this data is read one episode at a time. Defaults to
     * null (unsupported) so a provider without an episode endpoint need not
     * implement it.
     */
    suspend fun episodeDetails(episodeId: EpisodeId): EpisodeDetails? = null
}

/**
 * Resolves a [MetadataProvider] for a given [SourceId] so callers can work with
 * any source uniformly. A [MediaId] carries its own source, so lookups route to
 * the right provider automatically.
 */
class MetadataProviderRegistry(providers: Iterable<MetadataProvider>) {
    private val bySource: Map<SourceId, MetadataProvider> = providers.associateBy { it.source }

    fun require(source: SourceId): MetadataProvider = bySource[source] ?: error("No MetadataProvider registered for source '$source'")

    fun forId(id: MediaId): MetadataProvider = require(id.source)

    val all: Collection<MetadataProvider> get() = bySource.values
}
