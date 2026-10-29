package com.codingpit.muviss.feature.nextpick.data

import com.codingpit.muviss.models.Genre
import com.codingpit.muviss.models.MediaType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LibraryGenreIdsTest {

    @Test
    fun live_catalogue_is_asked_once_per_language_and_type() = runTest {
        var calls = 0
        val resolver = LibraryGenreIds(liveCatalogue = {
            calls++
            listOf(Genre("35", "Comedy"))
        }, language = { "en-US" })

        resolver.index(MediaType.MOVIE, online = true)
        val index = resolver.index(MediaType.MOVIE, online = true)

        assertEquals(listOf("35"), index.idsOf(listOf("Comedy")))
        assertEquals(1, calls)
    }

    @Test
    fun failed_live_fetch_is_not_memoised_and_falls_back_to_the_bundled_names() = runTest {
        var calls = 0
        val resolver = LibraryGenreIds(liveCatalogue = {
            calls++
            error("network down")
        }, language = { "en-US" })

        val first = resolver.index(MediaType.MOVIE, online = true).idsOf(listOf("Comedia", "Comedy"))
        resolver.index(MediaType.MOVIE, online = true)

        assertEquals(listOf("35"), first)
        assertEquals(2, calls)
    }

    @Test
    fun offline_never_asks_the_live_catalogue() = runTest {
        var calls = 0
        val resolver = LibraryGenreIds(liveCatalogue = {
            calls++
            emptyList()
        }, language = { "en-US" })

        val ids = resolver.index(MediaType.TV, online = false).idsOf(listOf("Talk"))

        assertEquals(listOf("10767"), ids)
        assertEquals(0, calls)
    }

    @Test
    fun cancellation_from_the_live_catalogue_is_rethrown_not_swallowed() = runTest {
        val resolver = LibraryGenreIds(liveCatalogue = { throw CancellationException("cancelled") }, language = { "en-US" })

        assertFailsWith<CancellationException> { resolver.index(MediaType.MOVIE, online = true) }
    }

    @Test
    fun names_match_ignoring_case_and_surrounding_spaces() = runTest {
        val index = LibraryGenreIds(liveCatalogue = { emptyList() }, language = { "en-US" }).index(MediaType.MOVIE, online = false)

        assertEquals(listOf("35"), index.idsOf(listOf("  comedy ")))
    }

    @Test
    fun unknown_and_blank_names_map_to_nothing_without_failing() = runTest {
        val index = LibraryGenreIds(liveCatalogue = { emptyList() }, language = { "en-US" }).index(MediaType.MOVIE, online = false)

        assertEquals(emptyList(), index.idsOf(listOf("", "Not A Genre")))
    }

    @Test
    fun live_names_take_precedence_over_the_bundled_ones() = runTest {
        val resolver = LibraryGenreIds(liveCatalogue = { listOf(Genre("999", "Comedy")) }, language = { "en-US" })

        val ids = resolver.index(MediaType.MOVIE, online = true).idsOf(listOf("Comedy"))

        assertEquals(listOf("999"), ids)
    }
}
