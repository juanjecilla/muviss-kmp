package com.codingpit.muviss.feature.profile.domain

import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.core.common.epochDayOfCivil
import com.codingpit.muviss.core.common.epochMsAtStartOfDay
import kotlin.test.Test
import kotlin.test.assertEquals

class RewatchWindowTest {

    private val hour = 3_600_000L

    /** A person in a fixed zone, at [nowMs]. */
    private fun zone(utcOffsetMs: Long, nowMs: Long) = object : AppClock {
        override fun nowEpochMs(): Long = nowMs
        override fun utcOffsetMsAt(epochMs: Long): Long = utcOffsetMs
    }

    private fun utcAt(year: Int, month: Int, day: Int, atHour: Long = 12) = epochMsAtStartOfDay(epochDayOfCivil(year, month, day)) + atHour * hour

    private val someMomentIn2026 = utcAt(2026, 8, 29)

    @Test
    fun all_time_bounds_nothing() {
        assertEquals(0L, RewatchWindow.ALL_TIME.sinceEpochMs(zone(0L, someMomentIn2026)))
    }

    @Test
    fun this_year_starts_at_the_first_instant_of_January() {
        assertEquals(
            epochMsAtStartOfDay(epochDayOfCivil(2026, 1, 1)),
            RewatchWindow.THIS_YEAR.sinceEpochMs(zone(0L, someMomentIn2026)),
        )
    }

    /** On New Year's Day the bound is that same day, not the year before. */
    @Test
    fun the_first_of_January_bounds_at_itself() {
        val newYear = epochMsAtStartOfDay(epochDayOfCivil(2026, 1, 1))

        assertEquals(newYear, RewatchWindow.THIS_YEAR.sinceEpochMs(zone(0L, newYear)))
    }

    /**
     * 23:30 UTC on 31 Dec is already 01:30 on 1 Jan at UTC+2. The person's year is 2027, so the window is
     * 2027 from their midnight, and a rewatch made that night is not dropped by a 2026 bound (#756).
     */
    @Test
    fun the_year_is_the_local_year_when_the_zone_is_already_next_year() {
        val nowMs = utcAt(2026, 12, 31, atHour = 23) + 30 * 60_000L
        val localMidnight = utcAt(2026, 12, 31, atHour = 22)

        assertEquals(localMidnight, RewatchWindow.THIS_YEAR.sinceEpochMs(zone(2 * hour, nowMs)))
    }

    /** 02:00 UTC on 1 Jan is still 31 Dec at UTC-5, so the window is last year, from its own midnight. */
    @Test
    fun the_year_is_the_local_year_when_the_zone_is_still_last_year() {
        val nowMs = utcAt(2026, 1, 1, atHour = 2)
        val localMidnight = utcAt(2025, 1, 1, atHour = 5)

        assertEquals(localMidnight, RewatchWindow.THIS_YEAR.sinceEpochMs(zone(-5 * hour, nowMs)))
    }

    /**
     * Berlin-like: +1 in winter, +2 in summer. In July the bound is 1 January at the winter offset, 23:00 UTC on
     * 31 Dec. Reading the offset at "now" would put it an hour late.
     */
    @Test
    fun the_bound_reads_the_offset_on_the_first_of_january_not_the_offset_now() {
        val dstZone = object : AppClock {
            override fun nowEpochMs(): Long = utcAt(2026, 7, 15)
            override fun utcOffsetMsAt(epochMs: Long): Long = if (epochMs < utcAt(2026, 3, 29, atHour = 1)) hour else 2 * hour
        }

        assertEquals(utcAt(2025, 12, 31, atHour = 23), RewatchWindow.THIS_YEAR.sinceEpochMs(dstZone))
    }
}
