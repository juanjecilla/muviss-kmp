package com.codingpit.muviss.feature.profile.domain

import com.codingpit.muviss.core.common.epochDayOfCivil
import com.codingpit.muviss.core.common.epochMsAtStartOfDay
import kotlin.test.Test
import kotlin.test.assertEquals

class MonthlyRewatchCalculatorTest {

    private fun day(year: Int, month: Int, day: Int) = epochDayOfCivil(year, month, day)

    private fun at(year: Int, month: Int, day: Int) = epochMsAtStartOfDay(day(year, month, day))

    private val today = day(2026, 8, 29)

    @Test
    fun always_emits_twelve_months_oldest_first_ending_with_this_one() {
        val months = MonthlyRewatchCalculator.calculate(emptyList(), today)

        assertEquals(MonthlyRewatchCalculator.MONTHS_SHOWN, months.size)
        assertEquals(2025 to 9, months.first().year to months.first().month)
        assertEquals(2026 to 8, months.last().year to months.last().month)
    }

    /** A quiet month has to read as a gap in the chart, which means it has to exist as a zero. */
    @Test
    fun months_with_nothing_in_them_are_zeros_not_omissions() {
        val months = MonthlyRewatchCalculator.calculate(listOf(at(2026, 3, 2)), today)

        assertEquals(1, months.single { it.year == 2026 && it.month == 3 }.rewatches)
        assertEquals(0, months.single { it.year == 2026 && it.month == 4 }.rewatches)
    }

    @Test
    fun counts_every_rewatch_in_a_month() {
        val months = MonthlyRewatchCalculator.calculate(
            listOf(at(2026, 3, 2), at(2026, 3, 9), at(2026, 3, 31)),
            today,
        )

        assertEquals(3, months.single { it.year == 2026 && it.month == 3 }.rewatches)
    }

    /** The window spans a year boundary, so the arithmetic has to wrap without special-casing December. */
    @Test
    fun the_window_reaches_back_across_the_new_year() {
        val months = MonthlyRewatchCalculator.calculate(listOf(at(2025, 12, 31)), today)

        assertEquals(1, months.single { it.year == 2025 && it.month == 12 }.rewatches)
    }

    @Test
    fun a_rewatch_older_than_the_window_is_not_counted_anywhere() {
        val months = MonthlyRewatchCalculator.calculate(listOf(at(2024, 1, 1)), today)

        assertEquals(0, months.sumOf { it.rewatches })
    }

    /** The last instant of a month belongs to that month, not the next one. */
    @Test
    fun a_rewatch_on_the_last_day_of_a_month_stays_in_it() {
        val months = MonthlyRewatchCalculator.calculate(
            listOf(epochMsAtStartOfDay(day(2026, 1, 31)) + MILLIS_PER_DAY - 1),
            today,
        )

        assertEquals(1, months.single { it.year == 2026 && it.month == 1 }.rewatches)
    }

    @Test
    fun the_query_bound_covers_exactly_the_oldest_month_shown() {
        val since = MonthlyRewatchCalculator.sinceEpochMs(today)

        assertEquals(at(2025, 9, 1), since)
    }

    private companion object {
        const val MILLIS_PER_DAY = 86_400_000L
    }
}
