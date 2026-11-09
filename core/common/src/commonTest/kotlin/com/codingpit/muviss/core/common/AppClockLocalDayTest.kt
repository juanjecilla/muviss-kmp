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

    private companion object {
        const val HOUR = 3_600_000L
        const val MINUTE = 60_000L
        const val DAY_MS = 86_400_000L
    }
}
