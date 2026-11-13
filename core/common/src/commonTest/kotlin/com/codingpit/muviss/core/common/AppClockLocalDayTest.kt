package com.codingpit.muviss.core.common

import kotlin.test.Test
import kotlin.test.assertEquals

class AppClockLocalDayTest {

    private val nov1 = epochMsAtStartOfDay(epochDayOfCivil(2026, 11, 1))

    private fun zone(utcOffsetMs: Long) = object : AppClock {
        override fun nowEpochMs(): Long = nov1 + 22 * HOUR + 30 * MINUTE
        override fun utcOffsetMsAt(epochMs: Long): Long = utcOffsetMs
    }

    @Test
    fun today_is_the_zone_day_when_it_differs_from_the_utc_day() {
        // Given UTC+2 and 22:30 UTC on 1 Nov, which is already 00:30 on 2 Nov locally
        val clock = zone(2 * HOUR)
        // Then today is 2 Nov for the person, on every aired, due and streak check (#675)
        assertEquals(epochDayOfCivil(2026, 11, 2), clock.todayEpochDay())
        assertEquals(clock.localTodayEpochDay(), clock.todayEpochDay())
        // The UTC day is still 1 Nov, and nothing reads it as "today" any more.
        assertEquals(epochDayOfCivil(2026, 11, 1), epochDayOf(clock.nowEpochMs()))
    }

    @Test
    fun a_day_whose_midnight_is_skipped_starts_at_the_jump_not_on_the_previous_day() {
        // America/Asuncion, 1 Oct 2023: the clocks jump from 00:00 (UTC-4) to 01:00 (UTC-3), so local 00:00 never happens.
        val jump = epochMsAtStartOfDay(epochDayOfCivil(2023, 10, 1)) + 4 * HOUR
        val clock = object : AppClock {
            override fun nowEpochMs(): Long = jump
            override fun utcOffsetMsAt(epochMs: Long): Long = if (epochMs >= jump) -3 * HOUR else -4 * HOUR
        }
        val october1 = epochDayOfCivil(2023, 10, 1)

        // The day starts at the jump, the first instant that is on 1 Oct locally, not 03:00 UTC on 30 Sep.
        assertEquals(jump, clock.localStartOfDayEpochMs(october1))
        assertEquals(october1, clock.localEpochDayOf(clock.localStartOfDayEpochMs(october1)))
        // The day after is unaffected: its midnight is 03:00 UTC at the new offset.
        assertEquals(epochMsAtStartOfDay(october1 + 1) + 3 * HOUR, clock.localStartOfDayEpochMs(october1 + 1))
    }

    @Test
    fun a_repeated_midnight_starts_its_day_at_the_first_of_the_two() {
        // Asia/Gaza, 1 Oct 2004: UTC+3 becomes UTC+2 at 22:00 UTC, which shows 00:00 twice. The first is 21:00 UTC on 30 Sep.
        val switch = epochMsAtStartOfDay(epochDayOfCivil(2004, 9, 30)) + 22 * HOUR
        val clock = object : AppClock {
            override fun nowEpochMs(): Long = switch
            override fun utcOffsetMsAt(epochMs: Long): Long = if (epochMs >= switch) 2 * HOUR else 3 * HOUR
        }
        val october1 = epochDayOfCivil(2004, 10, 1)
        val firstMidnight = epochMsAtStartOfDay(epochDayOfCivil(2004, 9, 30)) + 21 * HOUR

        // Not the second reading at 22:00 UTC, which is the midnight an offset refinement picks.
        assertEquals(firstMidnight, clock.localStartOfDayEpochMs(october1))
        assertEquals(october1, clock.localEpochDayOf(firstMidnight))
        assertEquals(epochDayOfCivil(2004, 9, 30), clock.localEpochDayOf(firstMidnight - 1))
        // The day after has one midnight, at UTC+2.
        assertEquals(epochMsAtStartOfDay(october1 + 1) - 2 * HOUR, clock.localStartOfDayEpochMs(october1 + 1))
    }

    @Test
    fun a_whole_day_the_clocks_skip_starts_at_the_jump_into_the_next_day() {
        // Pacific/Apia, 30 Dec 2011 never happens locally: UTC-10 becomes UTC+14 at 10:00 UTC on 30 Dec, so 29 Dec is followed by 31 Dec.
        val jump = epochMsAtStartOfDay(epochDayOfCivil(2011, 12, 30)) + 10 * HOUR
        val clock = object : AppClock {
            override fun nowEpochMs(): Long = jump
            override fun utcOffsetMsAt(epochMs: Long): Long = if (epochMs >= jump) 14 * HOUR else -10 * HOUR
        }

        // No instant is on 30 Dec, so the first instant at or after its start is the jump. It is not 30 Dec's UTC midnight.
        assertEquals(jump, clock.localStartOfDayEpochMs(epochDayOfCivil(2011, 12, 30)))
        assertEquals(epochDayOfCivil(2011, 12, 31), clock.localEpochDayOf(jump))
    }

    @Test
    fun local_day_before_the_epoch_floors_toward_the_earlier_day() {
        // Given UTC-5 and 02:00 UTC on 1 Jan 1970, which is 21:00 on 31 Dec 1969 locally
        val clock = zone(-5 * HOUR)
        // Then floorDiv keeps it on 31 Dec (-1) rather than truncating toward zero to day 0
        assertEquals(-1L, clock.localEpochDayOf(2 * HOUR))
    }

    @Test
    fun the_default_offset_is_utc_so_a_fake_never_reads_the_host_zone() {
        val clock = object : AppClock {
            override fun nowEpochMs(): Long = nov1 + 22 * HOUR
        }
        assertEquals(0L, clock.utcOffsetMsAt(nov1))
        assertEquals(clock.todayEpochDay(), clock.localTodayEpochDay())
    }

    /** Berlin-like: +1 until the March change, +2 from then on. The change is on 29 Mar 2026 at 01:00 UTC. */
    private val dstChangeMs = epochMsAtStartOfDay(epochDayOfCivil(2026, 3, 29)) + HOUR
    private val dstZone = object : AppClock {
        override fun nowEpochMs(): Long = epochMsAtStartOfDay(epochDayOfCivil(2026, 11, 9)) + 12 * HOUR
        override fun utcOffsetMsAt(epochMs: Long): Long = if (epochMs < dstChangeMs) HOUR else 2 * HOUR
    }

    @Test
    fun a_local_midnight_on_the_far_side_of_a_dst_change_uses_the_offset_at_that_midnight() {
        // Given the summer day that starts after the change, when its local midnight is read
        val midnight = dstZone.localStartOfDayEpochMs(epochDayOfCivil(2026, 7, 1))
        // Then it is 00:00 at +2, which is 22:00 UTC on 30 June: the offset is read at that instant
        assertEquals(epochMsAtStartOfDay(epochDayOfCivil(2026, 6, 30)) + 22 * HOUR, midnight)
        assertEquals(epochDayOfCivil(2026, 7, 1), dstZone.localEpochDayOf(midnight))
    }

    @Test
    fun a_local_midnight_before_the_change_is_the_winter_offset_and_is_its_own_day() {
        val midnight = dstZone.localStartOfDayEpochMs(epochDayOfCivil(2026, 1, 1))
        assertEquals(epochMsAtStartOfDay(epochDayOfCivil(2025, 12, 31)) + 23 * HOUR, midnight)
        assertEquals(epochDayOfCivil(2026, 1, 1), dstZone.localEpochDayOf(midnight))
        assertEquals(epochDayOfCivil(2025, 12, 31), dstZone.localEpochDayOf(midnight - 1))
    }

    private companion object {
        const val HOUR = 3_600_000L
        const val MINUTE = 60_000L
        const val DAY_MS = 86_400_000L
    }
}
