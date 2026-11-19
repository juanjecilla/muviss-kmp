package com.codingpit.muviss.feature.progress.api

import com.codingpit.muviss.models.Episode
import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.Season

/**
 * Pure helpers over a show's season/episode structure: the one "next episode" ordering. Watch-next
 * and Detail's primary action both answer through [nextUnseen] and [nextAnnounced] (via
 * [ProgressApi.nextEpisodes]), so the two surfaces cannot disagree about "next".
 *
 * It lives in `:api` because [ProgressApi.nextEpisodes] is the shared answer and its default body calls
 * this, while `:api` may not depend on `:domain`. It stays an `object` of pure functions: no state, no I/O.
 */
object EpisodeOrdering {

    /** Every episode across [seasons], in air order (season number, then episode number). */
    fun flatten(seasons: List<Season>): List<Episode> = seasons
        .sortedBy { it.number }
        .flatMap { season -> season.episodes.sortedBy { it.episodeNumber } }

    /**
     * The first aired episode not in [seen], or null if fully caught up (or nothing has aired).
     *
     * Season 0 specials are never "next" (ADR 0037): this walks regular seasons only, so an aired special
     * that was never watched does not stand in for the next episode. The consequence for Watch Next: a title
     * whose only unseen aired episodes are specials gets a null next episode and is dropped from the list
     * (`WatchNextItem.hasOnlySpecialsLeft`), rather than shown as "open the app" or prompted with a special.
     */
    fun nextUnseen(seasons: List<Season>, seen: Set<EpisodeId>, todayEpochDay: Long): Episode? = regular(seasons)
        .firstOrNull { episode ->
            val airDate = episode.airDateEpochDay
            episode.id !in seen && airDate != null && airDate <= todayEpochDay
        }

    /** The first regular episode not in [seen] whose air date is still ahead: the next one announced, if any. */
    fun nextAnnounced(seasons: List<Season>, seen: Set<EpisodeId>, todayEpochDay: Long): Episode? = regular(seasons)
        .firstOrNull { episode ->
            val airDate = episode.airDateEpochDay
            episode.id !in seen && airDate != null && airDate > todayEpochDay
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

    /** Regular seasons only (season 1 onward), in air order. */
    private fun regular(seasons: List<Season>): List<Episode> = flatten(seasons.filter { it.number >= 1 })
}
