package com.codingpit.muviss.appfunctions

import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaType
import com.codingpit.muviss.models.WatchStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class AppFunctionArgumentsTest {

    @Test
    fun rating_accepts_one_to_ten_and_rejects_the_rest() {
        assertEquals(1, parseRating(1))
        assertEquals(10, parseRating(10))
        assertFailsWith<InvalidAgentArgument> { parseRating(0) }
        assertFailsWith<InvalidAgentArgument> { parseRating(11) }
        assertFailsWith<InvalidAgentArgument> { parseRating(-3) }
    }

    @Test
    fun status_is_optional_and_case_insensitive() {
        assertNull(parseStatus(null))
        assertNull(parseStatus("  "))
        assertEquals(WatchStatus.WATCHING, parseStatus("watching"))
        assertEquals(WatchStatus.NOT_STARTED, parseStatus("NOT_STARTED"))
        assertFailsWith<InvalidAgentArgument> { parseStatus("DROPPED") }
    }

    @Test
    fun media_type_maps_the_two_wire_names() {
        assertEquals(MediaType.MOVIE, parseMediaType("movie"))
        assertEquals(MediaType.TV, parseMediaType("TV"))
        assertNull(parseMediaType(null))
        assertFailsWith<InvalidAgentArgument> { parseMediaType("game") }
    }

    @Test
    fun media_id_round_trips_and_bad_input_is_an_argument_error_without_the_parser_text() {
        assertEquals(MediaId.tmdbTv("1399"), parseMediaId("tmdb:tv:1399"))
        // An unknown type makes MediaId.parse throw IllegalStateException; it must not reach the agent as is.
        assertFailsWith<InvalidAgentArgument> { parseMediaId("tmdb:game:1399") }
        assertFailsWith<InvalidAgentArgument> { parseMediaId("1399") }
    }

    @Test
    fun episode_id_parses_show_season_and_episode() {
        assertEquals(EpisodeId(MediaId.tmdbTv("1399"), 1, 2), parseEpisodeId("tmdb:tv:1399/1/2"))
        assertFailsWith<InvalidAgentArgument> { parseEpisodeId("tmdb:tv:1399/1") }
        assertFailsWith<InvalidAgentArgument> { parseEpisodeId("tmdb:tv:1399/x/2") }
    }
}
