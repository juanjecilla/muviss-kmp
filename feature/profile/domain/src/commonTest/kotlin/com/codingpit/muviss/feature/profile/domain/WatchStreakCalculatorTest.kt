package com.codingpit.muviss.feature.profile.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class WatchStreakCalculatorTest {

    @Test
    fun no_activity_is_zero_current_and_longest() {
        val streak = WatchStreakCalculator.calculate(emptySet(), todayEpochDay = 100)
        assertEquals(WatchStreak(0, 0), streak)
    }

    @Test
    fun activity_today_only_is_a_streak_of_one() {
        val streak = WatchStreakCalculator.calculate(setOf(100L), todayEpochDay = 100)
        assertEquals(WatchStreak(currentDays = 1, longestDays = 1), streak)
    }

    @Test
    fun consecutive_days_up_to_today_are_all_counted() {
        val streak = WatchStreakCalculator.calculate(setOf(97L, 98L, 99L, 100L), todayEpochDay = 100)
        assertEquals(WatchStreak(currentDays = 4, longestDays = 4), streak)
    }

    @Test
    fun one_day_grace_keeps_the_streak_alive_with_no_activity_yet_today() {
        // Most recent activity was yesterday; today hasn't happened yet.
        val streak = WatchStreakCalculator.calculate(setOf(97L, 98L, 99L), todayEpochDay = 100)
        assertEquals(WatchStreak(currentDays = 3, longestDays = 3), streak)
    }

    @Test
    fun a_gap_of_two_or_more_days_breaks_the_current_streak() {
        val streak = WatchStreakCalculator.calculate(setOf(90L, 91L, 92L), todayEpochDay = 100)
        assertEquals(WatchStreak(currentDays = 0, longestDays = 3), streak)
    }

    @Test
    fun longest_streak_survives_a_gap_even_when_current_streak_is_shorter() {
        // Long run early, gap, then a shorter run ending today.
        val streak = WatchStreakCalculator.calculate(setOf(1L, 2L, 3L, 4L, 5L, 50L, 99L, 100L), todayEpochDay = 100)
        assertEquals(WatchStreak(currentDays = 2, longestDays = 5), streak)
    }

    @Test
    fun a_single_isolated_activity_day_far_in_the_past_yields_zero_current_streak() {
        val streak = WatchStreakCalculator.calculate(setOf(10L), todayEpochDay = 100)
        assertEquals(WatchStreak(currentDays = 0, longestDays = 1), streak)
    }

    @Test
    fun duplicate_and_unordered_input_days_are_handled() {
        val streak = WatchStreakCalculator.calculate(setOf(100L, 98L, 99L, 99L, 97L), todayEpochDay = 100)
        assertEquals(WatchStreak(currentDays = 4, longestDays = 4), streak)
    }
}
