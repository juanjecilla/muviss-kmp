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
    @SerialName("genre_ids") val genreIds: List<Int> = emptyList(),
)

@Serializable
internal data class TmdbExternalIdsDto(
    @SerialName("imdb_id") val imdbId: String? = null,
    @SerialName("wikidata_id") val wikidataId: String? = null,
)

/** `credits` appended to a movie: one entry per cast role / crew job. */
@Serializable
internal data class TmdbCreditsDto(
    val cast: List<TmdbCastDto> = emptyList(),
    val crew: List<TmdbCrewDto> = emptyList(),
)

@Serializable
internal data class TmdbCastDto(
    val id: Long,
    val name: String = "",
    @SerialName("profile_path") val profilePath: String? = null,
    val character: String? = null,
    val order: Int = Int.MAX_VALUE,
)

@Serializable
internal data class TmdbCrewDto(
    val id: Long,
    val name: String = "",
    @SerialName("profile_path") val profilePath: String? = null,
    val job: String? = null,
    val department: String? = null,
)

/**
 * `aggregate_credits` appended to a show: a person once, with every role they
 * played across the whole run and how many episodes each covered. A plain
 * `credits` on a show covers only the latest season.
 */
@Serializable
internal data class TmdbAggregateCreditsDto(
    val cast: List<TmdbAggregateCastDto> = emptyList(),
    val crew: List<TmdbAggregateCrewDto> = emptyList(),
)

@Serializable
internal data class TmdbAggregateCastDto(
    val id: Long,
    val name: String = "",
    @SerialName("profile_path") val profilePath: String? = null,
    val roles: List<TmdbRoleDto> = emptyList(),
    @SerialName("total_episode_count") val totalEpisodeCount: Int? = null,
    val order: Int = Int.MAX_VALUE,
)

@Serializable
internal data class TmdbRoleDto(
    val character: String? = null,
    @SerialName("episode_count") val episodeCount: Int? = null,
)

@Serializable
internal data class TmdbAggregateCrewDto(
    val id: Long,
    val name: String = "",
    @SerialName("profile_path") val profilePath: String? = null,
    val department: String? = null,
    val jobs: List<TmdbJobDto> = emptyList(),
    @SerialName("total_episode_count") val totalEpisodeCount: Int? = null,
)

@Serializable
internal data class TmdbJobDto(
    val job: String? = null,
    @SerialName("episode_count") val episodeCount: Int? = null,
)

/** One entry of a show's `created_by`. */
@Serializable
internal data class TmdbCreatorDto(
    val id: Long,
    val name: String = "",
    @SerialName("profile_path") val profilePath: String? = null,
)

@Serializable
internal data class TmdbGenreDto(val name: String, val id: Int? = null)

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
    val credits: TmdbCreditsDto? = null,
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
    @SerialName("aggregate_credits") val aggregateCredits: TmdbAggregateCreditsDto? = null,
    @SerialName("created_by") val createdBy: List<TmdbCreatorDto> = emptyList(),
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

/**
 * `/tv/{id}/season/{n}/episode/{m}` — the same fields as [TmdbEpisodeDto] plus
 * everything only the episode detail screen reads. `guest_stars` and `crew`
 * come back on this endpoint directly, so no `append_to_response` is needed.
 */
@Serializable
internal data class TmdbEpisodeDetailDto(
    @SerialName("season_number") val seasonNumber: Int,
    @SerialName("episode_number") val episodeNumber: Int,
    val name: String = "",
    val overview: String? = null,
    @SerialName("air_date") val airDate: String? = null,
    @SerialName("still_path") val stillPath: String? = null,
    val runtime: Int? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
    @SerialName("guest_stars") val guestStars: List<TmdbEpisodeCreditDto> = emptyList(),
    val crew: List<TmdbEpisodeCreditDto> = emptyList(),
)

@Serializable
internal data class TmdbEpisodeCreditDto(
    val id: Long? = null,
    val name: String = "",
    val character: String? = null,
    val job: String? = null,
    @SerialName("profile_path") val profilePath: String? = null,
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

/** `/person/{id}` with `combined_credits` and `external_ids` appended. */
@Serializable
internal data class TmdbPersonDto(
    val id: Long,
    val name: String = "",
    val biography: String? = null,
    val birthday: String? = null,
    val deathday: String? = null,
    @SerialName("place_of_birth") val placeOfBirth: String? = null,
    @SerialName("profile_path") val profilePath: String? = null,
    @SerialName("known_for_department") val knownForDepartment: String? = null,
    @SerialName("external_ids") val externalIds: TmdbExternalIdsDto? = null,
    @SerialName("combined_credits") val combinedCredits: TmdbCombinedCreditsDto? = null,
)

@Serializable
internal data class TmdbCombinedCreditsDto(
    val cast: List<TmdbPersonCreditDto> = emptyList(),
    val crew: List<TmdbPersonCreditDto> = emptyList(),
)

/** One row of a person's `combined_credits`; `character` is set on cast rows, `job` on crew rows. */
@Serializable
internal data class TmdbPersonCreditDto(
    val id: Long,
    @SerialName("media_type") val mediaType: String? = null,
    val title: String? = null,
    val name: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    val overview: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
    @SerialName("vote_count") val voteCount: Int = 0,
    @SerialName("genre_ids") val genreIds: List<Int> = emptyList(),
    val character: String? = null,
    val job: String? = null,
    @SerialName("episode_count") val episodeCount: Int? = null,
)
