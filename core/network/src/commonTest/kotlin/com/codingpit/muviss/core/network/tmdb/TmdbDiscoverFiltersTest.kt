package com.codingpit.muviss.core.network.tmdb

import com.codingpit.muviss.core.common.epochDayOfCivil
import com.codingpit.muviss.core.network.DiscoverFilters
import com.codingpit.muviss.core.network.DiscoverSort
import com.codingpit.muviss.models.MediaType
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What a Pick Session's filters turn into on the wire (EPIC 51, #399). */
class TmdbDiscoverFiltersTest {
    private fun fixture() = ProviderFixture(handler = { respondJson(EMPTY_PAGE) })

    private suspend fun ask(type: MediaType, filters: DiscoverFilters) = fixture().let { f ->
        f.provider.discoverFiltered(type, page = 1, filters = filters)
        f.requests.single().url.parameters
    }

    @Test
    fun no_filters_is_the_plain_popular_browse() = runTest {
        val params = ask(MediaType.MOVIE, DiscoverFilters())

        assertEquals("popularity.desc", params["sort_by"])
        listOf("with_genres", "without_genres", "with_runtime.gte", "with_original_language", "with_watch_providers", "certification", "vote_count.gte").forEach {
            assertNull(params[it], "$it should not be sent")
        }
    }

    @Test
    fun genres_are_any_of_and_an_included_genre_is_never_also_excluded() = runTest {
        val params = ask(MediaType.MOVIE, DiscoverFilters(genreIds = setOf("35", "16"), excludedGenreIds = setOf("16", "10767")))

        assertEquals("35|16", params["with_genres"])
        assertEquals("10767", params["without_genres"])
    }

    @Test
    fun runtime_language_and_votes_are_sent_as_given() = runTest {
        val params = ask(
            MediaType.TV,
            DiscoverFilters(minRuntimeMinutes = 20, maxRuntimeMinutes = 45, originalLanguage = "es", minVoteCount = 100, sort = DiscoverSort.RATING),
        )

        assertEquals("20", params["with_runtime.gte"])
        assertEquals("45", params["with_runtime.lte"])
        assertEquals("es", params["with_original_language"])
        assertEquals("100", params["vote_count.gte"])
        assertEquals("vote_average.desc", params["sort_by"])
    }

    @Test
    fun release_dates_use_the_films_or_the_shows_parameter() = runTest {
        val from = epochDayOfCivil(2020, 1, 5)
        val to = epochDayOfCivil(2024, 12, 31)

        val movie = ask(MediaType.MOVIE, DiscoverFilters(releasedOnOrAfterEpochDay = from, releasedOnOrBeforeEpochDay = to))
        assertEquals("2020-01-05", movie["primary_release_date.gte"])
        assertEquals("2024-12-31", movie["primary_release_date.lte"])
        assertNull(movie["first_air_date.gte"])

        val tv = ask(MediaType.TV, DiscoverFilters(releasedOnOrAfterEpochDay = from))
        assertEquals("2020-01-05", tv["first_air_date.gte"])
        assertNull(tv["primary_release_date.gte"])
    }

    @Test
    fun watch_providers_need_a_region_and_are_dropped_without_one() = runTest {
        val with = ask(MediaType.MOVIE, DiscoverFilters(watchProviderIds = setOf(8, 337), watchRegion = "ES"))
        assertEquals("8|337", with["with_watch_providers"])
        assertEquals("ES", with["watch_region"])

        val without = ask(MediaType.MOVIE, DiscoverFilters(watchProviderIds = setOf(8)))
        assertNull(without["with_watch_providers"])
        assertNull(without["watch_region"])
    }

    @Test
    fun certification_is_a_film_only_filter() = runTest {
        val filters = DiscoverFilters(certificationCountry = "US", certifications = setOf("G", "PG"))

        val movie = ask(MediaType.MOVIE, filters)
        assertEquals("US", movie["certification_country"])
        assertEquals("G|PG", movie["certification"])

        val tv = ask(MediaType.TV, filters)
        assertNull(tv["certification"])
        assertNull(tv["certification_country"])
    }

    @Test
    fun the_old_discover_call_sends_what_it_always_did() = runTest {
        val f = fixture()
        f.provider.discover(MediaType.TV, page = 2, genreId = "10764", excludedGenreIds = setOf("10767"))

        val params = f.requests.single().url.parameters
        assertEquals("10764", params["with_genres"])
        assertEquals("10767", params["without_genres"])
        assertEquals("2", params["page"])
        assertEquals("popularity.desc", params["sort_by"])
    }
}
