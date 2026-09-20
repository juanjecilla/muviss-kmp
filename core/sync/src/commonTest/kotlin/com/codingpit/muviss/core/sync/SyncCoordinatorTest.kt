@file:OptIn(ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.core.sync

import com.codingpit.muviss.core.common.AppClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private const val CLOCK_BASE = 1_000_000L

/** One recorded call to the runner: which trigger, and at what virtual millisecond. */
private data class Call(val trigger: SyncTrigger, val atMs: Long)

/**
 * A [SyncRunner] that lives in virtual time. It records when it was called,
 * takes [durationMs] to run, and lets a test decide the outcome and what the
 * run did to the dirty-row count (a real push clears it).
 */
private class FakeRunner(private val scope: TestScope, private val pending: MutableStateFlow<Long>) : SyncRunner {
    val calls = mutableListOf<Call>()
    var durationMs = 0L
    var outcome: (SyncTrigger) -> SyncOutcome = { OK }
    var clearsPending = true
    var lastFinishedOverride: Long? = null

    override var lastFinishedAtEpochMs: Long? = null

    override suspend fun syncNow(trigger: SyncTrigger): SyncOutcome {
        calls += Call(trigger, scope.testScheduler.currentTime)
        delay(durationMs)
        val result = outcome(trigger)
        if (result is SyncOutcome.Success && clearsPending) pending.value = 0
        lastFinishedAtEpochMs = lastFinishedOverride ?: (CLOCK_BASE + scope.testScheduler.currentTime)
        return result
    }

    fun changeCalls() = calls.filter { it.trigger == SyncTrigger.Change }

    companion object {
        val OK = SyncOutcome.Success(pushedCount = 1, pulledCount = 0, syncedAtEpochMs = 0)
    }
}

private fun offline() = SyncOutcome.Failed(SyncFailureReason.Offline, "no route")

class SyncCoordinatorTest {

    private class Rig(val test: TestScope, timing: SyncTiming = SyncTiming(), enabled: Boolean = true) {
        val pending = MutableStateFlow(0L)
        val enabled = MutableStateFlow(enabled)
        val runner = FakeRunner(test, pending)
        val clock = object : AppClock {
            override fun nowEpochMs(): Long = CLOCK_BASE + test.testScheduler.currentTime
        }
        val coordinator = SyncCoordinator(runner, pending, this.enabled, clock, test.backgroundScope, timing)

        fun start() = coordinator.start()

        suspend fun advanceBy(duration: Duration) {
            test.advanceTimeBy(duration)
            test.testScheduler.runCurrent()
        }
    }

    private fun TestScope.rig(timing: SyncTiming = SyncTiming(), enabled: Boolean = true) = Rig(this, timing, enabled)

    // --- the debounced change push -------------------------------------------

    @Test
    fun a_burst_of_fifty_writes_coalesces_into_one_run() = runTest {
        val rig = rig().also { it.start() }
        runCurrent()

        repeat(50) { i ->
            rig.pending.value = i + 1L
            rig.advanceBy(100.milliseconds)
        }
        rig.advanceBy(10.seconds)

        assertEquals(1, rig.runner.changeCalls().size, "50 writes inside the debounce window are one run, not 50")
    }

    @Test
    fun the_run_waits_for_the_debounce_window_after_the_last_write() = runTest {
        val rig = rig().also { it.start() }
        runCurrent()

        rig.pending.value = 1
        rig.advanceBy(4_900.milliseconds)
        assertEquals(0, rig.runner.calls.size, "not before the debounce has elapsed")

        rig.advanceBy(200.milliseconds)
        assertEquals(listOf(SyncTrigger.Change), rig.runner.calls.map { it.trigger })
        assertEquals(5_000L, rig.runner.calls.single().atMs)
    }

    @Test
    fun nothing_dirty_means_nothing_runs() = runTest {
        val rig = rig().also { it.start() }
        runCurrent()

        rig.advanceBy(2.hours)

        assertEquals(emptyList(), rig.runner.calls)
    }

    @Test
    fun writes_that_predate_the_switch_are_sent_when_it_turns_on() = runTest {
        val rig = rig(enabled = false).also { it.start() }
        rig.pending.value = 3
        rig.advanceBy(1.minutes)
        assertEquals(emptyList(), rig.runner.calls, "switch off: the writes wait")

        rig.enabled.value = true
        rig.advanceBy(6.seconds)

        assertEquals(1, rig.runner.changeCalls().size)
    }

    @Test
    fun a_run_at_most_every_thirty_seconds() = runTest {
        val rig = rig().also { it.start() }
        runCurrent()

        rig.pending.value = 1
        rig.advanceBy(5.seconds)
        assertEquals(listOf(5_000L), rig.runner.changeCalls().map { it.atMs })

        // Written 1s later; the debounce would allow a run at 11s, the rate limit says 35s.
        rig.advanceBy(1.seconds)
        rig.pending.value = 1
        rig.advanceBy(28.seconds)
        assertEquals(1, rig.runner.changeCalls().size, "still inside 30s of the first run")

        rig.advanceBy(2.seconds)
        assertEquals(listOf(5_000L, 35_000L), rig.runner.changeCalls().map { it.atMs })
    }

    @Test
    fun an_edit_made_during_a_run_is_sent_by_the_next_one() = runTest {
        val rig = rig().also { it.start() }
        rig.runner.durationMs = 2_000
        runCurrent()

        rig.pending.value = 3
        rig.advanceBy(6.seconds) // run in flight, started at 5s
        // The user edits a row while the push is in the air: it is one of the
        // rows the run cannot clear, so the count settles at 1 afterwards.
        rig.pending.value = 4
        rig.runner.clearsPending = false
        rig.advanceBy(2.seconds)
        rig.pending.value = 1
        rig.advanceBy(2.minutes)

        assertEquals(2, rig.runner.changeCalls().size, "the row left dirty by the first run gets its own")
        assertTrue(rig.runner.changeCalls()[1].atMs >= 5_000L + 30_000L)
    }

    // --- failure and backoff ----------------------------------------------------

    @Test
    fun a_failing_run_backs_off_exponentially_and_caps_at_fifteen_minutes() = runTest {
        val rig = rig().also { it.start() }
        rig.runner.outcome = { offline() }
        runCurrent()

        rig.pending.value = 1
        rig.advanceBy(2.hours)

        val starts = rig.runner.changeCalls().map { it.atMs }
        val gaps = starts.zipWithNext { a, b -> (b - a).milliseconds }
        assertEquals(5_000L, starts.first())
        assertEquals(
            listOf(30.seconds, 60.seconds, 120.seconds, 240.seconds, 480.seconds, 15.minutes, 15.minutes),
            gaps.take(7),
        )
    }

    @Test
    fun a_success_resets_the_backoff() = runTest {
        val rig = rig().also { it.start() }
        var failures = 3
        rig.runner.outcome = { if (failures-- > 0) offline() else FakeRunner.OK }
        runCurrent()

        rig.pending.value = 1
        rig.advanceBy(20.minutes) // fail at 5s, 35s, 95s; succeed at 215s
        val runsAfterRecovery = rig.runner.changeCalls().size
        assertEquals(4, runsAfterRecovery)
        assertEquals(0L, rig.pending.value)

        // A new failure starts again at the shortest step, not where it left off.
        rig.runner.outcome = { offline() }
        val before = rig.test.testScheduler.currentTime
        rig.pending.value = 1
        rig.advanceBy(3.minutes)

        val starts = rig.runner.changeCalls().drop(runsAfterRecovery).map { it.atMs }
        assertTrue(starts.size >= 2, "expected a run and a retry, got $starts")
        assertEquals(30_000L, starts[1] - starts[0], "first backoff step is 30s again")
        assertTrue(starts[0] >= before)
    }

    @Test
    fun a_foreground_success_also_resets_the_backoff() = runTest {
        val rig = rig().also { it.start() }
        rig.runner.outcome = { trigger -> if (trigger == SyncTrigger.Foreground) FakeRunner.OK else offline() }
        runCurrent()

        rig.pending.value = 1
        rig.advanceBy(96.seconds) // fails at 5s, 35s and 95s: the next wait would be 120s
        assertEquals(3, rig.runner.changeCalls().size)

        rig.advanceBy(65.seconds) // outside the 60s skip window that the last failed run opened
        // The app comes forward and reaches the server. That run pushed
        // everything (the fake clears the count), so the pending retry has
        // nothing left to do, and the failure streak is over.
        rig.coordinator.onForeground()
        runCurrent()
        assertEquals(0L, rig.pending.value)

        rig.advanceBy(5.minutes)
        val before = rig.runner.changeCalls().size
        rig.pending.value = 1
        rig.advanceBy(3.minutes)

        val starts = rig.runner.changeCalls().drop(before).map { it.atMs }
        assertEquals(30_000L, starts[1] - starts[0], "the streak restarted at the first backoff step, not the fourth")
    }

    @Test
    fun a_dead_session_is_not_retried_in_a_loop() = runTest {
        val rig = rig().also { it.start() }
        rig.runner.outcome = { SyncOutcome.Failed(SyncFailureReason.Unauthorised, "401") }
        runCurrent()

        rig.pending.value = 1
        rig.advanceBy(2.hours)

        assertEquals(1, rig.runner.changeCalls().size)
    }

    private fun assertNotRetried(skipped: SyncOutcome) = runTest {
        val rig = rig().also { it.start() }
        rig.runner.outcome = { skipped }
        runCurrent()

        rig.pending.value = 1
        rig.advanceBy(2.hours)

        assertEquals(1, rig.runner.changeCalls().size, "$skipped")

        // A later write is a fresh reason to try: the person may have signed in since.
        rig.pending.value = 2
        rig.advanceBy(1.minutes)
        assertEquals(2, rig.runner.changeCalls().size, "$skipped")
    }

    @Test
    fun a_disabled_run_is_not_retried_in_a_loop() = assertNotRetried(SyncOutcome.Disabled)

    @Test
    fun a_signed_out_run_is_not_retried_in_a_loop() = assertNotRetried(SyncOutcome.NotSignedIn)

    @Test
    fun an_unentitled_run_is_not_retried_in_a_loop() = assertNotRetried(SyncOutcome.NotEntitled)

    @Test
    fun an_account_change_is_not_retried_in_a_loop() = assertNotRetried(SyncOutcome.AccountChanged("a", "b"))

    // --- no feedback loop -------------------------------------------------------

    @Test
    fun a_pull_does_not_trigger_another_run() = runTest {
        val rig = rig().also { it.start() }
        // The cycle pushed one row and pulled fifty. Pulled rows are written
        // clean, so the dirty count goes 1 -> 0 and never above.
        rig.runner.outcome = { SyncOutcome.Success(pushedCount = 1, pulledCount = 50, syncedAtEpochMs = 0) }
        runCurrent()

        rig.pending.value = 1
        rig.advanceBy(3.hours)

        assertEquals(1, rig.runner.changeCalls().size)
    }

    // --- the switch ---------------------------------------------------------------

    @Test
    fun switching_off_before_the_debounce_expires_cancels_the_run() = runTest {
        val rig = rig().also { it.start() }
        runCurrent()

        rig.pending.value = 1
        rig.advanceBy(2.seconds)
        rig.enabled.value = false
        rig.advanceBy(1.hours)

        assertEquals(emptyList(), rig.runner.calls)
    }

    @Test
    fun switching_off_during_a_backoff_wait_stops_the_retries() = runTest {
        val rig = rig().also { it.start() }
        rig.runner.outcome = { offline() }
        runCurrent()

        rig.pending.value = 1
        rig.advanceBy(6.seconds)
        assertEquals(1, rig.runner.changeCalls().size)

        rig.enabled.value = false
        rig.advanceBy(3.hours)

        assertEquals(1, rig.runner.changeCalls().size, "no further runs once the switch is off")
    }

    @Test
    fun switching_off_and_on_again_watches_afresh() = runTest {
        val rig = rig().also { it.start() }
        runCurrent()

        rig.enabled.value = false
        rig.advanceBy(1.seconds)
        rig.enabled.value = true
        rig.pending.value = 2
        rig.advanceBy(6.seconds)

        assertEquals(1, rig.runner.changeCalls().size)
    }

    @Test
    fun starting_twice_does_not_double_the_runs() = runTest {
        val rig = rig().also { it.start() }
        rig.start()
        runCurrent()

        rig.pending.value = 1
        rig.advanceBy(10.seconds)

        assertEquals(1, rig.runner.changeCalls().size)
    }

    // --- foreground and resume ----------------------------------------------------

    @Test
    fun a_foreground_with_no_recent_run_asks_the_runner() = runTest {
        val rig = rig()

        rig.coordinator.onForeground()
        runCurrent()

        assertEquals(listOf(SyncTrigger.Foreground), rig.runner.calls.map { it.trigger })
    }

    @Test
    fun a_foreground_within_sixty_seconds_of_a_finished_run_is_skipped() = runTest {
        val rig = rig()
        rig.runner.lastFinishedAtEpochMs = CLOCK_BASE - 59_000L

        rig.coordinator.onForeground()
        runCurrent()

        assertEquals(emptyList(), rig.runner.calls)
    }

    @Test
    fun a_foreground_after_the_window_runs() = runTest {
        val rig = rig()
        rig.runner.lastFinishedAtEpochMs = CLOCK_BASE - 60_000L

        rig.coordinator.onForeground()
        runCurrent()

        assertEquals(1, rig.runner.calls.size)
    }

    @Test
    fun the_window_is_measured_from_when_the_last_run_finished_not_started() = runTest {
        val rig = rig()
        rig.runner.durationMs = 40_000
        rig.coordinator.onForeground()
        runCurrent()
        rig.advanceBy(41.seconds) // the first run finishes at 40s

        rig.advanceBy(50.seconds) // 90s in, 50s after it finished
        rig.coordinator.onForeground()
        runCurrent()

        assertEquals(1, rig.runner.calls.size, "50s after the last run finished is still inside the window")
    }

    @Test
    fun on_start_and_on_resume_arriving_together_are_one_run() = runTest {
        val rig = rig()
        rig.runner.durationMs = 500

        rig.coordinator.onForeground()
        rig.coordinator.onForeground()
        rig.coordinator.onResume()
        runCurrent()
        rig.advanceBy(2.seconds)

        assertEquals(1, rig.runner.calls.size)
    }

    @Test
    fun resume_shares_the_foreground_skip_window_and_uses_its_own_trigger() = runTest {
        val rig = rig()

        rig.coordinator.onResume()
        runCurrent()
        assertEquals(listOf(SyncTrigger.Resume), rig.runner.calls.map { it.trigger })

        rig.advanceBy(30.seconds)
        rig.coordinator.onResume()
        runCurrent()
        assertEquals(1, rig.runner.calls.size, "a second visibilitychange 30s later is skipped")

        rig.advanceBy(31.seconds)
        rig.coordinator.onResume()
        runCurrent()
        assertEquals(2, rig.runner.calls.size)
    }

    @Test
    fun the_foreground_skip_does_not_apply_to_a_periodic_tick() = runTest {
        val rig = rig()
        rig.runner.lastFinishedAtEpochMs = CLOCK_BASE - 1_000L

        val outcome = rig.coordinator.runPeriodic()

        assertEquals(FakeRunner.OK, outcome)
        assertEquals(listOf(SyncTrigger.Periodic), rig.runner.calls.map { it.trigger })
    }

    @Test
    fun a_foreground_does_not_depend_on_the_coordinator_having_been_started() = runTest {
        // start() is what watches for writes; foreground goes straight to the runner.
        val rig = rig(enabled = false)

        rig.coordinator.onForeground()
        runCurrent()

        assertEquals(1, rig.runner.calls.size, "the runner (SyncEngine) is the one that says Disabled")
    }
}
