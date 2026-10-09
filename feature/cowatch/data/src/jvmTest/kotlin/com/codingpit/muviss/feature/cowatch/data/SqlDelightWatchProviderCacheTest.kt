@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.cowatch.data

import com.codingpit.muviss.core.common.AppDispatchers
import com.codingpit.muviss.core.database.MuvissDatabase
import com.codingpit.muviss.core.testing.FakeClock
import com.codingpit.muviss.core.testing.inMemoryDatabase
import com.codingpit.muviss.models.MediaId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class CacheTestDispatchers(d: CoroutineDispatcher) : AppDispatchers {
    override val default = d
    override val io = d
}

/** [SqlDelightWatchProviderCache] against a real database (EPIC 41 follow-up, #122). */
class SqlDelightWatchProviderCacheTest {

    private lateinit var database: MuvissDatabase
    private lateinit var clock: FakeClock
    private lateinit var cache: SqlDelightWatchProviderCache

    private val movie = MediaId.tmdbMovie("1")
    private val show = MediaId.tmdbTv("2")

    @BeforeTest
    fun setUp() {
        database = inMemoryDatabase()
        clock = FakeClock(1_000L)
        cache = SqlDelightWatchProviderCache(database, clock, CacheTestDispatchers(UnconfinedTestDispatcher()))
    }

    @Test
    fun `an id never put is absent from the result rather than mapped to null`() = runTest {
        assertTrue(cache.get(setOf(movie)).isEmpty())
    }

    @Test
    fun `a put round-trips region, ids and the write-time timestamp`() = runTest {
        clock.epochMs = 5_000L
        cache.put(movie, region = "US", flatrateProviderIds = setOf("8", "337"))

        val cached = cache.get(setOf(movie))[movie]!!
        assertEquals("US", cached.region)
        assertEquals(setOf("8", "337"), cached.flatrateProviderIds)
        assertEquals(5_000L, cached.fetchedAtEpochMs)
    }

    @Test
    fun `a put with no providers round-trips as an empty set, not a one-element set of the empty string`() = runTest {
        cache.put(movie, region = "US", flatrateProviderIds = emptySet())

        assertTrue(cache.get(setOf(movie))[movie]!!.flatrateProviderIds.isEmpty())
    }

    @Test
    fun `a second put for the same id replaces rather than accumulates`() = runTest {
        cache.put(movie, region = "US", flatrateProviderIds = setOf("8"))
        clock.epochMs = 2_000L
        cache.put(movie, region = "GB", flatrateProviderIds = setOf("30"))

        val cached = cache.get(setOf(movie))[movie]!!
        assertEquals("GB", cached.region)
        assertEquals(setOf("30"), cached.flatrateProviderIds)
        assertEquals(2_000L, cached.fetchedAtEpochMs)
    }

    @Test
    fun `get only returns the requested ids`() = runTest {
        cache.put(movie, region = "US", flatrateProviderIds = setOf("8"))
        cache.put(show, region = "US", flatrateProviderIds = setOf("9"))

        val result = cache.get(setOf(movie))
        assertEquals(setOf(movie), result.keys)
    }

    @Test
    fun `an empty request never touches the database`() = runTest {
        assertTrue(cache.get(emptySet()).isEmpty())
    }
}
