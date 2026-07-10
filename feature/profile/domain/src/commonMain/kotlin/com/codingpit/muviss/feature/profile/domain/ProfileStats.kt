package com.codingpit.muviss.feature.profile.domain

/** Saved-title counts by derived [com.codingpit.muviss.models.WatchStatus] (ADR 0005 — never stored, always this shape). */
data class StatusBreakdown(
    val notStarted: Int = 0,
    val watching: Int = 0,
    val watched: Int = 0,
    val finished: Int = 0,
) {
    val total: Int get() = notStarted + watching + watched + finished
}

/** One genre's title count, part of [ProfileStats.genreBreakdown] (already sorted, most-watched first). */
data class GenreCount(
    val genre: String,
    val count: Int,
)

/**
 * Consecutive-day watch activity, in UTC epoch-days (see
 * [com.codingpit.muviss.core.common.todayEpochDay]). A "day" counts if at
 * least one episode or movie is currently marked seen with that day's
 * `updatedAtEpochMs` (see [com.codingpit.muviss.feature.progress.api.ProgressApi.observeSeenActivityEpochDays]).
 *
 * [currentDays] counts backward from today with a one-day grace period: it
 * stays alive through "yesterday" so a streak isn't lost the moment a day
 * changes, only once a full day has passed with zero activity. It is 0 once
 * the most recent active day is two or more days in the past.
 *
 * [longestDays] is the longest run of consecutive days across all recorded
 * activity, independent of whether it is still ongoing.
 */
data class WatchStreak(
    val currentDays: Int = 0,
    val longestDays: Int = 0,
)

/**
 * Everything the profile screen's stats section renders, aggregated from
 * [com.codingpit.muviss.feature.collection.api.CollectionApi] and
 * [com.codingpit.muviss.feature.progress.api.ProgressApi] by
 * [ProfileStatsCalculator]. Never persisted itself — always recomputed from
 * the library + progress tables (ADR 0005's "derive, don't store" rule
 * extends to these aggregates too).
 */
data class ProfileStats(
    val statusBreakdown: StatusBreakdown = StatusBreakdown(),
    val moviesWatched: Int = 0,
    val episodesSeen: Int = 0,
    val estimatedMinutesWatched: Long = 0,
    val genreBreakdown: List<GenreCount> = emptyList(),
    val streak: WatchStreak = WatchStreak(),
) {
    /** True once the library has no saved titles at all — the screen's empty-state trigger. */
    val isEmpty: Boolean get() = statusBreakdown.total == 0

    val estimatedHoursWatched: Double get() = estimatedMinutesWatched / MINUTES_PER_HOUR

    private companion object {
        const val MINUTES_PER_HOUR = 60.0
    }
}
