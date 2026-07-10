package com.codingpit.muviss.feature.profile.domain

import com.codingpit.muviss.feature.collection.api.CollectionSummary
import com.codingpit.muviss.models.MediaType
import com.codingpit.muviss.models.WatchStatus

/**
 * Derives [ProfileStats] from the saved library ([CollectionSummary], read
 * through collection's `:api` per ADR 0004) and the current watch-activity
 * calendar. Pure and total — safe to unit-test without a database (see this
 * module's `ProfileStatsCalculatorTest`) — and exercised again against a real
 * SQLDelight-backed collection/progress pair by `feature/profile/data`'s
 * `ProfileStatsAggregationTest`.
 */
object ProfileStatsCalculator {
    /** Applied when a title carries no TMDB runtime (see `CollectionEntry.runtimeMinutes`'s KDoc for when that happens). */
    const val DEFAULT_MOVIE_RUNTIME_MINUTES = 120
    const val DEFAULT_TV_EPISODE_RUNTIME_MINUTES = 45

    fun calculate(
        summaries: List<CollectionSummary>,
        activityEpochDays: Set<Long>,
        todayEpochDay: Long,
    ): ProfileStats = ProfileStats(
        statusBreakdown = statusBreakdown(summaries),
        moviesWatched = moviesWatched(summaries),
        episodesSeen = episodesSeen(summaries),
        estimatedMinutesWatched = estimatedMinutesWatched(summaries),
        genreBreakdown = genreBreakdown(summaries),
        streak = WatchStreakCalculator.calculate(activityEpochDays, todayEpochDay),
    )

    private fun statusBreakdown(summaries: List<CollectionSummary>) = StatusBreakdown(
        notStarted = summaries.count { it.status == WatchStatus.NOT_STARTED },
        watching = summaries.count { it.status == WatchStatus.WATCHING },
        watched = summaries.count { it.status == WatchStatus.WATCHED },
        finished = summaries.count { it.status == WatchStatus.FINISHED },
    )

    private fun moviesWatched(summaries: List<CollectionSummary>) = summaries
        .count { it.mediaId.type == MediaType.MOVIE && it.status == WatchStatus.WATCHED }

    /** TV episodes only — a movie's synthetic single tick (see `EpisodeId.forMovie`) isn't an "episode". */
    private fun episodesSeen(summaries: List<CollectionSummary>) = summaries
        .filter { it.mediaId.type == MediaType.TV }
        .sumOf { it.seenEpisodes }

    /** Real runtimes when [CollectionSummary.runtimeMinutes] is known, the fixed defaults otherwise. */
    private fun estimatedMinutesWatched(summaries: List<CollectionSummary>): Long = summaries.sumOf { summary ->
        when (summary.mediaId.type) {
            MediaType.MOVIE -> if (summary.status == WatchStatus.WATCHED) {
                (summary.runtimeMinutes ?: DEFAULT_MOVIE_RUNTIME_MINUTES).toLong()
            } else {
                0L
            }

            MediaType.TV -> summary.seenEpisodes * (summary.runtimeMinutes ?: DEFAULT_TV_EPISODE_RUNTIME_MINUTES).toLong()
        }
    }

    /**
     * Counts every saved title once per genre it carries, regardless of watch
     * status — a read of the whole library's taste, not only what has been
     * finished — sorted most-common genre first.
     */
    private fun genreBreakdown(summaries: List<CollectionSummary>): List<GenreCount> = summaries
        .flatMap { it.genres }
        .groupingBy { it }
        .eachCount()
        .map { (genre, count) -> GenreCount(genre, count) }
        .sortedByDescending { it.count }
}
