package com.codingpit.muviss.feature.progress.domain

import com.codingpit.muviss.models.Episode
import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.Season
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val TODAY = 100L

class UpcomingEpisodesCalculatorTest {

    private fun episode(show: MediaId, season: Int, number: Int, airDateEpochDay: Long?) = Episode(
        id = EpisodeId(show, season, number),
        seasonNumber = season,
        episodeNumber = number,
        name = "S${season}E$number",
        airDateEpochDay = airDateEpochDay,
    )

    private fun show(id: String, title: String, vararg episodes: Episode) = UpcomingShow(
        mediaId = MediaId.tmdbTv(id),
        title = title,
        posterUrl = null,
        seasons = listOf(Season(1, "Season 1", episodes.toList())),
    )

    @Test
    fun episode_airing_today_lands_in_the_today_bucket() {
        val zorro = show("1", "Zorro", episode(MediaId.tmdbTv("1"), 1, 1, airDateEpochDay = TODAY))

        val groups = UpcomingEpisodesCalculator.group(listOf(zorro), TODAY)

        assertEquals(listOf(UpcomingBucket.TODAY), groups.map { it.bucket })
        assertEquals("Zorro", groups.single().episodes.single().title)
    }

    @Test
    fun episode_airing_tomorrow_lands_in_this_week() {
        val zorro = show("1", "Zorro", episode(MediaId.tmdbTv("1"), 1, 1, airDateEpochDay = TODAY + 1))

        val groups = UpcomingEpisodesCalculator.group(listOf(zorro), TODAY)

        assertEquals(listOf(UpcomingBucket.THIS_WEEK), groups.map { it.bucket })
    }

    @Test
    fun episode_airing_exactly_six_days_out_is_the_last_day_of_this_week() {
        val zorro = show("1", "Zorro", episode(MediaId.tmdbTv("1"), 1, 1, airDateEpochDay = TODAY + 6))

        val groups = UpcomingEpisodesCalculator.group(listOf(zorro), TODAY)

        assertEquals(listOf(UpcomingBucket.THIS_WEEK), groups.map { it.bucket })
    }

    @Test
    fun episode_airing_seven_days_out_lands_in_later() {
        val zorro = show("1", "Zorro", episode(MediaId.tmdbTv("1"), 1, 1, airDateEpochDay = TODAY + 7))

        val groups = UpcomingEpisodesCalculator.group(listOf(zorro), TODAY)

        assertEquals(listOf(UpcomingBucket.LATER), groups.map { it.bucket })
    }

    @Test
    fun episode_that_already_aired_yesterday_is_excluded() {
        val zorro = show("1", "Zorro", episode(MediaId.tmdbTv("1"), 1, 1, airDateEpochDay = TODAY - 1))

        val groups = UpcomingEpisodesCalculator.group(listOf(zorro), TODAY)

        assertTrue(groups.isEmpty())
    }

    @Test
    fun episode_with_a_null_air_date_is_excluded() {
        val zorro = show("1", "Zorro", episode(MediaId.tmdbTv("1"), 1, 1, airDateEpochDay = null))

        val groups = UpcomingEpisodesCalculator.group(listOf(zorro), TODAY)

        assertTrue(groups.isEmpty())
    }

    @Test
    fun ended_show_with_no_future_episodes_is_absent() {
        // A show whose catalog only has past episodes behaves exactly like one
        // with no episodes at all — there's nothing that inspects production
        // status directly; it's just an empty result of the air-date filter.
        val zorro = show("1", "Ended Show", episode(MediaId.tmdbTv("1"), 1, 1, airDateEpochDay = TODAY - 500))

        assertTrue(UpcomingEpisodesCalculator.group(listOf(zorro), TODAY).isEmpty())
    }

    @Test
    fun empty_library_produces_no_groups() {
        assertTrue(UpcomingEpisodesCalculator.group(emptyList(), TODAY).isEmpty())
    }

    @Test
    fun rows_within_a_bucket_sort_by_date_then_show_title() {
        val zorro = show("1", "Zorro", episode(MediaId.tmdbTv("1"), 1, 1, airDateEpochDay = TODAY + 2))
        val alpha = show("2", "Alpha", episode(MediaId.tmdbTv("2"), 1, 1, airDateEpochDay = TODAY + 2))
        val early = show("3", "Zzz Early", episode(MediaId.tmdbTv("3"), 1, 1, airDateEpochDay = TODAY + 1))

        val groups = UpcomingEpisodesCalculator.group(listOf(zorro, alpha, early), TODAY)

        val titlesInOrder = groups.single().episodes.map { it.title }
        // "Zzz Early" airs first (day +1), then the day-+2 pair ordered by title.
        assertEquals(listOf("Zzz Early", "Alpha", "Zorro"), titlesInOrder)
    }

    @Test
    fun buckets_with_no_episodes_are_omitted_rather_than_returned_empty() {
        val zorro = show("1", "Zorro", episode(MediaId.tmdbTv("1"), 1, 1, airDateEpochDay = TODAY))

        val groups = UpcomingEpisodesCalculator.group(listOf(zorro), TODAY)

        assertEquals(1, groups.size)
        assertTrue(groups.none { it.episodes.isEmpty() })
    }

    @Test
    fun multiple_future_episodes_of_the_same_show_all_appear() {
        val zorro = show(
            "1",
            "Zorro",
            episode(MediaId.tmdbTv("1"), 1, 1, airDateEpochDay = TODAY),
            episode(MediaId.tmdbTv("1"), 1, 2, airDateEpochDay = TODAY + 7),
        )

        val groups = UpcomingEpisodesCalculator.group(listOf(zorro), TODAY)

        assertEquals(2, groups.sumOf { it.episodes.size })
        assertEquals(setOf(UpcomingBucket.TODAY, UpcomingBucket.LATER), groups.map { it.bucket }.toSet())
    }
}
