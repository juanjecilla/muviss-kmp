package com.codingpit.muviss.feature.profile.domain

import com.codingpit.muviss.core.common.epochDayOfCivil
import com.codingpit.muviss.core.common.epochMsAtStartOfDay
import kotlin.test.Test
import kotlin.test.assertEquals

class RewatchWindowTest {

    private val someMomentIn2026 = epochMsAtStartOfDay(epochDayOfCivil(2026, 8, 29)) + 12 * 3_600_000L

    @Test
    fun all_time_bounds_nothing() {
        assertEquals(0L, RewatchWindow.ALL_TIME.sinceEpochMs(someMomentIn2026))
    }

    @Test
    fun this_year_starts_at_the_first_instant_of_January() {
        assertEquals(
            epochMsAtStartOfDay(epochDayOfCivil(2026, 1, 1)),
            RewatchWindow.THIS_YEAR.sinceEpochMs(someMomentIn2026),
        )
    }

    /** On New Year's Day the bound is that same day, not the year before. */
    @Test
    fun the_first_of_January_bounds_at_itself() {
        val newYear = epochMsAtStartOfDay(epochDayOfCivil(2026, 1, 1))

        assertEquals(newYear, RewatchWindow.THIS_YEAR.sinceEpochMs(newYear))
    }
}
