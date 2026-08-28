package com.codingpit.muviss.core.designsystem.component

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Ratings are shown out of five but stored out of ten (see [RatingScale]), so
 * this conversion is the only thing standing between "9" on a poster badge and
 * "4.5 stars" on the detail screen meaning the same thing.
 */
class RatingScaleTest {

    @Test
    fun a_stored_value_halves_onto_the_five_point_scale() {
        assertEquals(5f, RatingScale.starsOf(10))
        assertEquals(3.5f, RatingScale.starsOf(7))
        assertEquals(0.5f, RatingScale.starsOf(1))
    }

    @Test
    fun a_whole_number_of_stars_drops_its_trailing_zero() {
        assertEquals("5", RatingScale.label(10))
        assertEquals("3", RatingScale.label(6))
    }

    @Test
    fun a_half_star_keeps_its_fraction() {
        assertEquals("4.5", RatingScale.label(9))
        assertEquals("0.5", RatingScale.label(1))
    }

    @Test
    fun the_left_half_of_a_star_is_the_odd_value_and_the_right_half_the_even_one() {
        // Star index 0 covers stored 1 and 2, index 4 covers 9 and 10.
        assertEquals(1, RatingScale.valueForTap(starIndex = 0, leftHalf = true))
        assertEquals(2, RatingScale.valueForTap(starIndex = 0, leftHalf = false))
        assertEquals(9, RatingScale.valueForTap(starIndex = 4, leftHalf = true))
        assertEquals(10, RatingScale.valueForTap(starIndex = 4, leftHalf = false))
    }

    @Test
    fun a_star_is_full_only_once_the_stored_value_reaches_its_even_step() {
        assertEquals(StarFill.EMPTY, RatingScale.fillOf(stored = null, starIndex = 0))
        assertEquals(StarFill.HALF, RatingScale.fillOf(stored = 1, starIndex = 0))
        assertEquals(StarFill.FULL, RatingScale.fillOf(stored = 2, starIndex = 0))
        assertEquals(StarFill.EMPTY, RatingScale.fillOf(stored = 2, starIndex = 1))
        assertEquals(StarFill.HALF, RatingScale.fillOf(stored = 7, starIndex = 3))
        assertEquals(StarFill.FULL, RatingScale.fillOf(stored = 7, starIndex = 2))
    }
}
