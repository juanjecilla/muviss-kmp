package com.codingpit.muviss.feature.triage.domain

import com.codingpit.muviss.models.Episode
import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.Season
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Triage never ticks a special (#938, ADR 0037): a special sorts before season 1, so without this the "I'm watching
 * this" tick would land on it, which is not "next".
 */
class AiredSpecialsTest {

    private val show = MediaId.tmdbTv("1399")
    private fun episode(season: Int, number: Int, airDay: Long?) = Episode(EpisodeId(show, season, number), season, number, "E", airDateEpochDay = airDay)

    private val withSpecials = MediaDetails(
        summary = MediaSummary(show, "Show"),
        seasons = listOf(
            Season(0, "Specials", listOf(episode(0, 1, airDay = 5))),
            Season(1, "Season 1", listOf(episode(1, 1, airDay = 10), episode(1, 2, airDay = 20))),
        ),
    )

    @Test
    fun the_first_aired_episode_is_regular_even_when_a_special_aired_first() {
        assertEquals(EpisodeId(show, 1, 1), withSpecials.firstAiredEpisode(todayEpochDay = 50)?.id)
    }

    @Test
    fun the_last_aired_episode_is_regular() {
        assertEquals(EpisodeId(show, 1, 2), withSpecials.lastAiredEpisode(todayEpochDay = 50)?.id)
    }

    @Test
    fun a_show_with_only_an_aired_special_has_no_first_episode_to_tick() {
        val onlySpecials = withSpecials.copy(seasons = listOf(Season(0, "Specials", listOf(episode(0, 1, airDay = 5)))))

        assertNull(onlySpecials.firstAiredEpisode(todayEpochDay = 50))
    }
}
