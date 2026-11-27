package com.codingpit.muviss.feature.collection.data

import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.core.common.epochMsAtStartOfDay
import com.codingpit.muviss.core.common.localEpochDayOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The wake-up for the Library's day roll-over (#951). The offset must be the one in force at the midnight itself: a DST
 * switch between now and midnight moves that midnight. The old `utcOffsetMsAt(now)` woke an hour late after a forward
 * change and an hour early after a backward one, and when the clock jumped over midnight it woke before the day changed.
 *
 * Every case checks the day boundary on both sides of the result, so a wrong instant fails here rather than only in a
 * comparison against a formula.
 */
class NextLocalMidnightTest {

    private val hour = 3_600_000L
    private val minute = 60_000L
    private val today = 1_000L
    private val tomorrowMs = epochMsAtStartOfDay(today + 1)

    /** A clock whose offset is [offsetAt] for each instant. */
    private fun clockAt(offsetAt: (Long) -> Long) = object : AppClock {
        override fun nowEpochMs(): Long = 0L
        override fun utcOffsetMsAt(epochMs: Long): Long = offsetAt(epochMs)
    }

    /** Asserts [wake] is the first instant of [today] + 1 on [clock]'s zone, and is after [nowMs]. */
    private fun assertDayBoundary(clock: AppClock, nowMs: Long, wake: Long) {
        assertTrue(wake > nowMs, "the wake-up $wake is not after now $nowMs")
        assertEquals(today + 1, clock.localEpochDayOf(wake), "the wake-up is not on the next local day")
        assertEquals(today, clock.localEpochDayOf(wake - 1), "the day changed before the wake-up")
    }

    @Test
    fun a_constant_offset_wakes_at_local_midnight() {
        // +01:00 all day: local midnight is 23:00 UTC.
        val nowMs = tomorrowMs - 3 * hour
        val clock = clockAt { 1 * hour }

        val wake = nextLocalMidnightMs(clock, nowMs, today)

        assertEquals(tomorrowMs - 1 * hour, wake)
        assertDayBoundary(clock, nowMs, wake)
    }

    @Test
    fun a_forward_change_before_midnight_wakes_at_the_new_offsets_midnight() {
        // +01:00 until 17:00 UTC, then +02:00. The +01:00 midnight at 23:00 UTC is read on +02:00, so local midnight is
        // 22:00 UTC. The old code woke at 23:00 UTC, an hour late, with yesterday's day still shown.
        val switch = tomorrowMs - 7 * hour
        val nowMs = tomorrowMs - 8 * hour
        val clock = clockAt { if (it >= switch) 2 * hour else 1 * hour }

        val wake = nextLocalMidnightMs(clock, nowMs, today)

        assertEquals(tomorrowMs - 2 * hour, wake)
        assertDayBoundary(clock, nowMs, wake)
    }

    @Test
    fun a_backward_change_before_midnight_wakes_at_the_new_offsets_midnight() {
        // +02:00 until 21:30 UTC, then +01:00. The +02:00 midnight at 22:00 UTC is after the switch, so it is read on
        // +01:00 and local midnight is 23:00 UTC. The old code woke at 22:00 UTC, an hour early.
        val switch = tomorrowMs - 150 * minute
        val nowMs = tomorrowMs - 4 * hour
        val clock = clockAt { if (it >= switch) 1 * hour else 2 * hour }

        val wake = nextLocalMidnightMs(clock, nowMs, today)

        assertEquals(tomorrowMs - 1 * hour, wake)
        assertDayBoundary(clock, nowMs, wake)
    }

    @Test
    fun a_clock_that_jumps_over_midnight_wakes_at_the_jump_not_before_it() {
        // +01:00 until 23:00 UTC, then +02:00. The clock goes from 23:59 to 01:00, so there is no midnight: the new
        // day begins at the jump itself. Waking at the old midnight's guess would leave yesterday's day up for an hour
        // and then spin, because the same instant comes back with the same day.
        val switch = tomorrowMs - 1 * hour
        val nowMs = tomorrowMs - 4 * hour
        val clock = clockAt { if (it >= switch) 2 * hour else 1 * hour }

        val wake = nextLocalMidnightMs(clock, nowMs, today)

        assertEquals(switch, wake)
        assertDayBoundary(clock, nowMs, wake)
    }
}
