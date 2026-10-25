package com.codingpit.muviss.feature.nextpick.domain

import com.codingpit.muviss.feature.nextpick.api.PickMood
import com.codingpit.muviss.models.MediaType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MoodGenresTest {

    @Test
    fun no_mood_means_no_genre_constraint() {
        assertEquals(emptySet(), MoodGenres.genreIds(emptySet(), MediaType.MOVIE))
        assertEquals(emptySet(), MoodGenres.genreIds(emptySet(), null))
    }

    @Test
    fun a_mood_reads_as_that_types_genres() {
        assertEquals(setOf("35", "10751", "16"), MoodGenres.genreIds(setOf(PickMood.LIGHT), MediaType.MOVIE))
        assertEquals(setOf("10759", "10765"), MoodGenres.genreIds(setOf(PickMood.ESCAPE), MediaType.TV))
    }

    @Test
    fun moods_are_united() {
        val ids = MoodGenres.genreIds(setOf(PickMood.TENSE, PickMood.ROMANTIC), MediaType.MOVIE)
        assertEquals(setOf("53", "80", "9648", "27", "10749", "35"), ids)
    }

    @Test
    fun any_type_is_the_union_of_both_lists() {
        val ids = MoodGenres.genreIds(setOf(PickMood.ESCAPE), null)
        assertTrue("878" in ids && "10765" in ids)
    }

    @Test
    fun every_mood_has_genres_for_both_types() {
        PickMood.entries.forEach { mood ->
            assertTrue(MoodGenres.genreIds(setOf(mood), MediaType.MOVIE).isNotEmpty(), "$mood has no movie genres")
            assertTrue(MoodGenres.genreIds(setOf(mood), MediaType.TV).isNotEmpty(), "$mood has no TV genres")
        }
    }
}
