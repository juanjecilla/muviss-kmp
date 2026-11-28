package com.codingpit.muviss.feature.progress.domain

import com.codingpit.muviss.feature.progress.api.EpisodeOrdering
import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.Season
import kotlin.test.Test
import kotlin.test.assertEquals

/** Specials sort before season 1, so the ordering must keep them out of a regular walk (#938, ADR 0037). */
class SpecialsOrderingTest {

    private val show = MediaId.tmdbTv("1399")
    private val undatedSpecial = testEpisode(show, 0, 1, airDay = null)
    private val seasons = listOf(
        Season(0, "Specials", listOf(undatedSpecial)),
        Season(1, "Season 1", listOf(testEpisode(show, 1, 1, 50), testEpisode(show, 1, 2, 60), testEpisode(show, 1, 3, 70))),
    )

    @Test
    fun marking_previous_for_a_regular_episode_never_ticks_a_special() {
        val ticked = EpisodeOrdering.upToInclusive(seasons, EpisodeId(show, 1, 2))

        assertEquals(listOf(EpisodeId(show, 1, 1), EpisodeId(show, 1, 2)), ticked)
    }

    @Test
    fun marking_previous_for_a_special_walks_only_the_specials() {
        val second = testEpisode(show, 0, 2, airDay = 40)
        val withTwo = listOf(Season(0, "Specials", listOf(undatedSpecial, second)), seasons[1])

        assertEquals(listOf(undatedSpecial.id, second.id), EpisodeOrdering.upToInclusive(withTwo, second.id))
    }

    @Test
    fun the_air_date_walk_never_includes_an_undated_special() {
        assertEquals(
            listOf(EpisodeId(show, 1, 1), EpisodeId(show, 1, 2)),
            EpisodeOrdering.airedBy(seasons, todayEpochDay = 65),
        )
    }
}
