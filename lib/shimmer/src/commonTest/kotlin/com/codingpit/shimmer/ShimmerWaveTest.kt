package com.codingpit.shimmer

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
        val early = bandStartInItem(progress = 0.2f, hostLeft = 0f, hostWidth = 300f, band = 240f, itemLeft = 0f)
        val later = bandStartInItem(progress = 0.6f, hostLeft = 0f, hostWidth = 300f, band = 240f, itemLeft = 0f)

        assertNotEquals(early, later)
    }

    @Test
    fun two_siblings_show_the_same_band_edge_on_screen() {
        // Item A starts at window x=0, item B at x=120. Shifted into each item, the band
        // must land at the same window position, or the highlight would break at the seam.
        val progress = 0.4f
        val band = 240f
        val inA = bandStartInItem(progress, hostLeft = 0f, hostWidth = 300f, band = band, itemLeft = 0f)
        val inB = bandStartInItem(progress, hostLeft = 0f, hostWidth = 300f, band = band, itemLeft = 120f)

        assertEquals(inA, inB + 120f)
    }
}
