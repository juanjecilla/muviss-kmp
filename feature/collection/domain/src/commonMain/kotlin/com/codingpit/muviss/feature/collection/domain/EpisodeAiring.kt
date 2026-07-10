package com.codingpit.muviss.feature.collection.domain

import com.codingpit.muviss.models.Episode
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaType

/**
 * Pure business rules for turning a title's full season/episode structure
 * into the aggregate counts the collection persists (`CollectionEntry`'s
 * [CollectionEntry.totalEpisodes]/[CollectionEntry.airedEpisodes]) and the
 * "SxxExx" label EPIC 5's new-episode notifications quote. Movies count as a
 * single "episode": both aired and total are 1 once released, matching
 * [WatchProgress][com.codingpit.muviss.core.model.WatchProgress]'s movie
 * convention.
 *
 * Kept free of any persistence concern so [NewEpisodesCalculator] can use the
 * same rule the snapshot-refresh path does without depending on the data
 * layer.
 */
fun MediaDetails.totalEpisodeCount(): Int = when (type) {
    MediaType.MOVIE -> 1
    MediaType.TV -> seasons.sumOf { it.episodes.size }
}

/** Episodes with an air date on or before [todayEpochDay] (see [com.codingpit.muviss.core.common.todayEpochDay]). */
fun MediaDetails.airedEpisodeCount(todayEpochDay: Long): Int = when (type) {
    MediaType.MOVIE -> 1
    MediaType.TV -> seasons.sumOf { season -> season.episodes.count { it.hasAiredBy(todayEpochDay) } }
}

/**
 * The latest aired episode's "SxxExx" label (e.g. "S02E05"), or null for a
 * movie or a show with nothing aired yet. Ties (same air date) resolve to the
 * highest season/episode number, matching how TMDB orders a season's episode
 * list.
 */
fun MediaDetails.latestAiredEpisodeLabel(todayEpochDay: Long): String? {
    if (type != MediaType.TV) return null
    val latest = seasons
        .flatMap { it.episodes }
        .filter { it.hasAiredBy(todayEpochDay) }
        .maxWithOrNull(compareBy({ it.seasonNumber }, { it.episodeNumber }))
        ?: return null
    return "S${latest.seasonNumber.zeroPadded()}E${latest.episodeNumber.zeroPadded()}"
}

// A local val (rather than `episode.airDateEpochDay != null && episode.airDateEpochDay <= ...`) because
// Kotlin can't smart-cast a nullable property declared in another module (`:models`) after a null check.
private fun Episode.hasAiredBy(todayEpochDay: Long): Boolean {
    val airDate = airDateEpochDay
    return airDate != null && airDate <= todayEpochDay
}

private fun Int.zeroPadded(): String = toString().padStart(2, '0')
