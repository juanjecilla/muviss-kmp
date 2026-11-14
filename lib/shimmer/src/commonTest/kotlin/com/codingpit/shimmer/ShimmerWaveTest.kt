package com.codingpit.shimmer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ShimmerWaveTest {

    // The sweep restarts from progress 0 the frame after it reaches 1. Those
    // two frames only look the same if the band is off the host in both.

    @Test
    fun the_band_starts_just_off_the_left_edge() {
        assertEquals(-50f, shimmerBandStart(progress = 0f, width = 100f, band = 50f))
    }

    @Test
    fun the_band_ends_just_off_the_right_edge() {
        assertEquals(100f, shimmerBandStart(progress = 1f, width = 100f, band = 50f))
    }

    @Test
    fun the_band_moves_at_a_constant_speed_between_the_edges() {
        assertEquals(25f, shimmerBandStart(progress = 0.5f, width = 100f, band = 50f))
    }

    @Test
    fun the_wave_is_in_different_places_at_two_clock_times() {
        val direction = sweepDirection(0f)
        val early = bandStartAlongSweep(0.2f, Offset(0f, 0f), Size(300f, 200f), direction, band = 240f)
        val later = bandStartAlongSweep(0.6f, Offset(0f, 0f), Size(300f, 200f), direction, band = 240f)

        assertNotEquals(early, later)
    }

    @Test
    fun two_siblings_show_the_same_band_edge_on_screen() {
        // Item A starts at window x=0, item B at x=120. The band is computed once for the
        // host, so both items must draw its ends at the same window position.
        val direction = sweepDirection(0f)
        val bandStart = bandStartAlongSweep(0.4f, Offset(0f, 0f), Size(300f, 200f), direction, band = 240f)
        val (startInA, _) = bandEndsInItem(bandStart, 240f, direction, itemTopLeft = Offset(0f, 0f))
        val (startInB, _) = bandEndsInItem(bandStart, 240f, direction, itemTopLeft = Offset(120f, 0f))

        assertEquals(startInA.x, startInB.x + 120f)
    }

    // The angle is the sweep's direction in degrees: 0 left to right, 90 top to bottom.

    @Test
    fun the_default_angle_sweeps_left_to_right() {
        val direction = sweepDirection(0f)

        assertEquals(1f, direction.x, absoluteTolerance = 1e-6f)
        assertEquals(0f, direction.y, absoluteTolerance = 1e-6f)
    }

    @Test
    fun the_default_angle_draws_the_band_the_horizontal_sweep_drew_before() {
        // Host at x=30 (y=10), 300 wide; item at x=50. The old horizontal arithmetic gave
        // hostLeft + (-240 + 0.3 * 540) - itemLeft = 30 - 78 - 50 = -98.
        val direction = sweepDirection(0f)
        val bandStart = bandStartAlongSweep(0.3f, Offset(30f, 10f), Size(300f, 200f), direction, band = 240f)
        val (start, _) = bandEndsInItem(bandStart, 240f, direction, itemTopLeft = Offset(50f, 10f))

        assertEquals(-98f, start.x, absoluteTolerance = 1e-3f)
    }

    @Test
    fun a_quarter_turn_sweeps_top_to_bottom() {
        val direction = sweepDirection(90f)

        assertEquals(0f, direction.x, absoluteTolerance = 1e-6f)
        assertEquals(1f, direction.y, absoluteTolerance = 1e-6f)
    }

    @Test
    fun at_a_quarter_turn_the_band_starts_above_the_host_and_ends_below_it() {
        // 100 wide, 200 tall: the sweep covers 200 and the band is half of that.
        val direction = sweepDirection(90f)
        val start = bandStartAlongSweep(0f, Offset(0f, 0f), Size(100f, 200f), direction, band = 100f)
        val middle = bandStartAlongSweep(0.5f, Offset(0f, 0f), Size(100f, 200f), direction, band = 100f)
        val end = bandStartAlongSweep(1f, Offset(0f, 0f), Size(100f, 200f), direction, band = 100f)

        assertEquals(-100f, start, absoluteTolerance = 1e-3f)
        assertEquals(50f, middle, absoluteTolerance = 1e-3f)
        assertEquals(200f, end, absoluteTolerance = 1e-3f)
    }

    @Test
    fun a_diagonal_sweep_covers_the_host_projected_on_its_direction() {
        // 100 by 200 at 45 degrees: 100 cos 45 + 200 sin 45.
        val length = sweepLength(100f, 200f, sweepDirection(45f))

        assertEquals(300f * sin(PI.toFloat() / 4f), length, absoluteTolerance = 1e-3f)
    }

    @Test
    fun a_diagonal_band_starts_just_off_the_top_left_corner_of_a_square() {
        // Square 100 by 100 at 45 degrees: the projection is 100 * sqrt(2), the band half of it.
        val direction = sweepDirection(45f)
        val length = sweepLength(100f, 100f, direction)
        val start = bandStartAlongSweep(0f, Offset(0f, 0f), Size(100f, 100f), direction, band = length / 2f)

        assertEquals(-length / 2f, start, absoluteTolerance = 1e-3f)
    }

    @Test
    fun a_host_away_from_the_origin_starts_its_band_from_its_own_top_edge() {
        // Host at (40, 60), 100 by 200, a quarter turn: its top edge is at y=60.
        val start = bandStartAlongSweep(0f, Offset(40f, 60f), Size(100f, 200f), sweepDirection(90f), band = 100f)

        assertEquals(-40f, start, absoluteTolerance = 1e-3f)
    }

    @Test
    fun a_leftward_sweep_starts_from_the_right_edge() {
        // A half turn: the band starts wholly right of a 100-wide host at x=0.
        val direction = sweepDirection(180f)
        val start = bandStartAlongSweep(0f, Offset(0f, 0f), Size(100f, 200f), direction, band = 50f)

        assertEquals(-150f, start, absoluteTolerance = 1e-3f)
    }

    @Test
    fun the_wave_is_in_different_places_at_two_clock_times_at_an_angle() {
        val direction = sweepDirection(45f)
        val early = bandStartAlongSweep(0.2f, Offset(0f, 0f), Size(300f, 200f), direction, band = 240f)
        val later = bandStartAlongSweep(0.6f, Offset(0f, 0f), Size(300f, 200f), direction, band = 240f)

        assertNotEquals(early, later)
    }

    @Test
    fun two_siblings_share_one_band_edge_on_screen_at_a_quarter_turn() {
        // Items stacked vertically: both must draw the band's ends at the same window points.
        val direction = sweepDirection(90f)
        val bandStart = bandStartAlongSweep(0.4f, Offset(0f, 0f), Size(300f, 200f), direction, band = 80f)
        val (startInTop, endInTop) = bandEndsInItem(bandStart, 80f, direction, itemTopLeft = Offset(0f, 0f))
        val (startInBelow, endInBelow) = bandEndsInItem(bandStart, 80f, direction, itemTopLeft = Offset(0f, 120f))

        assertEquals(startInTop.y, startInBelow.y + 120f, absoluteTolerance = 1e-3f)
        assertEquals(endInTop.y, endInBelow.y + 120f, absoluteTolerance = 1e-3f)
    }

    @Test
    fun the_band_ends_are_placed_along_the_sweep_direction_inside_an_item() {
        // A quarter turn, item at y=120: the band starts at window y=30 and ends at y=80.
        val direction = sweepDirection(90f)
        val (start, end) = bandEndsInItem(30f, 50f, direction, itemTopLeft = Offset(0f, 120f))

        assertEquals(0f, start.x, absoluteTolerance = 1e-3f)
        assertEquals(-90f, start.y, absoluteTolerance = 1e-3f)
        assertEquals(-40f, end.y, absoluteTolerance = 1e-3f)
    }

    @Test
    fun the_direction_is_a_unit_vector_at_any_angle() {
        val angle = 37f
        val direction = sweepDirection(angle)

        assertEquals(cos(angle * PI.toFloat() / 180f), direction.x, absoluteTolerance = 1e-5f)
        assertEquals(sin(angle * PI.toFloat() / 180f), direction.y, absoluteTolerance = 1e-5f)
    }
}
