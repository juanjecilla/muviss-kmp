package com.codingpit.muviss.feature.progress.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class UpcomingDateLabelTest {

    // Epoch day 20,000 is 2024-10-04 (a Friday) — an arbitrary, verifiable anchor.
    private val today = 20_000L

    @Test
    fun same_day_is_today() {
        assertEquals("Today", upcomingDateLabel(today, today))
    }

    @Test
    fun next_day_is_tomorrow() {
        assertEquals("Tomorrow", upcomingDateLabel(today + 1, today))
    }

    @Test
    fun two_days_out_is_the_weekday_name() {
        // today (day 20,000) is a Friday, so day+2 is Sunday.
        assertEquals("Sunday", upcomingDateLabel(today + 2, today))
    }

    @Test
    fun six_days_out_is_still_a_weekday_name() {
        // The last day of the rolling week: day+6 is Thursday.
        assertEquals("Thursday", upcomingDateLabel(today + 6, today))
    }

    @Test
    fun seven_days_out_falls_back_to_a_calendar_date() {
        // day+7 is 2024-10-11.
        assertEquals("Oct 11", upcomingDateLabel(today + 7, today))
    }

    @Test
    fun epoch_day_zero_is_a_thursday() {
        // "Today" (day -3) puts day 0 three days out — inside the weekday-name branch.
        assertEquals("Thursday", upcomingDateLabel(airDateEpochDay = 0, todayEpochDay = -3))
    }

    @Test
    fun far_future_date_formats_as_month_and_day() {
        // Epoch day 20,123 is 2025-02-04.
        assertEquals("Feb 4", upcomingDateLabel(20_123, today))
    }

    @Test
    fun pre_1970_epoch_days_still_resolve_a_correct_civil_date() {
        // Epoch day -1 is 1969-12-31; forced past the weekday branch via a distant "today".
        assertEquals("Dec 31", upcomingDateLabel(-1, -1000))
    }
}
