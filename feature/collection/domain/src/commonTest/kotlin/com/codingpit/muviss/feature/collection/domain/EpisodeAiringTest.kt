package com.codingpit.muviss.feature.collection.domain

import com.codingpit.muviss.models.Episode
import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.Season
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private const val TODAY = 100L

class EpisodeAiringTest {

    private val show = MediaId.tmdbTv("1399")

    private fun episode(season: Int, number: Int, airDateEpochDay: Long?) = Episode(
        id = EpisodeId(show, season, number),
        seasonNumber = season,
        episodeNumber = number,
        name = "S${season}E$number",
        airDateEpochDay = airDateEpochDay,
    )

    private fun tvDetails(vararg episodesBySeason: Pair<Int, List<Episode>>) = MediaDetails(
        summary = MediaSummary(show, "Show"),
        seasons = episodesBySeason.map { (number, episodes) -> Season(number, "S$number", episodes) },
    )

    @Test
    fun movie_always_counts_as_a_single_aired_episode() {
        val movie = MediaDetails(MediaSummary(MediaId.tmdbMovie("603"), "The Matrix"))
        assertEquals(1, movie.totalEpisodeCount())
        assertEquals(1, movie.airedEpisodeCount(TODAY))
    }

    @Test
    fun movie_has_no_episode_label() {
        val movie = MediaDetails(MediaSummary(MediaId.tmdbMovie("603"), "The Matrix"))
        assertNull(movie.latestAiredEpisodeLabel(TODAY))
    }

    @Test
    fun tv_aired_count_only_includes_episodes_on_or_before_today() {
        val details = tvDetails(
            1 to listOf(
                episode(1, 1, airDateEpochDay = TODAY - 10),
                episode(1, 2, airDateEpochDay = TODAY),
                episode(1, 3, airDateEpochDay = TODAY + 10), // not aired yet
                episode(1, 4, airDateEpochDay = null), // unscheduled
            ),
        )
        assertEquals(4, details.totalEpisodeCount())
        assertEquals(2, details.airedEpisodeCount(TODAY))
    }

    @Test
    fun tv_label_is_null_when_nothing_has_aired_yet() {
        val details = tvDetails(1 to listOf(episode(1, 1, airDateEpochDay = TODAY + 1)))
        assertNull(details.latestAiredEpisodeLabel(TODAY))
    }

    @Test
    fun tv_label_is_the_highest_aired_season_and_episode_number() {
        val details = tvDetails(
            1 to listOf(episode(1, 1, TODAY - 20), episode(1, 2, TODAY - 10)),
            2 to listOf(episode(2, 1, TODAY - 5), episode(2, 2, TODAY + 100)), // not aired yet
        )
        assertEquals("S02E01", details.latestAiredEpisodeLabel(TODAY))
    }
}
