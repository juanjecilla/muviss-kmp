package com.codingpit.muviss.feature.profile.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.codingpit.muviss.feature.profile.domain.GenreCount
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The donut's arithmetic, away from Compose.
 *
 * Test names are underscore_case because this is `commonTest`: Kotlin/Native
 * rejects punctuation inside backticked function names, and a comma is enough
 * to fail `compileTestKotlinIosArm64` long after `jvmTest` has gone green.
 */
class GenreDonutMathTest {

    private val size = Size(SIDE, SIDE)

    /** A point [radius] from the centre, [degrees] clockwise from twelve o'clock — the ring's own coordinates. */
    private fun pointAt(degrees: Float, radius: Float = MID_BAND): Offset {
        val radians = degrees * PI.toFloat() / 180f
        return Offset(
            x = SIDE / 2f + radius * sin(radians),
            y = SIDE / 2f - radius * cos(radians),
        )
    }

    @Test
    fun each_quarter_slice_is_hit_at_its_middle() {
        val quarters = listOf(90f, 90f, 90f, 90f)
        assertEquals(0, sliceIndexAt(pointAt(45f), size, quarters, STROKE))
        assertEquals(1, sliceIndexAt(pointAt(135f), size, quarters, STROKE))
        assertEquals(2, sliceIndexAt(pointAt(225f), size, quarters, STROKE))
        assertEquals(3, sliceIndexAt(pointAt(315f), size, quarters, STROKE))
    }

    @Test
    fun twelve_oclock_belongs_to_the_first_slice() {
        assertEquals(0, sliceIndexAt(pointAt(0f), size, listOf(90f, 90f, 90f, 90f), STROKE))
    }

    @Test
    fun the_last_degree_before_twelve_belongs_to_the_last_slice() {
        assertEquals(3, sliceIndexAt(pointAt(359.9f), size, listOf(90f, 90f, 90f, 90f), STROKE))
    }

    @Test
    fun sweeps_that_fall_short_of_360_still_close_the_ring() {
        // 100/3 split: the float sweeps sum to a hair under 360, which would
        // otherwise leave a dead wedge just before twelve o'clock.
        val thirds = List(3) { 360f * 1f / 3f }
        assertEquals(2, sliceIndexAt(pointAt(359.99f), size, thirds, STROKE))
    }

    @Test
    fun a_single_genre_owns_the_whole_ring() {
        val whole = listOf(360f)
        assertEquals(0, sliceIndexAt(pointAt(0f), size, whole, STROKE))
        assertEquals(0, sliceIndexAt(pointAt(180f), size, whole, STROKE))
        assertEquals(0, sliceIndexAt(pointAt(359.9f), size, whole, STROKE))
    }

    @Test
    fun a_sliver_of_a_slice_is_still_hittable() {
        val lopsided = listOf(359f, 1f)
        assertEquals(1, sliceIndexAt(pointAt(359.5f), size, lopsided, STROKE))
        assertEquals(0, sliceIndexAt(pointAt(358.5f), size, lopsided, STROKE))
    }

    @Test
    fun a_tap_in_the_hole_selects_nothing() {
        assertNull(sliceIndexAt(pointAt(45f, radius = INSIDE_HOLE), size, listOf(90f, 90f, 90f, 90f), STROKE))
    }

    @Test
    fun a_tap_past_the_outer_edge_selects_nothing() {
        assertNull(sliceIndexAt(pointAt(45f, radius = SIDE), size, listOf(90f, 90f, 90f, 90f), STROKE))
    }

    @Test
    fun a_tap_in_the_corner_of_the_square_selects_nothing() {
        assertNull(sliceIndexAt(Offset.Zero, size, listOf(90f, 90f, 90f, 90f), STROKE))
    }

    @Test
    fun no_sweeps_means_no_slice() {
        assertNull(sliceIndexAt(pointAt(45f), size, emptyList(), STROKE))
    }

    @Test
    fun share_rounds_to_the_nearest_percent() {
        assertEquals("13%", sharePercentLabel(count = 13, total = 98))
        assertEquals("51%", sharePercentLabel(count = 50, total = 98))
        assertEquals("100%", sharePercentLabel(count = 98, total = 98))
    }

    @Test
    fun a_share_too_small_to_round_to_a_percent_reads_less_than_one() {
        // The ring is drawing this slice, so "0%" would contradict the picture.
        assertEquals("<1%", sharePercentLabel(count = 1, total = 500))
    }

    @Test
    fun an_empty_slice_really_is_zero_percent() {
        assertEquals("0%", sharePercentLabel(count = 0, total = 98))
    }

    @Test
    fun an_empty_library_does_not_divide_by_zero() {
        assertEquals("0%", sharePercentLabel(count = 5, total = 0))
    }

    @Test
    fun fewer_genres_than_slots_are_left_alone() {
        val genres = listOf(GenreCount("Drama", 13), GenreCount("Action", 10))
        assertEquals(genres, foldGenresIntoOther(genres))
    }

    @Test
    fun exactly_six_genres_are_left_alone() {
        // The case the sixth palette colour exists for: no fold, six real
        // slices, so the palette must have six distinct hues.
        val genres = (1..6).map { GenreCount("Genre $it", it) }
        assertEquals(genres, foldGenresIntoOther(genres))
    }

    @Test
    fun a_seventh_genre_folds_the_tail_into_other() {
        val genres = (1..7).map { GenreCount("Genre $it", 10 - it) }
        val folded = foldGenresIntoOther(genres)

        assertEquals(6, folded.size)
        assertEquals(genres.take(5), folded.take(5))
        assertEquals("Other", folded.last().genre)
    }

    @Test
    fun other_carries_the_sum_of_everything_it_replaced() {
        val genres = (1..9).map { GenreCount("Genre $it", it) }
        val folded = foldGenresIntoOther(genres)

        assertEquals(genres.drop(5).sumOf { it.count }, folded.last().count)
        assertEquals(genres.sumOf { it.count }, folded.sumOf { it.count })
    }

    private companion object {
        const val SIDE = 160f

        /** The widest stroke the ring draws — the hit band is this thick. */
        const val STROKE = 40f

        /** Mid-band: outer radius 80, inner 40. */
        const val MID_BAND = 60f
        const val INSIDE_HOLE = 20f
    }
}
