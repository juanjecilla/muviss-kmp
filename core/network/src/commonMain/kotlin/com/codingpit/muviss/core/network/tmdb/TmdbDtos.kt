package com.codingpit.muviss.core.network.tmdb

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class TmdbPageDto(
    val page: Int = 1,
    val results: List<TmdbResultDto> = emptyList(),
    @SerialName("total_pages") val totalPages: Int = 1,
)

@Serializable
internal data class TmdbResultDto(
    val id: Long,
    @SerialName("media_type") val mediaType: String? = null,
    val title: String? = null, // movies
    val name: String? = null, // tv
    @SerialName("poster_path") val posterPath: String? = null,
    val overview: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
)

@Serializable
internal data class TmdbExternalIdsDto(
    @SerialName("imdb_id") val imdbId: String? = null,
)

@Serializable
internal data class TmdbGenreDto(val name: String)

@Serializable
internal data class TmdbMovieDetailDto(
    val id: Long,
    val title: String,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    val overview: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    val runtime: Int? = null,
    val genres: List<TmdbGenreDto> = emptyList(),
    @SerialName("vote_average") val voteAverage: Double? = null,
    val status: String? = null,
    @SerialName("external_ids") val externalIds: TmdbExternalIdsDto? = null,
)

@Serializable
internal data class TmdbTvDetailDto(
    val id: Long,
    val name: String,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    val overview: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    val genres: List<TmdbGenreDto> = emptyList(),
    @SerialName("vote_average") val voteAverage: Double? = null,
    val status: String? = null,
    @SerialName("number_of_seasons") val numberOfSeasons: Int = 0,
    val seasons: List<TmdbSeasonSummaryDto> = emptyList(),
    @SerialName("external_ids") val externalIds: TmdbExternalIdsDto? = null,
)

@Serializable
internal data class TmdbSeasonSummaryDto(
    @SerialName("season_number") val seasonNumber: Int,
    val name: String = "",
    @SerialName("episode_count") val episodeCount: Int = 0,
)

@Serializable
internal data class TmdbSeasonDetailDto(
    @SerialName("season_number") val seasonNumber: Int,
    val name: String = "",
    val episodes: List<TmdbEpisodeDto> = emptyList(),
)

@Serializable
internal data class TmdbEpisodeDto(
    @SerialName("season_number") val seasonNumber: Int,
    @SerialName("episode_number") val episodeNumber: Int,
    val name: String = "",
    @SerialName("air_date") val airDate: String? = null,
    @SerialName("still_path") val stillPath: String? = null,
    // TMDB's `/tv/{id}/season/{n}` response carries this per episode (unlike
    // the deprecated show-level `episode_run_time` average) — free real data
    // since this endpoint is already fetched for the season/episode structure.
    val runtime: Int? = null,
)

/** `/genre/{movie|tv}/list` — unlike [TmdbGenreDto], carries the id needed to filter `/discover`. */
@Serializable
internal data class TmdbGenreListItemDto(
    val id: Long,
    val name: String,
)

@Serializable
internal data class TmdbGenreListDto(
    val genres: List<TmdbGenreListItemDto> = emptyList(),
)

@Serializable
internal data class TmdbWatchProviderDto(
    @SerialName("provider_id") val providerId: Long,
    @SerialName("provider_name") val providerName: String,
    @SerialName("logo_path") val logoPath: String? = null,
)

/** One region's entry in `/{movie|tv}/{id}/watch/providers`'s `results` map. */
@Serializable
internal data class TmdbWatchProviderRegionDto(
    val flatrate: List<TmdbWatchProviderDto> = emptyList(),
    val rent: List<TmdbWatchProviderDto> = emptyList(),
    val buy: List<TmdbWatchProviderDto> = emptyList(),
)

@Serializable
internal data class TmdbWatchProvidersResponseDto(
    val id: Long,
    val results: Map<String, TmdbWatchProviderRegionDto> = emptyMap(),
)

/**
 * `/find/{external_id}` — resolves an id from another source (e.g. an IMDb
 * id) to TMDB's own movie/TV results. Rows reuse [TmdbResultDto]'s shape
 * (id/title/name/poster_path/...) but this endpoint never sets `media_type`,
 * hence the two separate typed lists rather than one `results` array.
 */
@Serializable
internal data class TmdbFindResponseDto(
    @SerialName("movie_results") val movieResults: List<TmdbResultDto> = emptyList(),
    @SerialName("tv_results") val tvResults: List<TmdbResultDto> = emptyList(),
)
