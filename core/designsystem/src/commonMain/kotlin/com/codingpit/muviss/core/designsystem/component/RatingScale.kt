package com.codingpit.muviss.core.designsystem.component

/** How much of one star is lit. */
enum class StarFill {
    EMPTY,
    HALF,
    FULL,
}

/**
 * The one place the personal rating's two scales meet.
 *
 * Ratings are **stored** 1-10 — that is what the `collectionEntry.rating`
 * column holds, what `SetRatingUseCase` validates, what every importer
 * (Trakt, TV Time, generic CSV) produces, and what `SyncChangeSet` carries.
 * Ratings are **shown** out of five, as five stars with half-star precision,
 * which is the same information at the same resolution: ten distinct values
 * either way. So the display change needed no migration and no change below
 * the UI — only this conversion.
 *
 * Star index is 0-based: index `i` covers stored values `2i+1` (its left half)
 * and `2i+2` (its right half).
 */
object RatingScale {

    /** Stored values a rating may take, mirroring `SetRatingUseCase`'s own range. */
    const val MAX_STORED = 10

    /** Stars rendered — five, each worth two stored points. */
    const val STAR_COUNT = 5

    /** The stored [rating] as a number of stars, e.g. 7 -> 3.5. */
    fun starsOf(rating: Int): Float = rating / 2f

    /** [starsOf] as display text, without a pointless trailing ".0" — 10 -> "5", 9 -> "4.5". */
    fun label(rating: Int): String {
        val whole = rating / 2
        return if (rating % 2 == 0) whole.toString() else "$whole.5"
    }

    /** The stored value a tap lands on: the left half of a star is its odd value, the right half its even one. */
    fun valueForTap(starIndex: Int, leftHalf: Boolean): Int = starIndex * 2 + if (leftHalf) 1 else 2

    /** How much of the star at [starIndex] the stored [stored] value lights up. */
    fun fillOf(stored: Int?, starIndex: Int): StarFill {
        val rating = stored ?: return StarFill.EMPTY
        val full = starIndex * 2 + 2
        return when {
            rating >= full -> StarFill.FULL
            rating == full - 1 -> StarFill.HALF
            else -> StarFill.EMPTY
        }
    }
}
