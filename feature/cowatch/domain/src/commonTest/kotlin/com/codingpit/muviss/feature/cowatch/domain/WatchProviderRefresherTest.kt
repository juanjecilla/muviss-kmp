package com.codingpit.muviss.feature.cowatch.domain

import com.codingpit.muviss.core.testing.FakeClock
import com.codingpit.muviss.models.MediaId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `underscore_case` — see [InviteCodeTest]. */
class WatchProviderRefresherTest {

    private class FakeCache : WatchProviderCache {
        val stored = mutableMapOf<MediaId, CachedProviders>()
        var getCallCount = 0

        override suspend fun get(mediaIds: Set<MediaId>): Map<MediaId, CachedProviders> {
            getCallCount++
            return stored.filterKeys { it in mediaIds }
        }

        override suspend fun put(mediaId: MediaId, region: String, flatrateProviderIds: Set<String>) {
            stored[mediaId] = CachedProviders(region, flatrateProviderIds, fetchedAtEpochMs = -1)
        }
    }

    private class FakeSource(override var region: String = "US") : WatchProviderSource {
        var fetchCount = 0
        var nextResult: Result<Set<String>> = Result.success(emptySet())

        override suspend fun fetchFlatrateIds(mediaId: MediaId): Result<Set<String>> {
            fetchCount++
            return nextResult
        }
    }

    private val id = MediaId.tmdbMovie("1")

    @Test
    fun a_never_cached_title_is_fetched_and_stored() = runTest {
        val cache = FakeCache()
        val source = FakeSource().apply { nextResult = Result.success(setOf("8")) }
        val refresher = WatchProviderRefresher(cache, source, FakeClock(0L))

        val result = refresher.refresh(setOf(id))

        assertEquals(setOf("8"), result[id])
        assertEquals(1, source.fetchCount)
        assertEquals(setOf("8"), cache.stored[id]?.flatrateProviderIds)
    }

    @Test
    fun a_fresh_cache_entry_is_served_without_a_fetch() = runTest {
        val cache = FakeCache().apply { stored[id] = CachedProviders("US", setOf("8"), fetchedAtEpochMs = 0L) }
        val source = FakeSource()
        val clock = FakeClock(1_000L) // well inside the default 24h TTL
        val refresher = WatchProviderRefresher(cache, source, clock)

        val result = refresher.refresh(setOf(id))

        assertEquals(setOf("8"), result[id])
        assertEquals(0, source.fetchCount)
    }

    @Test
    fun an_entry_older_than_the_ttl_is_refetched() = runTest {
        val oneDayMs = 24 * 60 * 60 * 1000L
        val cache = FakeCache().apply { stored[id] = CachedProviders("US", setOf("8"), fetchedAtEpochMs = 0L) }
        val source = FakeSource().apply { nextResult = Result.success(setOf("9")) }
        val clock = FakeClock(oneDayMs + 1)
        val refresher = WatchProviderRefresher(cache, source, clock)

        val result = refresher.refresh(setOf(id))

        assertEquals(setOf("9"), result[id])
        assertEquals(1, source.fetchCount)
    }

    @Test
    fun a_region_change_invalidates_the_cache_regardless_of_age() = runTest {
        // The whole point of storing the region: a Settings change is not
        // stuck behind the TTL.
        val cache = FakeCache().apply { stored[id] = CachedProviders("US", setOf("8"), fetchedAtEpochMs = 0L) }
        val source = FakeSource(region = "GB").apply { nextResult = Result.success(setOf("30")) }
        val clock = FakeClock(1L) // far inside the TTL
        val refresher = WatchProviderRefresher(cache, source, clock)

        val result = refresher.refresh(setOf(id))

        assertEquals(setOf("30"), result[id])
        assertEquals(1, source.fetchCount)
    }

    @Test
    fun a_failed_fetch_falls_back_to_the_stale_cached_value() = runTest {
        val oneDayMs = 24 * 60 * 60 * 1000L
        val cache = FakeCache().apply { stored[id] = CachedProviders("US", setOf("8"), fetchedAtEpochMs = 0L) }
        val source = FakeSource().apply { nextResult = Result.failure(RuntimeException("offline")) }
        val clock = FakeClock(oneDayMs + 1)
        val refresher = WatchProviderRefresher(cache, source, clock)

        val result = refresher.refresh(setOf(id))

        assertEquals(setOf("8"), result[id])
    }

    @Test
    fun a_failed_fetch_with_nothing_cached_yields_an_empty_set_not_an_error() = runTest {
        val cache = FakeCache()
        val source = FakeSource().apply { nextResult = Result.failure(RuntimeException("offline")) }
        val refresher = WatchProviderRefresher(cache, source, FakeClock(0L))

        val result = refresher.refresh(setOf(id))

        assertTrue(result[id]!!.isEmpty())
    }

    @Test
    fun an_empty_request_touches_neither_the_cache_nor_the_source() = runTest {
        val cache = FakeCache()
        val source = FakeSource()
        val refresher = WatchProviderRefresher(cache, source, FakeClock(0L))

        assertTrue(refresher.refresh(emptySet()).isEmpty())
        assertEquals(0, cache.getCallCount)
        assertEquals(0, source.fetchCount)
    }
}
