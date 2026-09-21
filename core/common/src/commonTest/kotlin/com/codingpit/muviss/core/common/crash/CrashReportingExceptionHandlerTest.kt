package com.codingpit.muviss.core.common.crash

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CrashReportingExceptionHandlerTest {

    // Unconfined so a launch that throws has thrown by the time `launch` returns,
    // on every target — these run on JS and Wasm too, where there is no blocking.
    private fun scope(reported: MutableList<Throwable>) = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined + reportingExceptionHandler { reported += it })

    @Test
    fun a_failure_in_a_launch_is_reported_and_not_rethrown() {
        val reported = mutableListOf<Throwable>()
        val boom = IllegalStateException("boom")

        // If the handler rethrew, this call would throw and the test would fail here.
        scope(reported).launch { throw boom }

        assertEquals(listOf<Throwable>(boom), reported)
    }

    @Test
    fun a_sibling_launch_keeps_running_after_one_fails() {
        val reported = mutableListOf<Throwable>()
        val scope = scope(reported)
        var siblingRan = false

        scope.launch { throw IllegalStateException("boom") }
        scope.launch { siblingRan = true }

        assertTrue(siblingRan)
    }

    @Test
    fun cancellation_is_not_a_failure_and_is_not_reported() {
        val reported = mutableListOf<Throwable>()

        scope(reported).launch { throw CancellationException("navigated away") }

        assertTrue(reported.isEmpty())
    }

    @Test
    fun a_reporter_that_throws_does_not_escape_the_handler() {
        val handler = reportingExceptionHandler { error("reporter down") }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined + handler)

        scope.launch { throw IllegalStateException("boom") }
    }

    @Test
    fun launchReporting_attaches_the_shared_handler_to_the_launch() {
        // The shared handler reports through CrashReporter, which is not started in a
        // test, so what this can prove is the part that matters for a view model:
        // the failure does not propagate out of the scope.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

        scope.launchReporting { throw IllegalStateException("boom") }
    }

    @Test
    fun a_failed_result_is_reported_and_handed_back_for_the_caller_to_show() {
        val result = Result.failure<Unit>(IllegalStateException("show me"))

        val returned = result.reportFailure()

        assertTrue(returned.isFailure)
    }

    @Test
    fun a_cancelled_result_is_rethrown_rather_than_swallowed() {
        val cancelled = Result.failure<Unit>(CancellationException("navigated away"))

        var thrown: Throwable? = null
        try {
            cancelled.reportFailure()
        } catch (e: CancellationException) {
            thrown = e
        }

        assertTrue(thrown is CancellationException)
    }
}
