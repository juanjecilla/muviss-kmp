package com.codingpit.muviss.core.network.tmdb

import com.codingpit.muviss.core.network.DefaultMetadataLocale
import com.codingpit.muviss.core.network.MetadataLocale
import com.codingpit.muviss.core.network.MetadataProvider
import com.codingpit.muviss.core.network.MuvissBuildConfig
import com.codingpit.muviss.core.network.muvissJson
import com.codingpit.muviss.models.EpisodeDetails
import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.Genre
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.MediaType
import com.codingpit.muviss.models.MetadataError
import com.codingpit.muviss.models.PagedResult
import com.codingpit.muviss.models.Season
import com.codingpit.muviss.models.SourceId
import com.codingpit.muviss.models.WatchProviders
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.cache.InvalidCacheStateException
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * TMDB implementation of [MetadataProvider].
 *
 * **Errors.** Every failure leaves this class as a [MetadataError] with fixed
 * text and no cause (see [tmdbCall]); a caller can show `userMessage` and
 * never has to look at, or scrub, an exception message that may contain the
 * request URL.
 *
 * **Credentials.** The v4 read token (`TMDB_READ_TOKEN`) is sent as a bearer
 * header when configured; otherwise the v3 key (`TMDB_API_KEY`) goes in the
 * `api_key` query parameter. See [TmdbCredentials].
 *
 * **Concurrency.** At most [maxConcurrentRequests] requests are in flight at
 * once *for this provider*, whoever is asking — the foreground screens, the
 * library refresh, the background worker. Callers may fan out freely; the
 * limit is here so it holds for all of them at once, which a semaphore inside
 * each caller cannot promise.
 *
 * **Seasons.** A TV title is one request for the show and then one more per 20
 * seasons (`append_to_response=season/1,...`), not one per season.
 *
 * [client] is the app's shared client; this class derives its own policy layer
 * from it ([withTmdbPolicy]) and leaves the original untouched.
 */
class TmdbProvider internal constructor(
    client: HttpClient,
    private val credentials: TmdbCredentials,
    private val locale: MetadataLocale,
    retryPolicy: TmdbRetryPolicy,
    maxConcurrentRequests: Int,
    private val failureTrace: TmdbFailureTrace = TmdbFailureTrace.None,
) : MetadataProvider {

    constructor(
        client: HttpClient,
        locale: MetadataLocale = DefaultMetadataLocale(),
        failureTrace: TmdbFailureTrace = TmdbFailureTrace.None,
    ) : this(
        client = client,
        credentials = TmdbCredentials(MuvissBuildConfig.TMDB_READ_TOKEN, MuvissBuildConfig.TMDB_API_KEY),
        locale = locale,
        retryPolicy = TmdbRetryPolicy(),
        maxConcurrentRequests = DEFAULT_MAX_CONCURRENT_REQUESTS,
        failureTrace = failureTrace,
    )

    private val cachedClient: HttpClient = client.withTmdbPolicy(retryPolicy, cached = true)

    // Title details feed the library refresh and the new-episode check, whose
    // whole job is to notice that TMDB's answer changed. A cache that honours
    // TMDB's own `max-age` would hand back the pre-refresh answer, so details
    // (and their appended seasons) use a client with the same policies minus
    // the cache. Everything else is safe to serve from it.
    private val freshClient: HttpClient = client.withTmdbPolicy(retryPolicy, cached = false)
    private val inFlight = Semaphore(maxConcurrentRequests)

    override val source: SourceId = SourceId.TMDB

    override suspend fun search(query: String, page: Int): PagedResult<MediaSummary> {
        val dto: TmdbPageDto = get("search/multi") {
            parameter("language", locale.language)
            parameter("query", query)
            parameter("page", page)
            parameter("include_adult", false)
        }
        return TmdbMapper.searchPageToPagedResult(dto)
    }

    override suspend fun trending(): List<MediaSummary> {
        val dto: TmdbPageDto = get("trending/all/week") { parameter("language", locale.language) }
        return dto.results.mapNotNull(TmdbMapper::resultToSummary)
    }

    override suspend fun details(id: MediaId): MediaDetails = when (id.type) {
        MediaType.MOVIE -> movieDetails(id.external)
        MediaType.TV -> tvDetails(id.external)
    }

    override suspend fun discover(type: MediaType, page: Int, genreId: String?): PagedResult<MediaSummary> {
        val dto: TmdbPageDto = get("discover/${type.tmdbPath}") {
            parameter("language", locale.language)
            parameter("sort_by", "popularity.desc")
            parameter("page", page)
            genreId?.let { parameter("with_genres", it) }
        }
        return TmdbMapper.discoverPageToPagedResult(type, dto)
    }

    override suspend fun genres(type: MediaType): List<Genre> {
        val dto: TmdbGenreListDto = get("genre/${type.tmdbPath}/list") { parameter("language", locale.language) }
        return TmdbMapper.genreListToModels(dto)
    }

    override suspend fun watchProviders(id: MediaId, region: String): WatchProviders {
        val dto: TmdbWatchProvidersResponseDto = get("${id.type.tmdbPath}/${id.external}/watch/providers")
        return TmdbMapper.watchProvidersToModel(dto.results[region])
    }

    override suspend fun recommendations(id: MediaId, page: Int): PagedResult<MediaSummary> {
        val dto: TmdbPageDto = get("${id.type.tmdbPath}/${id.external}/recommendations") {
            parameter("language", locale.language)
            parameter("page", page)
        }
        return TmdbMapper.discoverPageToPagedResult(id.type, dto)
    }

    override suspend fun similar(id: MediaId, page: Int): PagedResult<MediaSummary> {
        val dto: TmdbPageDto = get("${id.type.tmdbPath}/${id.external}/similar") {
            parameter("language", locale.language)
            parameter("page", page)
        }
        return TmdbMapper.discoverPageToPagedResult(id.type, dto)
    }

    override suspend fun findByExternalId(externalId: String, type: MediaType?): MediaSummary? {
        val dto: TmdbFindResponseDto = get("find/$externalId") {
            parameter("language", locale.language)
            parameter("external_source", "imdb_id")
        }
        return TmdbMapper.findResponseToSummary(dto, type)
    }

    /**
     * One request per episode. `guest_stars` and `crew` come back on this
     * endpoint without an `append_to_response`, so the whole detail screen is
     * a single round trip.
     */
    override suspend fun episodeDetails(episodeId: EpisodeId): EpisodeDetails {
        val show = episodeId.show
        val dto: TmdbEpisodeDetailDto =
            get("tv/${show.external}/season/${episodeId.seasonNumber}/episode/${episodeId.episodeNumber}") {
                parameter("language", locale.language)
            }
        return TmdbMapper.episodeDetailToModel(episodeId, dto)
    }

    private suspend fun movieDetails(externalId: String): MediaDetails {
        val dto: TmdbMovieDetailDto = get("movie/$externalId", fresh = true) {
            parameter("language", locale.language)
            parameter("append_to_response", "external_ids")
        }
        return TmdbMapper.movieToDetails(dto)
    }

    private suspend fun tvDetails(externalId: String): MediaDetails {
        val show: TmdbTvDetailDto = get("tv/$externalId", fresh = true) {
            parameter("language", locale.language)
            parameter("append_to_response", "external_ids")
        }
        val showId = MediaId.tmdbTv(externalId)
        val seasons = show.seasons
            .map { it.seasonNumber }
            .filter { it > 0 }
            .chunked(MAX_APPENDED_SEASONS)
            .flatMap { chunk -> seasonsOf(externalId, showId, chunk) }
        return TmdbMapper.tvToDetails(show, seasons)
    }

    /**
     * Fetches [numbers] in one `/tv/{id}?append_to_response=season/N,...`
     * request. The appended seasons come back as top-level keys literally named
     * `season/N`, which cannot be modelled as DTO fields, so the body is read as
     * a [JsonObject] and each key decoded on its own.
     *
     * A season that is absent from the response, or does not decode, is
     * fetched through its own `/tv/{id}/season/{n}` endpoint instead — the
     * pre-EPIC-27 path, kept so a shape TMDB changes or a season it declines to
     * append degrades to more requests, not to a show with missing episodes.
     */
    private suspend fun seasonsOf(externalId: String, showId: MediaId, numbers: List<Int>): List<Season> {
        val appended: JsonObject = get("tv/$externalId", fresh = true) {
            parameter("language", locale.language)
            parameter("append_to_response", numbers.joinToString(",") { "season/$it" })
        }
        return numbers.map { number ->
            val detail = appended["season/$number"]?.decodeSeasonOrNull() ?: seasonDetail(externalId, number)
            TmdbMapper.seasonToModel(showId, detail)
        }
    }

    private suspend fun seasonDetail(externalId: String, number: Int): TmdbSeasonDetailDto = get("tv/$externalId/season/$number", fresh = true) { parameter("language", locale.language) }

    private fun JsonElement.decodeSeasonOrNull(): TmdbSeasonDetailDto? = runCatching { muvissJson.decodeFromJsonElement<TmdbSeasonDetailDto>(this) }.getOrNull()

    /**
     * The one place a TMDB request is made: takes a slot from [inFlight],
     * attaches the credential, and converts every failure to a [MetadataError].
     *
     * A cached request that fails with [InvalidCacheStateException] is made once
     * more on [freshClient]. Ktor's `HttpCache` throws it when it revalidates a
     * stale entry, the server answers 304, and the entry the 304 points at cannot
     * be found (the 304's `Vary` differs from the stored response's). The entry
     * stays in memory, so without this fallback the same call fails the same way
     * until the process dies — which shipped as a Search screen that said
     * "offline" on a phone that was online.
     */
    private suspend inline fun <reified T> get(
        path: String,
        fresh: Boolean = false,
        crossinline configure: HttpRequestBuilder.() -> Unit = {},
    ): T = inFlight.withPermit {
        tmdbCall(onFailure = { failure, mapped -> failureTrace.record(tmdbFailureLine(path, failure, mapped)) }) {
            request(path, fresh) { configure() }.body<T>()
        }
    }

    private suspend fun request(path: String, fresh: Boolean, configure: HttpRequestBuilder.() -> Unit): HttpResponse {
        suspend fun HttpClient.fetch() = get("$BASE/$path") {
            authorize(credentials)
            configure()
        }
        if (fresh) return freshClient.fetch()
        return try {
            cachedClient.fetch()
        } catch (_: InvalidCacheStateException) {
            freshClient.fetch()
        }
    }

    companion object {
        const val BASE = "https://api.themoviedb.org/3"

        /** How many requests one provider keeps in flight; TMDB throttles, and the point is to overlap latency, not flood. */
        const val DEFAULT_MAX_CONCURRENT_REQUESTS = 4

        /**
         * TMDB's cap on `append_to_response` entries per request. `external_ids`
         * is fetched on the show request, so a season chunk has all 20 to itself.
         */
        const val MAX_APPENDED_SEASONS = 20
    }
}

/** TMDB's URL segment for a [MediaType] ("movie"/"tv"), shared by discover, genre and watch-provider calls. */
private val MediaType.tmdbPath: String
    get() = when (this) {
        MediaType.MOVIE -> "movie"
        MediaType.TV -> "tv"
    }
