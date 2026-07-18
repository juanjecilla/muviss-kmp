package com.codingpit.muviss.core.network.tmdb

import com.codingpit.muviss.models.Episode
import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.Genre
import com.codingpit.muviss.models.MediaAnchors
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.MediaType
import com.codingpit.muviss.models.PagedResult
import com.codingpit.muviss.models.ProductionStatus
import com.codingpit.muviss.models.Season
import com.codingpit.muviss.models.WatchProvider
import com.codingpit.muviss.models.WatchProviders

internal object TmdbMapper {
    const val IMAGE_BASE = "https://image.tmdb.org/t/p/w500"

    // Provider logos render small (a couple dozen dp); w92 is TMDB's smallest
    // non-thumbnail size and keeps the "Where to watch" row light.
    const val LOGO_BASE = "https://image.tmdb.org/t/p/w92"

    // Backdrops render edge-to-edge behind the detail hero; w780 is the
    // smallest size that stays sharp at expanded widths.
    const val BACKDROP_BASE = "https://image.tmdb.org/t/p/w780"

    fun imageUrl(path: String?): String? = path?.let { IMAGE_BASE + it }

    fun backdropUrl(path: String?): String? = path?.let { BACKDROP_BASE + it }

    fun logoUrl(path: String?): String? = path?.let { LOGO_BASE + it }

    fun yearOf(date: String?): Int? = date?.take(4)?.toIntOrNull()

    /**
     * Parses a TMDB `air_date` (`yyyy-MM-dd`) into an epoch day (days since
     * 1970-01-01, matching [com.codingpit.muviss.models.Episode.airDateEpochDay]'s
     * convention), or null if [airDate] is null or malformed. No date library
     * is used — this is common code and must run on every target — so the day
     * count is computed directly via the standard proleptic-Gregorian
     * days-from-civil algorithm (Howard Hinnant's `days_from_civil`).
     */
    fun airDateToEpochDay(airDate: String?): Long? {
        if (airDate == null) return null
        val parts = airDate.split("-")
        if (parts.size != 3) return null
        val year = parts[0].toIntOrNull() ?: return null
        val month = parts[1].toIntOrNull() ?: return null
        val day = parts[2].toIntOrNull() ?: return null
        return epochDayFromDate(year, month, day)
    }

    private fun epochDayFromDate(year: Int, month: Int, day: Int): Long {
        val y = if (month <= 2) year - 1 else year
        val era = (if (y >= 0) y else y - ERA_YEAR_ADJUST) / DAYS_PER_ERA_YEARS
        val yearOfEra = y - era * DAYS_PER_ERA_YEARS // [0, 399]
        val monthIndex = if (month > 2) month - 3 else month + 9 // [0, 11], March-based
        val dayOfYear = (MONTH_TO_DAYS_NUMERATOR * monthIndex + 2) / 5 + day - 1 // [0, 365]
        val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear // [0, 146096]
        return era.toLong() * DAYS_PER_ERA + dayOfEra.toLong() - DAYS_FROM_ERA_ZERO_TO_UNIX_EPOCH
    }

    private const val ERA_YEAR_ADJUST = 399
    private const val DAYS_PER_ERA_YEARS = 400
    private const val MONTH_TO_DAYS_NUMERATOR = 153
    private const val DAYS_PER_ERA = 146_097L
    private const val DAYS_FROM_ERA_ZERO_TO_UNIX_EPOCH = 719_468L

    fun tvProductionStatus(status: String?): ProductionStatus = when (status?.lowercase()) {
        "returning series", "in production", "planned", "pilot" -> ProductionStatus.RETURNING
        "ended" -> ProductionStatus.ENDED
        "canceled", "cancelled" -> ProductionStatus.CANCELED
        else -> ProductionStatus.UNKNOWN
    }

    /** Maps a search/trending row; returns null for non movie/tv rows (e.g. people). */
    fun resultToSummary(dto: TmdbResultDto): MediaSummary? = when (dto.mediaType) {
        "movie" -> discoverResultToSummary(MediaType.MOVIE, dto)
        "tv" -> discoverResultToSummary(MediaType.TV, dto)
        else -> null
    }

    /**
     * Maps a `/discover/{movie|tv}` row. Unlike search/trending rows, discover
     * rows carry no `media_type` — the endpoint called already fixes [type].
     */
    fun discoverResultToSummary(type: MediaType, dto: TmdbResultDto): MediaSummary = when (type) {
        MediaType.MOVIE -> dto.toSummary(MediaId.tmdbMovie(dto.id.toString()), dto.title, dto.releaseDate)
        MediaType.TV -> dto.toSummary(MediaId.tmdbTv(dto.id.toString()), dto.name, dto.firstAirDate)
    }

    private fun TmdbResultDto.toSummary(id: MediaId, title: String?, date: String?): MediaSummary = MediaSummary(
        id = id,
        title = title.orEmpty(),
        year = yearOf(date),
        posterUrl = imageUrl(posterPath),
        overview = overview,
        rating = voteAverage,
    )

    /** Maps a `/search/multi` page, dropping non movie/tv rows (e.g. people). */
    fun searchPageToPagedResult(dto: TmdbPageDto): PagedResult<MediaSummary> = PagedResult(
        items = dto.results.mapNotNull(::resultToSummary),
        page = dto.page,
        totalPages = dto.totalPages,
    )

    /** Maps a `/discover/{movie|tv}` page, already fixed to a single [type]. */
    fun discoverPageToPagedResult(type: MediaType, dto: TmdbPageDto): PagedResult<MediaSummary> = PagedResult(
        items = dto.results.map { discoverResultToSummary(type, it) },
        page = dto.page,
        totalPages = dto.totalPages,
    )

    fun genreListToModels(dto: TmdbGenreListDto): List<Genre> = dto.genres.map { Genre(it.id.toString(), it.name) }

    /** Maps one region's entry from `/{movie|tv}/{id}/watch/providers`; null (region not offered) maps to empty. */
    fun watchProvidersToModel(dto: TmdbWatchProviderRegionDto?): WatchProviders = if (dto == null) {
        WatchProviders()
    } else {
        WatchProviders(
            flatrate = dto.flatrate.map { it.toModel() },
            rent = dto.rent.map { it.toModel() },
            buy = dto.buy.map { it.toModel() },
        )
    }

    private fun TmdbWatchProviderDto.toModel() = WatchProvider(
        id = providerId.toString(),
        name = providerName,
        logoUrl = logoUrl(logoPath),
    )

    fun movieToDetails(dto: TmdbMovieDetailDto): MediaDetails {
        val id = MediaId.tmdbMovie(dto.id.toString())
        return MediaDetails(
            summary = MediaSummary(
                id = id,
                title = dto.title,
                year = yearOf(dto.releaseDate),
                posterUrl = imageUrl(dto.posterPath),
                overview = dto.overview,
                rating = dto.voteAverage,
            ),
            anchors = MediaAnchors(imdbId = dto.externalIds?.imdbId),
            genres = dto.genres.map { it.name },
            runtimeMinutes = dto.runtime,
            productionStatus = ProductionStatus.RELEASED,
            seasons = emptyList(),
            backdropUrl = backdropUrl(dto.backdropPath),
        )
    }

    fun tvToDetails(dto: TmdbTvDetailDto, seasons: List<Season>): MediaDetails {
        val id = MediaId.tmdbTv(dto.id.toString())
        return MediaDetails(
            summary = MediaSummary(
                id = id,
                title = dto.name,
                year = yearOf(dto.firstAirDate),
                posterUrl = imageUrl(dto.posterPath),
                overview = dto.overview,
                rating = dto.voteAverage,
            ),
            anchors = MediaAnchors(imdbId = dto.externalIds?.imdbId),
            genres = dto.genres.map { it.name },
            runtimeMinutes = null,
            productionStatus = tvProductionStatus(dto.status),
            seasons = seasons,
            backdropUrl = backdropUrl(dto.backdropPath),
        )
    }

    /**
     * Picks the right row out of a `/find/{external_id}` response for
     * [type] (the movie or TV list, whichever the caller asked for), or —
     * when [type] is unknown — whichever list has a match, movies first.
     * Used by [TmdbProvider.findByExternalId] (EPIC 18 import id-mapping).
     */
    fun findResponseToSummary(dto: TmdbFindResponseDto, type: MediaType?): MediaSummary? = when (type) {
        MediaType.MOVIE -> dto.movieResults.firstOrNull()?.let { discoverResultToSummary(MediaType.MOVIE, it) }

        MediaType.TV -> dto.tvResults.firstOrNull()?.let { discoverResultToSummary(MediaType.TV, it) }

        null -> dto.movieResults.firstOrNull()?.let { discoverResultToSummary(MediaType.MOVIE, it) }
            ?: dto.tvResults.firstOrNull()?.let { discoverResultToSummary(MediaType.TV, it) }
    }

    fun seasonToModel(show: MediaId, dto: TmdbSeasonDetailDto): Season = Season(
        number = dto.seasonNumber,
        name = dto.name,
        episodes = dto.episodes.map { ep ->
            Episode(
                id = EpisodeId(show, ep.seasonNumber, ep.episodeNumber),
                seasonNumber = ep.seasonNumber,
                episodeNumber = ep.episodeNumber,
                name = ep.name,
                airDateEpochDay = airDateToEpochDay(ep.airDate),
                stillUrl = imageUrl(ep.stillPath),
                runtimeMinutes = ep.runtime,
            )
        },
    )
}
