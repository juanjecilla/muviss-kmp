package com.codingpit.muviss.feature.cowatch.data

import com.codingpit.muviss.core.network.NotFoundTitles
import com.codingpit.muviss.core.testing.FakeClock
import com.codingpit.muviss.feature.cowatch.domain.CachedProviders
import com.codingpit.muviss.feature.cowatch.domain.WatchProviderCache
import com.codingpit.muviss.feature.cowatch.domain.WatchProviderSource
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MetadataError
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** #762: the pick's fill writes only what TMDB answered, and a failure never becomes "on no service". */
class TmdbWatchProviderFillTest {

    private class RecordingCache : WatchProviderCache {
        val puts = mutableListOf<Triple<MediaId, String, Set<String>>>()

        override suspend fun get(mediaIds: Set<MediaId>): Map<MediaId, CachedProviders> = emptyMap()

        override suspend fun put(mediaId: MediaId, region: String, flatrateProviderIds: Set<String>) {
            puts += Triple(mediaId, region, flatrateProviderIds)
        }
    }

    /** Answers each title from [answers] and records the order it was asked in. */
    private class ScriptedSource(private val answers: Map<MediaId, Result<Set<String>>>) : WatchProviderSource {
        override val region: String = "US"
        val asked = mutableListOf<MediaId>()
        val regions = mutableListOf<String>()

        override suspend fun fetchFlatrateIds(mediaId: MediaId, region: String): Result<Set<String>> {
            asked += mediaId
            regions += region
            return answers.getValue(mediaId)
        }
    }

    private val notFound = NotFoundTitles(FakeClock(epochMs = 0L))
    private val a = MediaId.tmdbMovie("1")
    private val b = MediaId.tmdbMovie("2")
    private val c = MediaId.tmdbMovie("3")

    @Test
    fun `every_answered_title_is_cached_for_the_region_in_the_order_given`() = runTest {
        val cache = RecordingCache()
        val source = ScriptedSource(mapOf(a to Result.success(setOf("8")), b to Result.success(setOf("9", "337"))))

        TmdbWatchProviderFill(cache, source, notFound).fill(listOf(a, b), region = "US")

        assertEquals(listOf(a, b), source.asked)
        assertEquals(listOf(Triple(a, "US", setOf("8")), Triple(b, "US", setOf("9", "337"))), cache.puts)
    }

    @Test
    fun `a_title_on_no_flatrate_service_is_cached_as_empty_so_it_is_not_asked_about_again`() = runTest {
        val cache = RecordingCache()
        val source = ScriptedSource(mapOf(a to Result.success(emptySet())))

        TmdbWatchProviderFill(cache, source, notFound).fill(listOf(a), region = "US")

        assertEquals(listOf(Triple(a, "US", emptySet())), cache.puts)
    }

    @Test
    fun `a_title_that_no_longer_exists_is_skipped_and_the_rest_still_fill`() = runTest {
        val cache = RecordingCache()
        val source = ScriptedSource(mapOf(a to Result.failure(MetadataError.NotFound()), b to Result.success(setOf("8"))))

        TmdbWatchProviderFill(cache, source, notFound).fill(listOf(a, b), region = "US")

        assertEquals(listOf(a, b), source.asked)
        assertEquals(listOf(Triple(b, "US", setOf("8"))), cache.puts)
    }

    @Test
    fun `an_offline_failure_writes_nothing_for_that_title_and_ends_the_batch`() = runTest {
        val cache = RecordingCache()
        val source = ScriptedSource(
            mapOf(
                a to Result.success(setOf("8")),
                b to Result.failure(MetadataError.Offline()),
                c to Result.success(setOf("9")),
            ),
        )

        TmdbWatchProviderFill(cache, source, notFound).fill(listOf(a, b, c), region = "US")

        assertEquals(listOf(a, b), source.asked, "the title after the failure is not asked about")
        assertEquals(listOf(Triple(a, "US", setOf("8"))), cache.puts, "what was answered before the failure is kept; the failed title is not written")
        assertTrue(cache.puts.none { it.first == b }, "a failure is never recorded as on no service")
    }

    @Test
    fun `a_failed_cache_write_ends_the_batch_without_escaping_the_fill`() = runTest {
        // A storage failure on one write must not throw out of the fill: the pick reads the cache as it is.
        val failingCache = object : WatchProviderCache {
            val puts = mutableListOf<MediaId>()

            override suspend fun get(mediaIds: Set<MediaId>): Map<MediaId, CachedProviders> = emptyMap()

            override suspend fun put(mediaId: MediaId, region: String, flatrateProviderIds: Set<String>) {
                check(mediaId != a) { "disk full" }
                puts += mediaId
            }
        }
        val source = ScriptedSource(mapOf(a to Result.success(setOf("8")), b to Result.success(setOf("9"))))

        TmdbWatchProviderFill(failingCache, source, notFound).fill(listOf(a, b), region = "US")

        assertEquals(listOf(a), source.asked, "the batch ends at the failed write; the title after it is not fetched")
        assertTrue(failingCache.puts.isEmpty())
    }

    @Test
    fun `a_title_the_source_does_not_know_is_remembered_as_missing_for_the_next_pick`() = runTest {
        val cache = RecordingCache()
        val source = ScriptedSource(mapOf(a to Result.failure(MetadataError.NotFound()), b to Result.success(setOf("8"))))

        TmdbWatchProviderFill(cache, source, notFound).fill(listOf(a, b), region = "US")

        assertEquals(setOf(a), notFound.known(setOf(a, b)), "a is remembered as missing, b is not")
    }

    @Test
    fun `every_fetch_names_the_region_the_batch_was_given`() = runTest {
        val cache = RecordingCache()
        val source = ScriptedSource(mapOf(a to Result.success(setOf("8")), b to Result.success(setOf("9"))))

        TmdbWatchProviderFill(cache, source, notFound).fill(listOf(a, b), region = "FR")

        assertEquals(listOf("FR", "FR"), source.regions, "the request and the cache write use the same region")
    }
}
