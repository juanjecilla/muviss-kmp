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
 * whether it finishes or a pan interrupts it part way (leaving the zoom where it stopped).
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
                when (val outcome = outcomes.receive()) {
                    Outcome.Finish -> zoom *= step.factor

                    is Outcome.Interrupt -> {
                        zoom = outcome.stoppedAt
                        throw CancellationException("a pan took over the zoom")
                    }
                }
            }
        }

        /** One wheel scroll reaching the queue, as the pointer loop delivers it. */
        fun scroll(deltaY: Float) {
            queue.request(zoom, deltaY, at)
            requests.trySend(Unit)
        }

        suspend fun nextStarted(): WheelZoomStep = started.receive()

        // The consumer's resumptions are background work, which advanceUntilIdle does not run, so each outcome is let
        // through with runCurrent before the test reads the zoom or scrolls again. Otherwise a scroll would be read
        // against a zoom the animation has not written yet.
        suspend fun finish() {
            outcomes.send(Outcome.Finish)
            test.runCurrent()
        }

        suspend fun interruptAt(stoppedAt: Float) {
            outcomes.send(Outcome.Interrupt(stoppedAt))
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
    fun a_scroll_that_arrives_during_an_interrupted_zoom_still_compounds_on_its_target() = runTest {
        val wheel = Harness(this)

        wheel.scroll(-1f)
        wheel.nextStarted()
        wheel.scroll(-1f)
        wheel.interruptAt(stoppedAt = 1.04f)

        // The target the queued scroll reached, 1.21, is kept, and the step is taken from the zoom where it stopped.
        val resumed = wheel.nextStarted()
        assertEquals(1.21f / 1.04f, resumed.factor, 0.0001f)
        wheel.finish()
        advanceUntilIdle()

        assertEquals(1.21f, wheel.zoom, 0.0001f)
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
        runCurrent()

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
