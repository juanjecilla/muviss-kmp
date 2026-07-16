package com.codingpit.muviss.core.network.tmdb

import com.codingpit.muviss.core.network.DefaultMetadataLocale
import com.codingpit.muviss.core.network.MetadataLocale
import com.codingpit.muviss.core.network.MetadataProvider
import com.codingpit.muviss.core.network.MuvissBuildConfig
import com.codingpit.muviss.models.Genre
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.MediaType
import com.codingpit.muviss.models.PagedResult
import com.codingpit.muviss.models.SourceId
import com.codingpit.muviss.models.WatchProviders
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter

/**
 * TMDB implementation of [MetadataProvider]. Reads the API key from the
 * generated [MuvissBuildConfig]. TV details are assembled by fetching the show
 * then each real season (season 0 "Specials" is skipped).
 */
class TmdbProvider(
    private val client: HttpClient,
    private val apiKey: String = MuvissBuildConfig.TMDB_API_KEY,
    private val locale: MetadataLocale = DefaultMetadataLocale(),
) : MetadataProvider {

    override val source: SourceId = SourceId.TMDB

    override suspend fun search(query: String, page: Int): PagedResult<MediaSummary> {
        val dto: TmdbPageDto = client.get("$BASE/search/multi") {
            parameter("api_key", apiKey)
            parameter("language", locale.language)
            parameter("query", query)
            parameter("page", page)
            parameter("include_adult", false)
        }.body()
        return TmdbMapper.searchPageToPagedResult(dto)
    }

    override suspend fun trending(): List<MediaSummary> {
        val dto: TmdbPageDto = client.get("$BASE/trending/all/week") {
            parameter("api_key", apiKey)
            parameter("language", locale.language)
        }.body()
        return dto.results.mapNotNull(TmdbMapper::resultToSummary)
    }

    override suspend fun details(id: MediaId): MediaDetails = when (id.type) {
        MediaType.MOVIE -> movieDetails(id.external)
        MediaType.TV -> tvDetails(id.external)
    }

    override suspend fun discover(type: MediaType, page: Int, genreId: String?): PagedResult<MediaSummary> {
        val dto: TmdbPageDto = client.get("$BASE/discover/${type.tmdbPath}") {
            parameter("api_key", apiKey)
            parameter("language", locale.language)
            parameter("sort_by", "popularity.desc")
            parameter("page", page)
            genreId?.let { parameter("with_genres", it) }
        }.body()
        return TmdbMapper.discoverPageToPagedResult(type, dto)
    }

    override suspend fun genres(type: MediaType): List<Genre> {
        val dto: TmdbGenreListDto = client.get("$BASE/genre/${type.tmdbPath}/list") {
            parameter("api_key", apiKey)
            parameter("language", locale.language)
        }.body()
        return TmdbMapper.genreListToModels(dto)
    }

    override suspend fun watchProviders(id: MediaId, region: String): WatchProviders {
        val dto: TmdbWatchProvidersResponseDto = client.get("$BASE/${id.type.tmdbPath}/${id.external}/watch/providers") {
            parameter("api_key", apiKey)
        }.body()
        return TmdbMapper.watchProvidersToModel(dto.results[region])
    }

    override suspend fun recommendations(id: MediaId, page: Int): PagedResult<MediaSummary> {
        val dto: TmdbPageDto = client.get("$BASE/${id.type.tmdbPath}/${id.external}/recommendations") {
            parameter("api_key", apiKey)
            parameter("language", locale.language)
            parameter("page", page)
        }.body()
        return TmdbMapper.discoverPageToPagedResult(id.type, dto)
    }

    override suspend fun similar(id: MediaId, page: Int): PagedResult<MediaSummary> {
        val dto: TmdbPageDto = client.get("$BASE/${id.type.tmdbPath}/${id.external}/similar") {
            parameter("api_key", apiKey)
            parameter("language", locale.language)
            parameter("page", page)
        }.body()
        return TmdbMapper.discoverPageToPagedResult(id.type, dto)
    }

    override suspend fun findByExternalId(externalId: String, type: MediaType?): MediaSummary? {
        val dto: TmdbFindResponseDto = client.get("$BASE/find/$externalId") {
            parameter("api_key", apiKey)
            parameter("language", locale.language)
            parameter("external_source", "imdb_id")
        }.body()
        return TmdbMapper.findResponseToSummary(dto, type)
    }

    private suspend fun movieDetails(externalId: String): MediaDetails {
        val dto: TmdbMovieDetailDto = client.get("$BASE/movie/$externalId") {
            parameter("api_key", apiKey)
            parameter("language", locale.language)
            parameter("append_to_response", "external_ids")
        }.body()
        return TmdbMapper.movieToDetails(dto)
    }

    private suspend fun tvDetails(externalId: String): MediaDetails {
        val show: TmdbTvDetailDto = client.get("$BASE/tv/$externalId") {
            parameter("api_key", apiKey)
            parameter("language", locale.language)
            parameter("append_to_response", "external_ids")
        }.body()
        val showId = MediaId.tmdbTv(externalId)
        val seasons = show.seasons
            .filter { it.seasonNumber > 0 }
            .map { summary ->
                val detail: TmdbSeasonDetailDto =
                    client.get("$BASE/tv/$externalId/season/${summary.seasonNumber}") {
                        parameter("api_key", apiKey)
                        parameter("language", locale.language)
                    }.body()
                TmdbMapper.seasonToModel(showId, detail)
            }
        return TmdbMapper.tvToDetails(show, seasons)
    }

    companion object {
        const val BASE = "https://api.themoviedb.org/3"
    }
}

/** TMDB's URL segment for a [MediaType] ("movie"/"tv"), shared by discover, genre and watch-provider calls. */
private val MediaType.tmdbPath: String
    get() = when (this) {
        MediaType.MOVIE -> "movie"
        MediaType.TV -> "tv"
    }
