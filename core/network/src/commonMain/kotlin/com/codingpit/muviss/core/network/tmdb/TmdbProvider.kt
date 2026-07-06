package com.codingpit.muviss.core.network.tmdb

import com.codingpit.muviss.core.network.MetadataProvider
import com.codingpit.muviss.core.network.MuvissBuildConfig
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.MediaType
import com.codingpit.muviss.models.SourceId
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
) : MetadataProvider {

    override val source: SourceId = SourceId.TMDB

    override suspend fun search(query: String, page: Int): List<MediaSummary> {
        val dto: TmdbPageDto = client.get("$BASE/search/multi") {
            parameter("api_key", apiKey)
            parameter("query", query)
            parameter("page", page)
            parameter("include_adult", false)
        }.body()
        return dto.results.mapNotNull(TmdbMapper::resultToSummary)
    }

    override suspend fun trending(): List<MediaSummary> {
        val dto: TmdbPageDto = client.get("$BASE/trending/all/week") {
            parameter("api_key", apiKey)
        }.body()
        return dto.results.mapNotNull(TmdbMapper::resultToSummary)
    }

    override suspend fun details(id: MediaId): MediaDetails = when (id.type) {
        MediaType.MOVIE -> movieDetails(id.external)
        MediaType.TV -> tvDetails(id.external)
    }

    private suspend fun movieDetails(externalId: String): MediaDetails {
        val dto: TmdbMovieDetailDto = client.get("$BASE/movie/$externalId") {
            parameter("api_key", apiKey)
            parameter("append_to_response", "external_ids")
        }.body()
        return TmdbMapper.movieToDetails(dto)
    }

    private suspend fun tvDetails(externalId: String): MediaDetails {
        val show: TmdbTvDetailDto = client.get("$BASE/tv/$externalId") {
            parameter("api_key", apiKey)
            parameter("append_to_response", "external_ids")
        }.body()
        val showId = MediaId.tmdbTv(externalId)
        val seasons = show.seasons
            .filter { it.seasonNumber > 0 }
            .map { summary ->
                val detail: TmdbSeasonDetailDto =
                    client.get("$BASE/tv/$externalId/season/${summary.seasonNumber}") {
                        parameter("api_key", apiKey)
                    }.body()
                TmdbMapper.seasonToModel(showId, detail)
            }
        return TmdbMapper.tvToDetails(show, seasons)
    }

    companion object {
        const val BASE = "https://api.themoviedb.org/3"
    }
}
