package com.codingpit.muviss.feature.collection.domain

import com.codingpit.muviss.core.common.AppClock
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class ThrottleClock(var now: Long = 0L) : AppClock {
    override fun nowEpochMs(): Long = now
}

/**
 * The automatic refresh fires from `CollectionViewModel`'s `init`, so without
 * a throttle every fresh back-stack entry re-fetches the whole library.
 */
class CollectionRefreshThrottleTest {

    @Test
    fun the_first_claim_is_granted() {
        val throttle = CollectionRefreshThrottle(ThrottleClock())
        assertTrue(throttle.claimAutomaticRefresh())
    }

    @Test
    fun a_second_claim_inside_the_interval_is_refused() {
        val clock = ThrottleClock()
        val throttle = CollectionRefreshThrottle(clock)

        assertTrue(throttle.claimAutomaticRefresh())
        clock.now += 60_000L
        assertFalse(throttle.claimAutomaticRefresh())
    }

    @Test
    fun a_claim_after_the_interval_is_granted_again() {
        val clock = ThrottleClock()
        val throttle = CollectionRefreshThrottle(clock, minIntervalMs = 1_000L)

        assertTrue(throttle.claimAutomaticRefresh())
        clock.now += 1_000L
        assertTrue(throttle.claimAutomaticRefresh())
    }

    @Test
    fun recording_an_explicit_refresh_restarts_the_interval() {
        val clock = ThrottleClock()
        val throttle = CollectionRefreshThrottle(clock, minIntervalMs = 1_000L)

        clock.now = 500L
        throttle.recordRefresh()
        clock.now = 1_000L

        assertFalse(throttle.claimAutomaticRefresh(), "an explicit refresh should also hold off the automatic one")
    }
}
