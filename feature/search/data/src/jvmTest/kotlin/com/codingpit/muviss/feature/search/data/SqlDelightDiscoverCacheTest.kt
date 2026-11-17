package com.codingpit.muviss.feature.search.data

import com.codingpit.muviss.core.common.AppDispatchers
import com.codingpit.muviss.core.database.MuvissDatabase
import com.codingpit.muviss.core.network.DefaultMetadataLocale
import com.codingpit.muviss.core.network.MutableMetadataLocale
import com.codingpit.muviss.core.testing.inMemoryDatabase
import com.codingpit.muviss.core.testing.inMemoryDriver
import com.codingpit.muviss.feature.search.domain.ForYouAnswer
import com.codingpit.muviss.models.Genre
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.MediaType
import com.codingpit.muviss.models.PagedResult
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
    private val cache = SqlDelightDiscoverCache(database.discoverCacheQueries, database.forYouCacheQueries, locale, dispatchers)

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
        val fresh = SqlDelightDiscoverCache(database.discoverCacheQueries, database.forYouCacheQueries, DefaultMetadataLocale(), dispatchers)

        fresh.saveGenres(MediaType.TV, emptyList(), fetchedAtEpochMs = 1L)

        assertEquals(emptyList(), cache.genres(MediaType.TV)?.value, "the same key under en-US")
    }

    @Test
    fun a_superseded_write_never_replaces_a_newer_answer() = runTest {
        // A refresh overtaken by a later one can still land its last write: the newer stored copy must win.
        cache.saveGenres(MediaType.MOVIE, listOf(Genre("28", "Action")), fetchedAtEpochMs = 10L)
        cache.saveGenres(MediaType.MOVIE, listOf(Genre("99", "Old")), fetchedAtEpochMs = 5L)

        assertEquals(listOf(Genre("28", "Action")), cache.genres(MediaType.MOVIE)?.value)
        assertEquals(10L, cache.genres(MediaType.MOVIE)?.fetchedAtEpochMs)
    }

    @Test
    fun a_storage_failure_costs_the_copy_and_never_fails_the_refresh() = runTest {
        // A table that is gone makes every write throw: the answer must still come back to the caller.
        val driver = inMemoryDriver()
        driver.execute(null, "DROP TABLE discoverCache", 0)
        val broken = SqlDelightDiscoverCache(MuvissDatabase(driver).discoverCacheQueries, MuvissDatabase(driver).forYouCacheQueries, locale, dispatchers)

        broken.saveGenres(MediaType.MOVIE, listOf(Genre("28", "Action")), fetchedAtEpochMs = 1L)
        broken.savePopular(MediaType.MOVIE, 1, PagedResult(emptyList(), page = 1, totalPages = 1), fetchedAtEpochMs = 1L)
        // Reaching this line without an exception is the assertion: a write that threw would have failed the test.
    }

    private val seedA = MediaId.tmdbMovie("1")
    private val seedB = MediaId.tmdbMovie("2")
    private val seedC = MediaId.tmdbMovie("3")

    private fun answer(seed: MediaId, fetchedAtEpochMs: Long, vararg titles: String) = ForYouAnswer(seed, titles.map { MediaSummary(MediaId.tmdbMovie(it), it) }, fetchedAtEpochMs)

    @Test
    fun a_for_you_copy_reads_back_in_seed_order_with_each_seeds_fetch_time() = runTest {
        cache.saveForYou(listOf(seedA, seedB), listOf(answer(seedA, 5L, "10"), answer(seedB, 7L, "20")))

        val read = cache.forYou(listOf(seedB, seedA))

        assertEquals(listOf(seedA, seedB), read?.map { it.seed }, "the seeds are a set; the answers come back in the order they were stored")
        assertEquals(listOf(5L, 7L), read?.map { it.fetchedAtEpochMs })
        assertEquals(listOf(MediaId.tmdbMovie("10")), read?.first()?.items?.map { it.id })
    }

    @Test
    fun a_different_seed_set_or_language_is_a_miss() = runTest {
        cache.saveForYou(listOf(seedA), listOf(answer(seedA, 1L, "10")))

        assertNull(cache.forYou(listOf(seedA, seedC)), "a new seed changes the key")
        locale.language = "es-ES"
        assertNull(cache.forYou(listOf(seedA)), "a language change is a miss")
    }

    @Test
    fun a_newer_answer_for_a_seed_survives_a_superseded_write_and_a_superseded_set_is_dropped() = runTest {
        cache.saveForYou(listOf(seedA, seedB), listOf(answer(seedA, 10L, "10"), answer(seedB, 10L, "20")))
        // A late write of an older answer for A, and a refresh of B alone that is newer.
        cache.saveForYou(listOf(seedA, seedB), listOf(answer(seedA, 5L, "99"), answer(seedB, 12L, "21")))

        val read = cache.forYou(listOf(seedA, seedB))
        assertEquals(listOf(MediaId.tmdbMovie("10")), read?.first { it.seed == seedA }?.items?.map { it.id })
        assertEquals(listOf(MediaId.tmdbMovie("21")), read?.first { it.seed == seedB }?.items?.map { it.id })

        // A new seed set replaces the one row: the old set is no longer stored at all.
        cache.saveForYou(listOf(seedC), listOf(answer(seedC, 20L, "30")))
        assertNull(cache.forYou(listOf(seedA, seedB)))
        assertNull(database.forYouCacheQueries.selectByKey(keyOf(listOf(seedA, seedB))).executeAsOneOrNull(), "the superseded set's row is deleted, not kept beside the new one")
        assertNotNull(database.forYouCacheQueries.selectByKey(keyOf(listOf(seedC))).executeAsOneOrNull())
    }

    @Test
    fun a_for_you_row_that_no_longer_decodes_is_a_miss_and_never_a_crash() = runTest {
        cache.saveForYou(listOf(seedA), listOf(answer(seedA, 1L, "10")))
        database.forYouCacheQueries.upsert(cacheKey = keyOf(listOf(seedA)), json = """{"not":"a copy"}""")

        assertNull(cache.forYou(listOf(seedA)))
    }

    @Test
    fun a_storage_failure_costs_the_for_you_copy_and_never_fails_the_refresh() = runTest {
        val driver = inMemoryDriver()
        driver.execute(null, "DROP TABLE forYouCache", 0)
        val broken = SqlDelightDiscoverCache(MuvissDatabase(driver).discoverCacheQueries, MuvissDatabase(driver).forYouCacheQueries, locale, dispatchers)

        broken.saveForYou(listOf(seedA), listOf(answer(seedA, 1L, "10")))
        // Reaching this line without an exception is the assertion.
    }

    /** The key the cache derives for [seeds] in the current language; mirrors the production derivation on purpose. */
    private fun keyOf(seeds: List<MediaId>): String {
        val identity = seeds.map { it.toString() }.sorted().joinToString(",") + "|" + locale.language
        var hash = -3750763034362895579L
        for (byte in identity.encodeToByteArray()) hash = (hash xor (byte.toLong() and 0xff)) * 0x100000001B3L
        return "foryou:" + hash.toULong().toString(16).padStart(16, '0')
    }
}
