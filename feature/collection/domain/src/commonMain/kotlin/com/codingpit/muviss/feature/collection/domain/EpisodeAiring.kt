package com.codingpit.muviss.feature.collection.domain

import com.codingpit.muviss.models.Episode
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaType
import com.codingpit.muviss.models.Season

/**
 * Pure business rules for turning a title's full season/episode structure
 * into the aggregate counts the collection persists (`CollectionEntry`'s
 * [CollectionEntry.totalEpisodes]/[CollectionEntry.airedEpisodes]) and the
 * "SxxExx" label EPIC 5's new-episode notifications quote. Movies count as a
 * single "episode": both aired and total are 1 once released, matching
 * [WatchProgress][com.codingpit.muviss.core.model.WatchProgress]'s movie
 * convention.
 *
 * Specials (season 0) are never in these counts (ADR 0037, #938). The snapshot is shared
 * and synced, and whether specials count is a per-device choice applied at read time, so
 * a refresh must not write them in. Their absence also keeps new-episode alerts from
 * firing for a special (`NewEpisodesCalculator` compares these counts), with no baseline
 * migration needed.
 *
 * Kept free of any persistence concern so [NewEpisodesCalculator] can use the
 * same rule the snapshot-refresh path does without depending on the data
 * layer.
 */
fun MediaDetails.totalEpisodeCount(): Int = when (type) {
    MediaType.MOVIE -> 1
    MediaType.TV -> regularSeasons().sumOf { it.episodes.size }
}

/** Episodes with an air date on or before [todayEpochDay] (see [com.codingpit.muviss.core.common.todayEpochDay]), specials excluded. */
fun MediaDetails.airedEpisodeCount(todayEpochDay: Long): Int = when (type) {
    MediaType.MOVIE -> 1
    MediaType.TV -> regularSeasons().sumOf { season -> season.episodes.count { it.hasAiredBy(todayEpochDay) } }
}

/**
 * The latest aired regular episode's "SxxExx" label (e.g. "S02E05"), or null for a
 * movie or a show with nothing aired yet. Ties (same air date) resolve to the
 * highest season/episode number, matching how TMDB orders a season's episode
 * list. A special is never the latest: it is not part of the count this label describes.
 */
fun MediaDetails.latestAiredEpisodeLabel(todayEpochDay: Long): String? {
    if (type != MediaType.TV) return null
    val latest = regularSeasons()
        .flatMap { it.episodes }
        .filter { it.hasAiredBy(todayEpochDay) }
        .maxWithOrNull(compareBy({ it.seasonNumber }, { it.episodeNumber }))
        ?: return null
    return "S${latest.seasonNumber.zeroPadded()}E${latest.episodeNumber.zeroPadded()}"
}

/** Seasons 1 onward. Season 0 (Specials) is excluded from every count the snapshot keeps (ADR 0037, #938). */
private fun MediaDetails.regularSeasons(): List<Season> = seasons.filter { it.number >= 1 }

// A local val (rather than `episode.airDateEpochDay != null && episode.airDateEpochDay <= ...`) because
// Kotlin can't smart-cast a nullable property declared in another module (`:models`) after a null check.
private fun Episode.hasAiredBy(todayEpochDay: Long): Boolean {
    val airDate = airDateEpochDay
    return airDate != null && airDate <= todayEpochDay
}

private fun Int.zeroPadded(): String = toString().padStart(2, '0')
