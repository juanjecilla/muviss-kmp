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
)

/** Stable episode identity within a show, e.g. `tmdb:tv:1399/1/1`. */
@Serializable
data class EpisodeId(
    val show: MediaId,
    val seasonNumber: Int,
    val episodeNumber: Int,
) {
    override fun toString(): String = "$show/$seasonNumber/$episodeNumber"
}
