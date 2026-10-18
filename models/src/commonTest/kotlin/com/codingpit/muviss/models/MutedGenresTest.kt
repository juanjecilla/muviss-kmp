package com.codingpit.muviss.models

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MutedGenresTest {
    private val talk = GenreKey(MediaType.TV, "10767")
    private val muted = MutedGenres(setOf(talk, GenreKey(MediaType.TV, "10763")))

    private fun tv(vararg genreIds: String) = MediaSummary(id = MediaId.tmdbTv("1"), title = "T", genreIds = genreIds.toList())

    private fun movie(vararg genreIds: String) = MediaSummary(id = MediaId.tmdbMovie("1"), title = "M", genreIds = genreIds.toList())

    @Test
    fun a_title_is_hidden_when_any_of_its_genres_is_muted() {
        assertTrue(muted.hides(tv("35", "10767")))
    }

    @Test
    fun a_title_with_no_muted_genre_is_kept() {
        assertFalse(muted.hides(tv("35", "18")))
    }

    @Test
    fun a_title_with_no_genre_ids_is_never_hidden() {
        assertFalse(muted.hides(tv()))
    }

    @Test
    fun genre_ids_are_keyed_by_media_type() {
        // Movie genre 10767 is not TV's Talk: ids are only unique within a type.
        assertFalse(muted.hides(movie("10767")))
    }

    @Test
    fun forType_returns_only_that_types_ids() {
        val mixed = MutedGenres(setOf(talk, GenreKey(MediaType.MOVIE, "99")))

        assertEquals(setOf("10767"), mixed.forType(MediaType.TV))
        assertEquals(setOf("99"), mixed.forType(MediaType.MOVIE))
    }

    @Test
    fun without_removes_an_explicitly_picked_genre_and_leaves_the_rest() {
        val picked = muted.without(MediaType.TV, "10767")

        assertEquals(setOf("10763"), picked.forType(MediaType.TV))
        assertFalse(picked.hides(tv("10767")))
        assertTrue(picked.hides(tv("10763")))
    }

    @Test
    fun without_a_null_genre_changes_nothing() {
        assertEquals(muted, muted.without(MediaType.TV, null))
    }

    @Test
    fun an_empty_set_hides_nothing() {
        assertFalse(MutedGenres.NONE.hides(tv("10767")))
    }
}
