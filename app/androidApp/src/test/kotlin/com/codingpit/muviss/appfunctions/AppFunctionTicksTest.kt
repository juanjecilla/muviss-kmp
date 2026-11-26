package com.codingpit.muviss.appfunctions

import com.codingpit.muviss.models.Episode
import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.MediaId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppFunctionTicksTest {

    private val show = MediaId.tmdbTv("1399")
    private val today = 20_000L

    private fun episode(airDate: Long?) = Episode(
        id = EpisodeId(show, 1, 2),
        seasonNumber = 1,
        episodeNumber = 2,
        name = "Pilot",
        airDateEpochDay = airDate,
    )

    @Test
    fun an_episode_missing_from_the_catalog_is_refused_as_not_in_catalog() {
        assertEquals(TickRefusal.NOT_IN_CATALOG, tickRefusal(null, today))
    }

    @Test
    fun an_unaired_episode_is_refused() {
        assertEquals(TickRefusal.NOT_AIRED, tickRefusal(episode(today + 1), today))
    }

    @Test
    fun an_undated_episode_is_refused_because_it_has_not_aired() {
        assertEquals(TickRefusal.NOT_AIRED, tickRefusal(episode(null), today))
    }

    @Test
    fun an_episode_that_aired_today_or_earlier_may_be_ticked() {
        assertNull(tickRefusal(episode(today), today))
        assertNull(tickRefusal(episode(today - 400), today))
    }
}
