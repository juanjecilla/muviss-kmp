package com.codingpit.muviss.feature.search.data

import com.codingpit.muviss.core.common.AppDispatchers
import com.codingpit.muviss.core.network.DefaultMetadataLocale
import com.codingpit.muviss.core.network.MutableMetadataLocale
import com.codingpit.muviss.core.testing.inMemoryDatabase
import com.codingpit.muviss.models.Genre
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.MediaType
import com.codingpit.muviss.models.PagedResult
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** #724: what Discover stores round-trips, is keyed by language, and a row that no longer decodes is a miss. */
class SqlDelightDiscoverCacheTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val dispatchers = object : AppDispatchers {
        override val default = dispatcher
        override val io = dispatcher
    }

    private val database = inMemoryDatabase()
    private val locale = MutableMetadataLocale()
    private val cache = SqlDelightDiscoverCache(database.discoverCacheQueries, locale, dispatchers)

    @Test
    fun genres_and_a_popular_page_read_back_as_they_were_stored() = runTest {
        val talk = MediaSummary(MediaId.tmdbTv("1"), "Talk", genreIds = listOf("10767"))
        cache.saveGenres(MediaType.TV, listOf(Genre("10767", "Talk")), fetchedAtEpochMs = 5L)
        cache.savePopular(MediaType.TV, 2, PagedResult(listOf(talk), page = 2, totalPages = 9), fetchedAtEpochMs = 6L)

        assertEquals(listOf(Genre("10767", "Talk")), cache.genres(MediaType.TV)?.value)
        assertEquals(5L, cache.genres(MediaType.TV)?.fetchedAtEpochMs)
        assertEquals(listOf(talk), cache.popular(MediaType.TV, 2)?.value?.items)
        assertEquals(9, cache.popular(MediaType.TV, 2)?.value?.totalPages)
    }

    @Test
    fun a_language_change_is_a_miss_not_stale_content() = runTest {
        cache.saveGenres(MediaType.MOVIE, listOf(Genre("28", "Action")), fetchedAtEpochMs = 1L)

        locale.language = "es-ES"

        assertNull(cache.genres(MediaType.MOVIE), "the stored copy is English, so Spanish must fetch")
    }

    @Test
    fun a_row_that_no_longer_decodes_is_a_miss_and_never_a_crash() = runTest {
        database.discoverCacheQueries.upsert(cacheKey = "popular:tv:1|en-US", json = """{"not":"a page"}""", fetchedAtEpochMs = 1L)

        assertNull(cache.popular(MediaType.TV, 1))
    }

    @Test
    fun a_page_is_replaced_by_the_next_refresh_so_the_cache_does_not_grow() = runTest {
        cache.savePopular(MediaType.MOVIE, 1, PagedResult(emptyList(), page = 1, totalPages = 1), fetchedAtEpochMs = 1L)
        cache.savePopular(MediaType.MOVIE, 1, PagedResult(emptyList(), page = 1, totalPages = 4), fetchedAtEpochMs = 2L)

        assertEquals(4, cache.popular(MediaType.MOVIE, 1)?.value?.totalPages)
        assertEquals(2L, cache.popular(MediaType.MOVIE, 1)?.fetchedAtEpochMs)
    }

    @Test
    fun the_default_locale_is_what_a_fresh_install_reads_in() = runTest {
        val fresh = SqlDelightDiscoverCache(database.discoverCacheQueries, DefaultMetadataLocale(), dispatchers)

        fresh.saveGenres(MediaType.TV, emptyList(), fetchedAtEpochMs = 1L)

        assertEquals(emptyList(), cache.genres(MediaType.TV)?.value, "the same key under en-US")
    }
}
