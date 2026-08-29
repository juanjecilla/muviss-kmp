package com.codingpit.muviss.feature.profile.domain

import com.codingpit.muviss.feature.collection.api.CollectionSummary
import com.codingpit.muviss.models.MediaId
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

    /**
     * A genre must have at least this many *rated* titles in the library to
     * be eligible for [ProfileStats.topRatedGenre] (EPIC 15) — guards
     * against a single 10/10 (or 1/10) outlier in an otherwise-unrated genre
     * winning "top genre" off one data point.
     */
    const val MIN_RATED_TITLES_PER_GENRE = 2

    /** How many titles the profile's most-rewatched card shows before handing off to the full ranking. */
    const val MOST_REWATCHED_ON_CARD = 3

    fun calculate(
        summaries: List<CollectionSummary>,
        activityEpochDays: Set<Long>,
        todayEpochDay: Long,
        rewatchCounts: Map<MediaId, Int> = emptyMap(),
    ): ProfileStats = ProfileStats(
        statusBreakdown = statusBreakdown(summaries),
        moviesWatched = moviesWatched(summaries),
        episodesSeen = episodesSeen(summaries),
        estimatedMinutesWatched = estimatedMinutesWatched(summaries),
        genreBreakdown = genreBreakdown(summaries),
        streak = WatchStreakCalculator.calculate(activityEpochDays, todayEpochDay),
        averageRating = averageRating(summaries),
        ratedCount = ratedCount(summaries),
        topRatedGenre = topRatedGenre(summaries),
        mostRewatched = RewatchRankingCalculator.calculate(rewatchCounts, summaries).top(MOST_REWATCHED_ON_CARD),
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

    /** Mean of every saved title's personal rating (EPIC 15); null if nothing is rated yet. */
    private fun averageRating(summaries: List<CollectionSummary>): Double? = summaries.mapNotNull { it.rating }.averageOrNull()

    private fun ratedCount(summaries: List<CollectionSummary>) = summaries.count { it.rating != null }

    /**
     * The genre with the highest average personal rating, considering only
     * genres with at least [MIN_RATED_TITLES_PER_GENRE] rated titles. A
     * title contributes its rating to every genre it carries (same
     * multi-counting [genreBreakdown] uses); ties break on whichever genre
     * [maxByOrNull] encounters first, which is fine since this is a single
     * "top pick" display, not a ranked list.
     */
    private fun topRatedGenre(summaries: List<CollectionSummary>): GenreRating? = summaries
        .filter { it.rating != null }
        .flatMap { summary -> summary.genres.map { genre -> genre to summary.rating!! } }
        .groupBy({ it.first }, { it.second })
        .filterValues { ratings -> ratings.size >= MIN_RATED_TITLES_PER_GENRE }
        .map { (genre, ratings) -> GenreRating(genre, ratings.average(), ratings.size) }
        .maxByOrNull { it.averageRating }
}

private fun List<Int>.averageOrNull(): Double? = if (isEmpty()) null else average()
