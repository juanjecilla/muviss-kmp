package com.codingpit.muviss.feature.triage.domain

import com.codingpit.muviss.models.Episode
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaType

/**
 * Pure helpers over a show's season/episode structure, used to turn a verdict
 * into the right ticks. Deliberately local to triage rather than reaching
 * into collection's `EpisodeAiring` or progress's `EpisodeOrdering`, both of
 * which are internal to their slices (ADR 0004).
 *
 * Air order is season number then episode number, matching how TMDB lists a
 * season — and matching `EpisodeOrdering.flatten`, so the ticks triage writes
 * line up with the ones the Detail screen would have written by hand.
 */
internal fun MediaDetails.airedEpisodesInOrder(todayEpochDay: Long): List<Episode> {
    if (type != MediaType.TV) return emptyList()
    return seasons
        .sortedBy { it.number }
        .flatMap { season -> season.episodes.sortedBy { it.episodeNumber } }
        .filter { it.hasAiredBy(todayEpochDay) }
}

/**
 * The earliest aired episode — what "I'm watching this" ticks, so the show
 * derives Watching and lands on the Progress screen with a real next episode
 * waiting. Null when nothing has aired yet, in which case the verdict saves
 * the title without writing any progress.
 */
internal fun MediaDetails.firstAiredEpisode(todayEpochDay: Long): Episode? = airedEpisodesInOrder(todayEpochDay).firstOrNull()

/** The most recent aired episode — the boundary "caught up" means. */
internal fun MediaDetails.lastAiredEpisode(todayEpochDay: Long): Episode? = airedEpisodesInOrder(todayEpochDay).lastOrNull()

// A local val rather than an inline null-check because Kotlin can't smart-cast
// a nullable property declared in another module (`:models`).
private fun Episode.hasAiredBy(todayEpochDay: Long): Boolean {
    val airDate = airDateEpochDay
    return airDate != null && airDate <= todayEpochDay
}
