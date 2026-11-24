package com.codingpit.muviss.feature.search.ui

import androidx.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Sequences of wheel input against [WheelZoomQueue]. The zoom is modelled the way Telephoto applies it: a step
 * multiplies the zoom on screen, and nothing changes until that step's animation has finished ([settle]).
 */
class WheelZoomTest {

    private val at = Offset(100f, 200f)

    /** Runs one animation the way the viewer does: take the step, apply it to the zoom on screen, settle. */
    private fun WheelZoomQueue.animateOnce(onScreen: Float): Float {
        val step = assertNotNull(nextStep(onScreen))
        settled()
        return onScreen * step.factor
    }

    @Test
    fun two_quick_scrolls_compound_into_one_zoom_of_one_point_two_one() {
        val queue = WheelZoomQueue()
        queue.request(1f, -1f, at)
        queue.request(1f, -1f, at)

        val zoom = queue.animateOnce(1f)

        assertEquals(1.21f, zoom, 0.0001f)
        assertNull(queue.nextStep(zoom))
    }

    @Test
    fun a_scroll_arriving_during_an_animation_compounds_on_its_target_not_the_zoom_on_screen() {
        val queue = WheelZoomQueue()
        queue.request(1f, -1f, at)
        val step = assertNotNull(queue.nextStep(1f))
        // The first animation has not finished, so the screen still reads 1.0 while the second scroll arrives.
        queue.request(1f, -1f, at)
        assertEquals(1.1f, step.factor, 0.0001f)

        queue.settled()
        val second = assertNotNull(queue.nextStep(1.1f))
        assertEquals(1.21f / 1.1f, second.factor, 0.0001f)
        queue.settled()
    }

    @Test
    fun scrolls_after_a_pan_aim_from_the_zoom_on_screen_when_the_wheel_is_idle() {
        val queue = WheelZoomQueue()
        queue.request(2f, -1f, at)
        assertEquals(2.2f, queue.animateOnce(2f), 0.0001f)

        // A pan leaves the zoom alone, but the picture is now somewhere else; the next step aims from 2.2 as it stands.
        queue.request(2.2f, -1f, Offset(10f, 10f))
        val step = assertNotNull(queue.nextStep(2.2f))
        assertEquals(1.1f, step.factor, 0.0001f)
        assertEquals(Offset(10f, 10f), step.focalPoint)
        queue.settled()
    }

    @Test
    fun the_focal_point_is_the_latest_scroll_position() {
        val queue = WheelZoomQueue()
        queue.request(1f, -1f, Offset(1f, 1f))
        queue.request(1f, -1f, Offset(300f, 40f))

        assertEquals(Offset(300f, 40f), assertNotNull(queue.nextStep(1f)).focalPoint)
    }

    @Test
    fun scrolling_up_past_the_maximum_stops_at_the_maximum() {
        val queue = WheelZoomQueue()
        repeat(20) { queue.request(1f, -1f, at) }

        val zoom = queue.animateOnce(1f)
        assertEquals(MAX_USER_ZOOM, zoom, 0.0001f)

        // At the maximum a further scroll asks for nothing: the factor would be 1.
        queue.request(zoom, -1f, at)
        assertNull(queue.nextStep(zoom))
    }

    @Test
    fun scrolling_down_past_the_minimum_stops_at_the_minimum() {
        val queue = WheelZoomQueue()
        repeat(20) { queue.request(1f, 1f, at) }

        assertNull(queue.nextStep(1f))
    }

    @Test
    fun reversing_direction_takes_back_the_step_it_undoes() {
        val queue = WheelZoomQueue()
        queue.request(1f, -1f, at)
        queue.request(1f, 1f, at)

        assertNull(queue.nextStep(1f))
    }

    @Test
    fun reversing_while_an_animation_runs_aims_from_its_target() {
        val queue = WheelZoomQueue()
        queue.request(1f, -1f, at)
        assertNotNull(queue.nextStep(1f))
        queue.request(1f, 1f, at)

        queue.settled()
        val back = assertNotNull(queue.nextStep(1.1f))
        assertEquals(1f / 1.1f, back.factor, 0.0001f)
    }

    @Test
    fun a_scroll_that_moves_nothing_changes_nothing() {
        val queue = WheelZoomQueue()
        queue.request(1f, 0f, at)

        assertNull(queue.nextStep(1f))
    }

    @Test
    fun settling_keeps_a_target_that_a_scroll_has_already_extended() {
        val queue = WheelZoomQueue()
        queue.request(1f, -1f, at)
        assertNotNull(queue.nextStep(1f))
        queue.request(1f, -1f, at)

        queue.settled()

        assertEquals(1.21f, assertNotNull(queue.nextStep(1.1f)).factor * 1.1f, 0.0001f)
    }

    @Test
    fun an_interrupted_zoom_is_not_aimed_at_again_from_its_old_target() {
        val queue = WheelZoomQueue()
        queue.request(1f, -1f, at)
        assertNotNull(queue.nextStep(1f))

        // A pan stopped the zoom at 1.04: the next scroll aims from there, not at the 1.1 the animation was heading to.
        queue.interrupted()
        queue.request(1.04f, -1f, at)

        assertEquals(1.1f, assertNotNull(queue.nextStep(1.04f)).factor, 0.0001f)
    }
}
