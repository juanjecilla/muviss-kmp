package com.codingpit.muviss.core.network.tmdb

import com.codingpit.muviss.models.MediaType
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Muted Genres reach TMDB as `without_genres` on `/discover` (verified live 2026-10-17:
 * `without_genres=10767|10763` drops the talk shows; `|` is "any of these"). Everything else
 * is filtered on our side from `genre_ids`.
 */
class TmdbDiscoverGenresTest {
    private fun fixture() = ProviderFixture(handler = { respondJson(EMPTY_PAGE) })

    @Test
    fun excluded_genres_are_sent_as_a_pipe_joined_without_genres() = runTest {
        val fixture = fixture()

        fixture.provider.discover(MediaType.TV, page = 1, genreId = null, excludedGenreIds = setOf("10767", "10763"))

        assertEquals("10767|10763", fixture.requests.single().url.parameters["without_genres"])
    }

    @Test
    fun no_exclusions_sends_no_without_genres() = runTest {
        val fixture = fixture()

        fixture.provider.discover(MediaType.TV, page = 1, genreId = null)

        assertNull(fixture.requests.single().url.parameters["without_genres"])
    }

    @Test
    fun an_explicit_genre_is_sent_and_never_excluded_in_the_same_request() = runTest {
        val fixture = fixture()

        fixture.provider.discover(MediaType.TV, page = 1, genreId = "10764", excludedGenreIds = setOf("10764", "10767"))

        val params = fixture.requests.single().url.parameters
        assertEquals("10764", params["with_genres"])
        assertEquals("10767", params["without_genres"])
    }

    @Test
    fun an_explicit_genre_that_was_the_only_exclusion_sends_no_without_genres() = runTest {
        val fixture = fixture()

        fixture.provider.discover(MediaType.TV, page = 1, genreId = "10764", excludedGenreIds = setOf("10764"))

        assertNull(fixture.requests.single().url.parameters["without_genres"])
    }
}
