package com.codingpit.muviss.core.network

import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.models.MediaId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** #762: a title TMDB said is missing is passed over only for its TTL, then asked about again. */
class NotFoundTitlesTest {

    private class SteppedClock(var now: Long) : AppClock {
        override fun nowEpochMs(): Long = now
    }

    private val a = MediaId.tmdbMovie("1")

    @Test
    fun a_recorded_title_is_known_until_its_ttl_passes_then_it_is_asked_about_again() = runTest {
        val clock = SteppedClock(0L)
        val missing = NotFoundTitles(clock, ttlMs = 1_000L)

        missing.record(a)
        assertEquals(setOf(a), missing.known(setOf(a)))

        clock.now = 999L
        assertEquals(setOf(a), missing.known(setOf(a)), "still inside the TTL")

        clock.now = 1_000L
        assertEquals(emptySet(), missing.known(setOf(a)), "at the TTL it expires, so TMDB is asked again")
    }
}
