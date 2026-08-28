package com.codingpit.muviss.feature.search.ui

import com.codingpit.muviss.models.Episode
import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.Season
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Which seasons a TV detail screen opens with, and which fold themselves away
 * afterwards. A nine-season show rendered every episode of every season in one
 * unbroken list before this.
 */
class SeasonExpansionTest {

    private val show = MediaId.tmdbTv("1399")

    private fun episode(season: Int, number: Int, airDay: Long? = 10L) = Episode(EpisodeId(show, season, number), season, number, "S${season}E$number", airDateEpochDay = airDay)

    private fun season(number: Int, count: Int, airDay: Long? = 10L) = Season(number, "Season $number", (1..count).map { episode(number, it, airDay) })

    @Test
    fun the_season_holding_the_next_unseen_episode_opens() {
        val seasons = listOf(season(1, 2), season(2, 2), season(3, 2))
        val seen = setOf(EpisodeId(show, 1, 1), EpisodeId(show, 1, 2), EpisodeId(show, 2, 1))

        assertEquals(1, SeasonExpansion.initiallyExpandedIndex(seasons, seen, todayEpochDay = 100L))
    }

    @Test
    fun a_show_with_nothing_watched_opens_at_its_first_season() {
        val seasons = listOf(season(1, 2), season(2, 2))

        assertEquals(0, SeasonExpansion.initiallyExpandedIndex(seasons, emptySet(), todayEpochDay = 100L))
    }

    @Test
    fun a_fully_watched_show_opens_with_everything_collapsed() {
        val seasons = listOf(season(1, 2), season(2, 2))
        val seen = seasons.flatMap { it.episodes }.map { it.id }.toSet()

        assertNull(
            SeasonExpansion.initiallyExpandedIndex(seasons, seen, todayEpochDay = 100L),
            "there is nothing to tick, so nothing needs to be open",
        )
    }

    @Test
    fun an_unaired_season_is_not_what_opens() {
        val seasons = listOf(season(1, 2), season(2, 2, airDay = 9_999L))
        val seen = setOf(EpisodeId(show, 1, 1), EpisodeId(show, 1, 2))

        assertNull(
            SeasonExpansion.initiallyExpandedIndex(seasons, seen, todayEpochDay = 100L),
            "you are caught up; a season that hasn't started is not where you left off",
        )
    }

    @Test
    fun marking_a_finished_season_folds_it_away() {
        assertTrue(SeasonExpansion.collapsesAfterMarking(seasonIndex = 0, seasonCount = 3))
        assertTrue(SeasonExpansion.collapsesAfterMarking(seasonIndex = 1, seasonCount = 3))
    }

    @Test
    fun the_last_season_stays_open_because_it_is_the_one_still_airing() {
        assertFalse(
            SeasonExpansion.collapsesAfterMarking(seasonIndex = 2, seasonCount = 3),
            "the newest season is where the next episode lands, so folding it away hides what you came for",
        )
    }

    @Test
    fun a_single_season_show_never_folds_itself_away() {
        assertFalse(SeasonExpansion.collapsesAfterMarking(seasonIndex = 0, seasonCount = 1))
    }
}
