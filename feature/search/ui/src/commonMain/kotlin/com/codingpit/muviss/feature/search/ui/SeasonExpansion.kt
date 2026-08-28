package com.codingpit.muviss.feature.search.ui

import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.Season

/**
 * Which season a TV detail screen opens expanded, and which fold away once
 * marked seen.
 *
 * Before this, every episode of every season rendered at once — a nine-season
 * show was an unbroken wall of a hundred-odd rows. Seasons are collapsed by
 * default now, which only works if the one you actually came for is already
 * open.
 */
internal object SeasonExpansion {

    /**
     * The index of the season holding the first unseen *aired* episode, or
     * null when there is nothing left to tick.
     *
     * Aired, because a season that hasn't started airing is not where the user
     * left off — opening it would show a list of rows none of which can be
     * ticked.
     */
    fun initiallyExpandedIndex(seasons: List<Season>, seen: Set<EpisodeId>, todayEpochDay: Long): Int? {
        val index = seasons.indexOfFirst { season ->
            season.episodes.any { episode ->
                val airDate = episode.airDateEpochDay
                episode.id !in seen && airDate != null && airDate <= todayEpochDay
            }
        }
        return index.takeIf { it >= 0 }
    }

    /**
     * Whether a season should fold itself away after being marked seen.
     *
     * Every season but the last: finishing season 3 of 9 is a good moment to
     * get it out of the way. The last season is where the next episode lands,
     * so collapsing it would hide exactly what the user is waiting on.
     */
    fun collapsesAfterMarking(seasonIndex: Int, seasonCount: Int): Boolean = seasonIndex < seasonCount - 1
}
