package com.codingpit.muviss.feature.collection.domain

import com.codingpit.muviss.core.testing.FakeClock
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The automatic refresh fires from `CollectionViewModel`'s `init`, so without
 * a throttle every fresh back-stack entry re-fetches the whole library.
 */
class CollectionRefreshThrottleTest {

    @Test
    fun the_first_claim_is_granted() {
        val throttle = CollectionRefreshThrottle(FakeClock(0L))
        assertTrue(throttle.claimAutomaticRefresh())
    }

    @Test
    fun a_second_claim_inside_the_interval_is_refused() {
        val clock = FakeClock(0L)
        val throttle = CollectionRefreshThrottle(clock)

        assertTrue(throttle.claimAutomaticRefresh())
        clock.epochMs += 60_000L
        assertFalse(throttle.claimAutomaticRefresh())
    }

    @Test
    fun a_claim_after_the_interval_is_granted_again() {
        val clock = FakeClock(0L)
        val throttle = CollectionRefreshThrottle(clock, minIntervalMs = 1_000L)

        assertTrue(throttle.claimAutomaticRefresh())
        clock.epochMs += 1_000L
        assertTrue(throttle.claimAutomaticRefresh())
    }

    @Test
    fun recording_an_explicit_refresh_restarts_the_interval() {
        val clock = FakeClock(0L)
        val throttle = CollectionRefreshThrottle(clock, minIntervalMs = 1_000L)

        clock.epochMs = 500L
        throttle.recordRefresh()
        clock.epochMs = 1_000L

        assertFalse(throttle.claimAutomaticRefresh(), "an explicit refresh should also hold off the automatic one")
    }
}
