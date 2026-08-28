package com.codingpit.muviss.models

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * `episodePlay` stores an episode's identity as the string [EpisodeId.toString]
 * produces (ADR 0011), so reading a play row back means parsing it. These pin
 * the round trip.
 */
class EpisodeIdTest {

    @Test
    fun an_episode_id_round_trips_through_its_string_form() {
        val id = EpisodeId(MediaId.tmdbTv("1399"), seasonNumber = 3, episodeNumber = 9)

        assertEquals("tmdb:tv:1399/3/9", id.toString())
        assertEquals(id, EpisodeId.parse(id.toString()))
    }

    @Test
    fun a_movie_tick_round_trips_too() {
        val id = EpisodeId.forMovie(MediaId.tmdbMovie("603"))

        assertEquals(id, EpisodeId.parse(id.toString()))
    }

    @Test
    fun a_malformed_id_is_rejected_rather_than_guessed_at() {
        assertFailsWith<IllegalArgumentException> { EpisodeId.parse("tmdb:tv:1399/3") }
        assertFailsWith<IllegalArgumentException> { EpisodeId.parse("tmdb:tv:1399/x/9") }
    }
}
