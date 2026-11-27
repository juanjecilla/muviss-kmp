package com.codingpit.muviss.core.model

/**
 * The seen and aired counts a status is derived from, after the per-device choice about specials (ADR 0037, #938).
 */
data class CountedProgress(val seen: Int, val aired: Int)

/**
 * Whether season 0 (Specials) counts toward a title's progress. It is a per-device setting, off by default, and it
 * is applied here at read time only: the snapshot's own counts never include specials, so the setting is never
 * written into a synced row.
 *
 * The four inputs are raw counts, all regular-season except the two specials counts:
 * - [regularSeen] and [regularAired] are the snapshot's own counts (`CollectionEntry.airedEpisodes` and the
 *   regular ticks).
 * - [specialsSeen] is the ticked specials that are also in the cached aired catalog, and [specialsAired] is the
 *   cached aired specials. The read path computes the first as a join on the aired catalog, so a ticked special
 *   is counted only when it is also counted as aired. That keeps `WatchProgress`'s `seen <= aired` true even when
 *   the catalog cache is empty (an upgraded install until the app runs once) and a special is ticked.
 *
 * Every caller that derives a status, a Library count or a Watch Next count goes through here, so they cannot
 * disagree about what the setting does.
 */
object SpecialsCounting {
    fun count(
        regularSeen: Int,
        regularAired: Int,
        specialsSeen: Int,
        specialsAired: Int,
        countSpecials: Boolean,
    ): CountedProgress = if (countSpecials) {
        CountedProgress(seen = regularSeen + specialsSeen, aired = regularAired + specialsAired)
    } else {
        CountedProgress(seen = regularSeen, aired = regularAired)
    }
}
