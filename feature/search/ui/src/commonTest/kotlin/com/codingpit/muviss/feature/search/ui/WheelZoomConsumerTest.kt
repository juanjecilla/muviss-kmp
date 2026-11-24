package com.codingpit.muviss.feature.search.ui

import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [applyWheelZooms] against a scripted Telephoto: each animation the loop starts waits on the test, which then says
 * whether it finishes or a pan cuts it short part way (leaving the zoom where it stopped).
 */
class WheelZoomConsumerTest {

    private val at = Offset(100f, 200f)

    private sealed interface Outcome {
        data object Finish : Outcome
        data class Interrupt(val stoppedAt: Float) : Outcome
    }

    private inner class Harness(private val test: TestScope) {
        var zoom = 1f
        val queue = WheelZoomQueue()
        private val requests = Channel<Unit>(Channel.CONFLATED)
        val started = Channel<WheelZoomStep>(Channel.UNLIMITED)
        private val outcomes = Channel<Outcome>(Channel.UNLIMITED)

        val consumer = test.backgroundScope.launch {
            applyWheelZooms(queue, requests, { zoom }) { step ->
                started.send(step)
                when (outcomes.receive()) {
                    Outcome.Finish -> zoom *= step.factor
                    is Outcome.Interrupt -> throw CancellationException("a pan took over the zoom")
                }
            }
        }

        /** One wheel scroll reaching the queue, as the pointer loop delivers it. */
        fun scroll(deltaY: Float) {
            queue.request(zoom, deltaY, at)
            requests.trySend(Unit)
        }

        suspend fun nextStarted(): WheelZoomStep = started.receive()

        suspend fun finish() {
            outcomes.send(Outcome.Finish)
            test.runCurrent()
        }

        /**
         * A pan cuts the zoom short at [stoppedAt]. The picture is at [stoppedAt] the moment the pan lands, so the zoom is
         * set here, and the consumer only learns of it when it next runs. The test lets it run before going on, unless a
         * scroll is wanted in between ([scrollBeforeConsumerRuns]), which is the main-thread ordering that matters.
         */
        suspend fun interruptAt(stoppedAt: Float, scrollBeforeConsumerRuns: Float? = null) {
            zoom = stoppedAt
            outcomes.send(Outcome.Interrupt(stoppedAt))
            if (scrollBeforeConsumerRuns != null) {
                scroll(scrollBeforeConsumerRuns)
            }
            test.runCurrent()
        }
    }

    @Test
    fun a_pan_that_interrupts_a_zoom_leaves_the_wheel_working() = runTest {
        val wheel = Harness(this)

        wheel.scroll(-1f)
        assertEquals(1.1f, wheel.nextStarted().factor, 0.0001f, "the first scroll starts a 10% zoom in")
        wheel.interruptAt(stoppedAt = 1.04f)

        wheel.scroll(-1f)
        val afterInterrupt = wheel.nextStarted()
        assertEquals(1.1f, afterInterrupt.factor, 0.0001f, "the next scroll aims from where the zoom stopped, 1.04")
        wheel.finish()
        advanceUntilIdle()

        assertEquals(1.144f, wheel.zoom, 0.0001f)
        assertFalse(wheel.consumer.isCompleted, "an interrupted zoom must not end the wheel")
    }

    @Test
    fun a_scroll_that_arrives_during_an_interrupted_zoom_applies_from_the_zoom_on_screen() = runTest {
        val wheel = Harness(this)

        wheel.scroll(-1f)
        wheel.nextStarted()
        wheel.scroll(-1f)
        wheel.interruptAt(stoppedAt = 1.04f)

        // The scroll that came during the cut-short zoom is one 10% step, so it applies as 10% on the zoom on screen.
        val resumed = wheel.nextStarted()
        assertEquals(1.1f, resumed.factor, 0.0001f)
        wheel.finish()
        advanceUntilIdle()

        assertEquals(1.144f, wheel.zoom, 0.0001f)
    }

    @Test
    fun two_scrolls_during_a_zoom_that_a_pan_cuts_at_1_25_times_apply_from_1_25() = runTest {
        val wheel = Harness(this)

        // Two -5 scrolls are 1.5x each. The first starts a 1.5x zoom, the second arrives during it.
        wheel.scroll(-5f)
        assertEquals(1.5f, wheel.nextStarted().factor, 0.0001f)
        wheel.scroll(-5f)
        wheel.interruptAt(stoppedAt = 1.25f)

        assertEquals(1.5f, wheel.nextStarted().factor, 0.0001f, "the pending scroll is 1.5x of the zoom on screen, 1.25x")
        wheel.finish()
        advanceUntilIdle()

        assertEquals(1.875f, wheel.zoom, 0.0001f)
    }

    @Test
    fun a_scroll_that_lands_between_the_pan_and_the_consumer_noticing_is_applied_from_the_zoom_on_screen() = runTest {
        val wheel = Harness(this)

        wheel.scroll(-1f)
        wheel.nextStarted()
        // No runCurrent between the pan and the scroll: the consumer has not yet seen the interruption when it arrives.
        wheel.interruptAt(stoppedAt = 1.04f, scrollBeforeConsumerRuns = -1f)

        assertEquals(1.1f, wheel.nextStarted().factor, 0.0001f)
        wheel.finish()
        advanceUntilIdle()

        assertEquals(1.144f, wheel.zoom, 0.0001f)
    }

    @Test
    fun a_zoom_that_completes_normally_still_lets_the_next_scroll_compound_on_it() = runTest {
        val wheel = Harness(this)

        wheel.scroll(-1f)
        wheel.nextStarted()
        wheel.finish()
        wheel.scroll(-1f)
        assertEquals(1.1f, wheel.nextStarted().factor, 0.0001f)
        wheel.finish()
        advanceUntilIdle()

        assertEquals(1.21f, wheel.zoom, 0.0001f)
    }

    @Test
    fun cancelling_the_wheel_itself_ends_it_rather_than_reading_on() = runTest {
        val wheel = Harness(this)

        wheel.scroll(-1f)
        wheel.nextStarted()
        // The pointer input leaves composition while the zoom runs: the loop is cancelled, not the zoom.
        wheel.consumer.cancel()
        advanceUntilIdle()

        assertTrue(wheel.consumer.isCancelled)
        wheel.scroll(-1f)
        advanceUntilIdle()
        assertNull(wheel.started.tryReceive().getOrNull(), "a cancelled wheel must not start another zoom")
    }

    @Test
    fun an_interrupted_zoom_with_no_scroll_after_it_leaves_the_wheel_idle() = runTest {
        val wheel = Harness(this)

        wheel.scroll(-1f)
        wheel.nextStarted()
        wheel.interruptAt(stoppedAt = 1.04f)
        advanceUntilIdle()

        assertNull(wheel.started.tryReceive().getOrNull())
        assertEquals(1.04f, wheel.zoom, 0.0001f)
        assertFalse(wheel.consumer.isCompleted)
    }
}
