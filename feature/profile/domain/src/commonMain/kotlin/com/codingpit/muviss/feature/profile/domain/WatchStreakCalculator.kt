package com.codingpit.muviss.feature.profile.domain

/**
 * Derives [WatchStreak] from a set of active epoch-days. Pure and total, like
 * [com.codingpit.muviss.core.model.WatchStatusCalculator] — the same input
 * always yields the same streak.
 */
object WatchStreakCalculator {

    /** One day's grace: a streak survives through "yesterday" with no activity yet today. */
    private const val GRACE_DAYS = 1L

    fun calculate(activityEpochDays: Set<Long>, todayEpochDay: Long): WatchStreak {
        if (activityEpochDays.isEmpty()) return WatchStreak(currentDays = 0, longestDays = 0)
        return WatchStreak(
            currentDays = currentRun(activityEpochDays, todayEpochDay),
            longestDays = longestRun(activityEpochDays),
        )
    }

    private fun longestRun(days: Set<Long>): Int {
        val sorted = days.distinct().sorted()
        var best = 1
        var run = 1
        for (i in 1 until sorted.size) {
            run = if (sorted[i] == sorted[i - 1] + 1) run + 1 else 1
            if (run > best) best = run
        }
        return best
    }

    /** Walks backward from "today" (or "yesterday", within [GRACE_DAYS]) counting consecutive active days. */
    private fun currentRun(days: Set<Long>, today: Long): Int {
        val mostRecent = days.max()
        val anchor = when {
            mostRecent == today -> today
            mostRecent >= today - GRACE_DAYS -> mostRecent
            else -> return 0
        }
        var count = 0
        var day = anchor
        while (days.contains(day)) {
            count++
            day--
        }
        return count
    }
}
