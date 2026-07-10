package com.codingpit.muviss.feature.profile.domain

import com.codingpit.muviss.feature.collection.api.CollectionSummary
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.WatchStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class ProfileStatsCalculatorTest {

    private fun movie(
        id: String,
        status: WatchStatus,
        runtimeMinutes: Int? = null,
        genres: List<String> = emptyList(),
    ) = CollectionSummary(
        mediaId = MediaId.tmdbMovie(id),
        title = "Movie $id",
        posterUrl = null,
        status = status,
        genres = genres,
        runtimeMinutes = runtimeMinutes,
        seenEpisodes = if (status == WatchStatus.WATCHED) 1 else 0,
    )

    private fun show(
        id: String,
        status: WatchStatus,
        seenEpisodes: Int,
        runtimeMinutes: Int? = null,
        genres: List<String> = emptyList(),
    ) = CollectionSummary(
        mediaId = MediaId.tmdbTv(id),
        title = "Show $id",
        posterUrl = null,
        status = status,
        genres = genres,
        runtimeMinutes = runtimeMinutes,
        seenEpisodes = seenEpisodes,
    )

    @Test
    fun empty_library_yields_empty_stats() {
        val stats = ProfileStatsCalculator.calculate(emptyList(), emptySet(), todayEpochDay = 0)

        assertEquals(StatusBreakdown(), stats.statusBreakdown)
        assertEquals(true, stats.isEmpty)
        assertEquals(0, stats.moviesWatched)
        assertEquals(0, stats.episodesSeen)
        assertEquals(0L, stats.estimatedMinutesWatched)
        assertEquals(emptyList(), stats.genreBreakdown)
    }

    @Test
    fun status_breakdown_counts_each_bucket() {
        val summaries = listOf(
            movie("1", WatchStatus.NOT_STARTED),
            movie("2", WatchStatus.WATCHED),
            show("3", WatchStatus.WATCHING, seenEpisodes = 2),
            show("4", WatchStatus.WATCHED, seenEpisodes = 10),
            show("5", WatchStatus.FINISHED, seenEpisodes = 20),
        )

        val stats = ProfileStatsCalculator.calculate(summaries, emptySet(), todayEpochDay = 0)

        assertEquals(StatusBreakdown(notStarted = 1, watching = 1, watched = 2, finished = 1), stats.statusBreakdown)
        assertEquals(5, stats.statusBreakdown.total)
    }

    @Test
    fun moviesWatched_only_counts_watched_movies_not_shows_or_unwatched_movies() {
        val summaries = listOf(
            movie("1", WatchStatus.WATCHED),
            movie("2", WatchStatus.NOT_STARTED),
            show("3", WatchStatus.WATCHED, seenEpisodes = 5),
        )

        val stats = ProfileStatsCalculator.calculate(summaries, emptySet(), todayEpochDay = 0)

        assertEquals(1, stats.moviesWatched)
    }

    @Test
    fun episodesSeen_sums_tv_seenEpisodes_and_ignores_movies() {
        val summaries = listOf(
            show("1", WatchStatus.WATCHING, seenEpisodes = 3),
            show("2", WatchStatus.FINISHED, seenEpisodes = 12),
            movie("3", WatchStatus.WATCHED),
        )

        val stats = ProfileStatsCalculator.calculate(summaries, emptySet(), todayEpochDay = 0)

        assertEquals(15, stats.episodesSeen)
    }

    @Test
    fun hours_estimate_uses_real_runtime_when_known() {
        val summaries = listOf(
            movie("1", WatchStatus.WATCHED, runtimeMinutes = 136),
            show("2", WatchStatus.WATCHING, seenEpisodes = 4, runtimeMinutes = 30),
        )

        val stats = ProfileStatsCalculator.calculate(summaries, emptySet(), todayEpochDay = 0)

        assertEquals(136L + 4 * 30L, stats.estimatedMinutesWatched)
    }

    @Test
    fun hours_estimate_falls_back_to_defaults_when_runtime_is_unknown() {
        val summaries = listOf(
            movie("1", WatchStatus.WATCHED, runtimeMinutes = null),
            show("2", WatchStatus.WATCHING, seenEpisodes = 3, runtimeMinutes = null),
        )

        val stats = ProfileStatsCalculator.calculate(summaries, emptySet(), todayEpochDay = 0)

        val expected = ProfileStatsCalculator.DEFAULT_MOVIE_RUNTIME_MINUTES +
            3 * ProfileStatsCalculator.DEFAULT_TV_EPISODE_RUNTIME_MINUTES
        assertEquals(expected.toLong(), stats.estimatedMinutesWatched)
    }

    @Test
    fun hours_estimate_excludes_unwatched_movies() {
        val summaries = listOf(movie("1", WatchStatus.NOT_STARTED, runtimeMinutes = 120))

        val stats = ProfileStatsCalculator.calculate(summaries, emptySet(), todayEpochDay = 0)

        assertEquals(0L, stats.estimatedMinutesWatched)
    }

    @Test
    fun estimatedHoursWatched_converts_minutes_to_hours() {
        val summaries = listOf(movie("1", WatchStatus.WATCHED, runtimeMinutes = 90))

        val stats = ProfileStatsCalculator.calculate(summaries, emptySet(), todayEpochDay = 0)

        assertEquals(1.5, stats.estimatedHoursWatched)
    }

    @Test
    fun genre_breakdown_counts_titles_per_genre_regardless_of_status_sorted_descending() {
        val summaries = listOf(
            movie("1", WatchStatus.NOT_STARTED, genres = listOf("Action", "Sci-Fi")),
            show("2", WatchStatus.WATCHING, seenEpisodes = 1, genres = listOf("Action", "Drama")),
            movie("3", WatchStatus.WATCHED, genres = listOf("Action")),
        )

        val stats = ProfileStatsCalculator.calculate(summaries, emptySet(), todayEpochDay = 0)

        assertEquals(
            listOf(GenreCount("Action", 3), GenreCount("Drama", 1), GenreCount("Sci-Fi", 1)),
            stats.genreBreakdown.sortedWith(compareByDescending<GenreCount> { it.count }.thenBy { it.genre }),
        )
    }

    @Test
    fun streak_is_derived_from_activity_days_and_today() {
        val stats = ProfileStatsCalculator.calculate(emptyList(), setOf(8L, 9L, 10L), todayEpochDay = 10)

        assertEquals(WatchStreak(currentDays = 3, longestDays = 3), stats.streak)
    }
}
