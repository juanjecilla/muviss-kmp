package com.codingpit.muviss.models

import kotlinx.serialization.Serializable

/** Lightweight media representation for search results and lists. */
@Serializable
data class MediaSummary(
    val id: MediaId,
    val title: String,
    val year: Int? = null,
    val posterUrl: String? = null,
    val overview: String? = null,
    val rating: Double? = null,
) {
    val type: MediaType get() = id.type
}

/**
 * Production lifecycle of a title. For TV this distinguishes an ongoing show
 * (more episodes may come) from one that has ended — the difference between
 * [WatchStatus.WATCHED] and [WatchStatus.FINISHED].
 */
@Serializable
enum class ProductionStatus {
    RELEASED, // movies
    RETURNING, // TV, ongoing
    ENDED, // TV, complete
    CANCELED, // TV, complete
    UNKNOWN,
    ;

    val isFinishedProduction: Boolean get() = this == ENDED || this == CANCELED || this == RELEASED
}

/** Full detail for a title, including seasons/episodes for TV. */
@Serializable
data class MediaDetails(
    val summary: MediaSummary,
    val anchors: MediaAnchors = MediaAnchors(),
    val genres: List<String> = emptyList(),
    val runtimeMinutes: Int? = null,
    val productionStatus: ProductionStatus = ProductionStatus.UNKNOWN,
    val seasons: List<Season> = emptyList(),
    /** Wide (16:9) backdrop art for the detail hero; null when TMDB has none. */
    val backdropUrl: String? = null,
) {
    val id: MediaId get() = summary.id
    val type: MediaType get() = summary.type
}

@Serializable
data class Season(
    val number: Int,
    val name: String,
    val episodes: List<Episode>,
)

@Serializable
data class Episode(
    val id: EpisodeId,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val name: String,
    val airDateEpochDay: Long? = null,
    val stillUrl: String? = null,
    /** Minutes, when TMDB reports it for this episode; null otherwise (see profile's hours-watched fallback). */
    val runtimeMinutes: Int? = null,
)

/** Stable episode identity within a show, e.g. `tmdb:tv:1399/1/1`. */
@Serializable
data class EpisodeId(
    val show: MediaId,
    val seasonNumber: Int,
    val episodeNumber: Int,
) {
    override fun toString(): String = "$show/$seasonNumber/$episodeNumber"

    companion object {
        /**
         * Synthetic id for a movie's single watched tick. Movies have no
         * season/episode structure, but progress is still stored per-"episode"
         * (see `EpisodeProgress.sq`), so this convention gives every movie one
         * stable id to tick. Safe from collision with any real TV episode id
         * because [show] already carries the media's [MediaType].
         */
        fun forMovie(show: MediaId) = EpisodeId(show, seasonNumber = 0, episodeNumber = 0)
    }
}
