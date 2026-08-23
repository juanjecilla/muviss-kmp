package com.codingpit.muviss.feature.progress.domain

import com.codingpit.muviss.models.Episode
import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.Season

/**
 * Pure helpers over a show's season/episode structure, shared by "next
 * unseen episode" (watch-next, used directly by `:ui`) and "mark previous as
 * seen" (Detail, used by `:data`'s [com.codingpit.muviss.feature.progress.api.ProgressApi]
 * bridge). Not exported via `:api` — peers pass raw [Season] lists in and
 * never need this ordering logic themselves.
 */
object EpisodeOrdering {

    /** Every episode across [seasons], in air order (season number, then episode number). */
    fun flatten(seasons: List<Season>): List<Episode> = seasons
        .sortedBy { it.number }
        .flatMap { season -> season.episodes.sortedBy { it.episodeNumber } }

    /** The first aired episode not in [seen], or null if fully caught up (or nothing has aired). */
    fun nextUnseen(seasons: List<Season>, seen: Set<EpisodeId>, todayEpochDay: Long): Episode? = flatten(seasons)
        .firstOrNull { episode ->
            val airDate = episode.airDateEpochDay
            episode.id !in seen && airDate != null && airDate <= todayEpochDay
        }

    /**
     * Every episode that has aired by [todayEpochDay], in show order.
     *
     * Deliberately not expressible as [upToInclusive] with the last aired
     * episode as its target: that walks a contiguous prefix and so would also
     * tick anything positioned *before* the target that has not aired — an
     * undated special in season 0, or a mid-season episode TMDB has no date
     * for yet. Those ticks are counted by
     * [WatchProgress][com.codingpit.muviss.core.model.WatchProgress]'s
     * `seenEpisodes` but not by its `airedEpisodes`, and that struct's
     * `require(seenEpisodes <= airedEpisodes)` throws. Filtering on air date
     * directly keeps the two counts consistent by construction.
     */
    fun airedBy(seasons: List<Season>, todayEpochDay: Long): List<EpisodeId> = flatten(seasons)
        .filter { episode ->
            val airDate = episode.airDateEpochDay
            airDate != null && airDate <= todayEpochDay
        }
        .map { it.id }

    /** Every episode at or before [target] in show order, inclusive of [target] itself. */
    fun upToInclusive(seasons: List<Season>, target: EpisodeId): List<EpisodeId> {
        val ordered = flatten(seasons)
        val targetIndex = ordered.indexOfFirst { it.id == target }
        if (targetIndex < 0) return listOf(target)
        return ordered.subList(0, targetIndex + 1).map { it.id }
    }
}
