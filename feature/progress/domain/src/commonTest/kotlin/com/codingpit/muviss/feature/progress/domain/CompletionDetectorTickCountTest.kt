package com.codingpit.muviss.feature.progress.domain

import com.codingpit.muviss.core.common.review.ReviewLedger
import com.codingpit.muviss.core.common.review.ReviewRecord
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.WatchStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The #410 tick counter lives in [CompletionDetector.around]: it counts hand ticks that mark something seen, and nothing else. */
class CompletionDetectorTickCountTest {

    private val movie = MediaId.tmdbMovie("2")

    private class CountingLedger(var ticks: Int = 0, var failure: Throwable? = null) : ReviewLedger {
        override suspend fun read() = ReviewRecord(null, ticks, null, null)
        override suspend fun stampInstalledIfUnknown(nowEpochMs: Long) = Unit
        override suspend fun countTick() {
            failure?.let { throw it }
            ticks++
        }
        override suspend fun recordAsk(nowEpochMs: Long, version: String) = Unit
    }

    private val status = TitleStatusSource { WatchStatus.NOT_STARTED }

    @Test
    fun a_tick_that_marks_something_seen_is_counted() = runTest {
        val ledger = CountingLedger()
        CompletionDetector(status, ledger).around(movie, seen = true) {}
        assertEquals(1, ledger.ticks)
    }

    @Test
    fun an_untick_is_not_counted() = runTest {
        val ledger = CountingLedger()
        CompletionDetector(status, ledger).around(movie, seen = false) {}
        assertEquals(0, ledger.ticks)
    }

    @Test
    fun a_counting_failure_does_not_fail_the_tick_and_the_write_still_happens() = runTest {
        val ledger = CountingLedger(failure = IllegalStateException("disk full"))
        var wrote = false
        CompletionDetector(status, ledger).around(movie, seen = true) { wrote = true }
        assertTrue(wrote)
    }

    @Test
    fun cancellation_while_counting_still_propagates() = runTest {
        val ledger = CountingLedger(failure = CancellationException("stop"))
        assertFailsWith<CancellationException> {
            CompletionDetector(status, ledger).around(movie, seen = true) {}
        }
    }
}
