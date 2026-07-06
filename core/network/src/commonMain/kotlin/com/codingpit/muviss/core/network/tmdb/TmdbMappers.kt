package com.codingpit.muviss.core.network.tmdb

import com.codingpit.muviss.models.Episode
import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.MediaAnchors
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.MediaType
import com.codingpit.muviss.models.ProductionStatus
import com.codingpit.muviss.models.Season

internal object TmdbMapper {
    const val IMAGE_BASE = "https://image.tmdb.org/t/p/w500"

    fun imageUrl(path: String?): String? = path?.let { IMAGE_BASE + it }

    fun yearOf(date: String?): Int? = date?.take(4)?.toIntOrNull()

    fun tvProductionStatus(status: String?): ProductionStatus = when (status?.lowercase()) {
        "returning series", "in production", "planned", "pilot" -> ProductionStatus.RETURNING
        "ended" -> ProductionStatus.ENDED
        "canceled", "cancelled" -> ProductionStatus.CANCELED
        else -> ProductionStatus.UNKNOWN
    }

    /** Maps a search/trending row; returns null for non movie/tv rows (e.g. people). */
    fun resultToSummary(dto: TmdbResultDto): MediaSummary? = when (dto.mediaType) {
        "movie" -> dto.toSummary(MediaId.tmdbMovie(dto.id.toString()), dto.title, dto.releaseDate)
        "tv" -> dto.toSummary(MediaId.tmdbTv(dto.id.toString()), dto.name, dto.firstAirDate)
        else -> null
    }

    private fun TmdbResultDto.toSummary(id: MediaId, title: String?, date: String?): MediaSummary = MediaSummary(
        id = id,
        title = title.orEmpty(),
        year = yearOf(date),
        posterUrl = imageUrl(posterPath),
        overview = overview,
        rating = voteAverage,
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
        )
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
                airDateEpochDay = null,
                stillUrl = imageUrl(ep.stillPath),
            )
        },
    )
}
