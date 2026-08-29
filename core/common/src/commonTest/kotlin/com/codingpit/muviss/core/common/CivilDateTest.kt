package com.codingpit.muviss.core.common

import kotlin.test.Test
import kotlin.test.assertEquals

class CivilDateTest {

    @Test
    fun the_unix_epoch_is_day_zero() {
        assertEquals(CivilDate(1970, 1, 1), civilDateOf(0L))
    }

    @Test
    fun it_handles_a_leap_day() {
        // 2024-02-29 is epoch day 19782.
        assertEquals(CivilDate(2024, 2, 29), civilDateOf(19_782L))
    }

    @Test
    fun it_handles_dates_before_the_epoch() {
        assertEquals(CivilDate(1969, 12, 31), civilDateOf(-1L))
    }

    @Test
    fun it_formats_a_date_readably() {
        assertEquals("29 Feb 2024", formatEpochDay(19_782L))
        assertEquals("1 Jan 1970", formatEpochDay(0L))
    }

    @Test
    fun december_maps_to_the_last_month_name() {
        // 2023-12-25 is epoch day 19716.
        assertEquals("25 Dec 2023", formatEpochDay(19_716L))
    }

    @Test
    fun a_civil_date_converts_back_to_its_epoch_day() {
        assertEquals(0L, epochDayOfCivil(1970, 1, 1))
        assertEquals(19_782L, epochDayOfCivil(2024, 2, 29))
        assertEquals(19_716L, epochDayOfCivil(2023, 12, 25))
        assertEquals(20_454L, epochDayOfCivil(2026, 1, 1))
    }

    /** The two directions have to agree, including across leap years and the March-based month shift. */
    @Test
    fun the_two_conversions_round_trip() {
        for (epochDay in 15_000L..21_000L) {
            val date = civilDateOf(epochDay)
            assertEquals(epochDay, epochDayOfCivil(date.year, date.month, date.day))
        }
    }

    @Test
    fun a_pre_epoch_date_round_trips_too() {
        for (epochDay in -1_000L..0L) {
            val date = civilDateOf(epochDay)
            assertEquals(epochDay, epochDayOfCivil(date.year, date.month, date.day))
        }
    }

    @Test
    fun an_epoch_day_widens_to_its_first_millisecond() {
        assertEquals(0L, epochMsAtStartOfDay(0L))
        assertEquals(86_400_000L, epochMsAtStartOfDay(1L))
        assertEquals(1L, epochDayOf(epochMsAtStartOfDay(1L)))
    }
}
