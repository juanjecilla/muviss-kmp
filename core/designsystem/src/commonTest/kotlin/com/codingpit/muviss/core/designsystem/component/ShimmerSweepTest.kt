package com.codingpit.muviss.core.designsystem.component

import kotlin.test.Test
import kotlin.test.assertEquals

class ShimmerSweepTest {

    // The sweep restarts from progress 0 the frame after it reaches 1. Those
    // two frames are only indistinguishable if the highlight band is off the
    // shape in both, so the restart lands on a flat surface instead of a jump.

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
}
