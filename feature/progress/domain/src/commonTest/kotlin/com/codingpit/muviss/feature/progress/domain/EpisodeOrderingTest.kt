package com.codingpit.muviss.feature.progress.domain

import com.codingpit.muviss.models.Episode
import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.Season
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EpisodeOrderingTest {

    private val show = MediaId.tmdbTv("1399")

    private fun episode(season: Int, number: Int, airDateEpochDay: Long?) = Episode(
        id = EpisodeId(show, season, number),
        seasonNumber = season,
        episodeNumber = number,
        name = "S${season}E$number",
        airDateEpochDay = airDateEpochDay,
    )

    private val seasons = listOf(
        Season(1, "Season 1", listOf(episode(1, 1, airDateEpochDay = 100), episode(1, 2, airDateEpochDay = 101))),
        Season(2, "Season 2", listOf(episode(2, 1, airDateEpochDay = 200), episode(2, 2, airDateEpochDay = 500))),
    )

    @Test
    fun flatten_orders_by_season_then_episode_number() {
        val flat = EpisodeOrdering.flatten(seasons)
        assertEquals(
            listOf(EpisodeId(show, 1, 1), EpisodeId(show, 1, 2), EpisodeId(show, 2, 1), EpisodeId(show, 2, 2)),
            flat.map { it.id },
        )
    }

    @Test
    fun nextUnseen_returns_first_aired_episode_not_in_seen() {
        val seen = setOf(EpisodeId(show, 1, 1))
        val next = EpisodeOrdering.nextUnseen(seasons, seen, todayEpochDay = 300)
        assertEquals(EpisodeId(show, 1, 2), next?.id)
    }

    @Test
    fun nextUnseen_skips_unaired_episodes() {
        // S02E02 airs at day 500; "today" is only 300, so it isn't a candidate.
        val seen = setOf(EpisodeId(show, 1, 1), EpisodeId(show, 1, 2), EpisodeId(show, 2, 1))
        val next = EpisodeOrdering.nextUnseen(seasons, seen, todayEpochDay = 300)
        assertNull(next)
    }

    @Test
    fun nextUnseen_is_null_when_fully_caught_up() {
        val seen = seasons.flatMap { it.episodes }.map { it.id }.toSet()
        assertNull(EpisodeOrdering.nextUnseen(seasons, seen, todayEpochDay = 1_000))
    }

    @Test
    fun upToInclusive_returns_every_episode_at_or_before_target() {
        val target = EpisodeId(show, 2, 1)
        val ids = EpisodeOrdering.upToInclusive(seasons, target)
        assertEquals(
            listOf(EpisodeId(show, 1, 1), EpisodeId(show, 1, 2), EpisodeId(show, 2, 1)),
            ids,
        )
    }

    @Test
    fun upToInclusive_first_episode_returns_only_itself() {
        val target = EpisodeId(show, 1, 1)
        assertEquals(listOf(target), EpisodeOrdering.upToInclusive(seasons, target))
    }

    @Test
    fun upToInclusive_unknown_target_falls_back_to_itself() {
        val unknown = EpisodeId(show, 99, 99)
        assertEquals(listOf(unknown), EpisodeOrdering.upToInclusive(seasons, unknown))
    }
}
