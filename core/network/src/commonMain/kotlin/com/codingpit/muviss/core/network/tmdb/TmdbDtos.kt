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
)
