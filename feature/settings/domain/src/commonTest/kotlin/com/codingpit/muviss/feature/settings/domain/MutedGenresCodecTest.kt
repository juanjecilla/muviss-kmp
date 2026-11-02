package com.codingpit.muviss.feature.settings.domain

import com.codingpit.muviss.models.GenreKey
import com.codingpit.muviss.models.MediaType
import kotlin.test.Test
import kotlin.test.assertEquals

class MutedGenresCodecTest {
    private val talk = MutedGenreEntry(GenreKey(MediaType.TV, "10767"), "Talk")
    private val action = MutedGenreEntry(GenreKey(MediaType.MOVIE, "28"), "Action & Adventure")

    @Test
    fun entries_round_trip() {
        assertEquals(listOf(talk, action), MutedGenresCodec.decode(MutedGenresCodec.encode(listOf(talk, action))))
    }

    @Test
    fun an_empty_string_is_muting_nothing_not_the_defaults() {
        assertEquals(emptyList(), MutedGenresCodec.decode(""))
        assertEquals("", MutedGenresCodec.encode(emptyList()))
    }

    @Test
    fun an_entry_that_does_not_parse_is_dropped_and_the_rest_kept() {
        assertEquals(listOf(talk), MutedGenresCodec.decode("garbage;tv:10767:Talk;radio:1:X;tv::Blank"))
    }

    @Test
    fun a_colon_in_a_name_survives_because_only_the_first_two_split() {
        val odd = MutedGenreEntry(GenreKey(MediaType.TV, "1"), "A: B")

        assertEquals(listOf(odd), MutedGenresCodec.decode(MutedGenresCodec.encode(listOf(odd))))
    }

    @Test
    fun a_duplicate_key_collapses_to_its_first_entry() {
        assertEquals(listOf(talk), MutedGenresCodec.decode("tv:10767:Talk;tv:10767:Talk again"))
    }

    @Test
    fun the_defaults_are_talk_news_and_reality_on_tv() {
        assertEquals(
            setOf(GenreKey(MediaType.TV, "10767"), GenreKey(MediaType.TV, "10763"), GenreKey(MediaType.TV, "10764")),
            MutedGenresCodec.toMutedGenres(MutedGenresCodec.DEFAULTS).keys,
        )
    }
}
