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
}
