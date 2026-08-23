package com.codingpit.muviss.feature.triage.domain

import com.codingpit.muviss.models.EpisodeId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AiredEpisodesTest {

    private val showId = show().id

    @Test
    fun ongoing_show_mid_season_stops_at_the_last_aired_episode() {
        val details = tvDetails(
            seasons = listOf(
                1 to listOf(TODAY_EPOCH_DAY - 30, TODAY_EPOCH_DAY - 23),
                2 to listOf(TODAY_EPOCH_DAY - 2, TODAY_EPOCH_DAY + 5),
            ),
        )

        assertEquals(EpisodeId(showId, 1, 1), details.firstAiredEpisode(TODAY_EPOCH_DAY)?.id)
        assertEquals(EpisodeId(showId, 2, 1), details.lastAiredEpisode(TODAY_EPOCH_DAY)?.id)
        assertEquals(3, details.airedEpisodesInOrder(TODAY_EPOCH_DAY).size)
    }

    @Test
    fun a_fully_aired_ended_show_counts_every_episode() {
        val details = tvDetails(
            seasons = listOf(1 to listOf(TODAY_EPOCH_DAY - 90, TODAY_EPOCH_DAY - 83)),
            productionStatus = com.codingpit.muviss.models.ProductionStatus.ENDED,
        )

        assertEquals(2, details.airedEpisodesInOrder(TODAY_EPOCH_DAY).size)
        assertEquals(EpisodeId(showId, 1, 2), details.lastAiredEpisode(TODAY_EPOCH_DAY)?.id)
    }

    @Test
    fun an_undated_special_is_never_treated_as_aired() {
        // The case that would otherwise break status derivation: a season 0
        // special sorts first but has no air date, so counting it as seen
        // would push seenEpisodes past airedEpisodes.
        val details = tvDetails(
            seasons = listOf(
                0 to listOf(null),
                1 to listOf(TODAY_EPOCH_DAY - 10),
            ),
        )

        val aired = details.airedEpisodesInOrder(TODAY_EPOCH_DAY)
        assertEquals(listOf(EpisodeId(showId, 1, 1)), aired.map { it.id })
        assertEquals(EpisodeId(showId, 1, 1), details.firstAiredEpisode(TODAY_EPOCH_DAY)?.id)
    }

    @Test
    fun an_entirely_unaired_season_contributes_nothing() {
        val details = tvDetails(seasons = listOf(1 to listOf(TODAY_EPOCH_DAY + 1, TODAY_EPOCH_DAY + 8)))

        assertNull(details.firstAiredEpisode(TODAY_EPOCH_DAY))
        assertNull(details.lastAiredEpisode(TODAY_EPOCH_DAY))
    }

    @Test
    fun a_show_with_no_seasons_is_handled() {
        assertNull(tvDetails(seasons = emptyList()).lastAiredEpisode(TODAY_EPOCH_DAY))
    }

    @Test
    fun a_movie_has_no_episodes_to_order() {
        assertEquals(emptyList(), movieDetails().airedEpisodesInOrder(TODAY_EPOCH_DAY))
    }

    @Test
    fun episodes_air_in_season_then_episode_order_regardless_of_declaration_order() {
        val details = tvDetails(
            seasons = listOf(
                2 to listOf(TODAY_EPOCH_DAY - 5),
                1 to listOf(TODAY_EPOCH_DAY - 50),
            ),
        )

        assertEquals(
            listOf(EpisodeId(showId, 1, 1), EpisodeId(showId, 2, 1)),
            details.airedEpisodesInOrder(TODAY_EPOCH_DAY).map { it.id },
        )
    }
}
