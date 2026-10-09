@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.progress.data

import com.codingpit.muviss.core.common.AppDispatchers
import com.codingpit.muviss.core.database.MuvissDatabase
import com.codingpit.muviss.core.testing.CountingDriver
import com.codingpit.muviss.core.testing.FakeClock
import com.codingpit.muviss.core.testing.inMemoryDriver
import com.codingpit.muviss.feature.progress.domain.EpisodeCatalogCache
import com.codingpit.muviss.feature.progress.domain.EpisodeCatalogSource
import com.codingpit.muviss.feature.progress.domain.FetchEpisodeCatalogUseCase
import com.codingpit.muviss.models.Episode
import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.Season
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class CatalogTestDispatchers(d: CoroutineDispatcher) : AppDispatchers {
    override val default = d
    override val io = d
}

private class RecordingCatalogSource(private val bySeasons: Map<MediaId, List<Season>>) : EpisodeCatalogSource {
    val fetched = mutableListOf<MediaId>()
    override suspend fun fetch(mediaId: MediaId): Result<List<Season>> {
        fetched += mediaId
        return Result.success(bySeasons[mediaId].orEmpty())
    }
}

/**
 * [SqlDelightEpisodeCatalogStore] against a real database, plus the
 * read-through wired to it — the seam a widget's whole offline story rests on
 * (ADR 0015).
 */
class EpisodeCatalogStoreTest {

    private lateinit var driver: CountingDriver
    private lateinit var database: MuvissDatabase
    private lateinit var clock: FakeClock
    private lateinit var store: SqlDelightEpisodeCatalogStore

    private val got = MediaId.tmdbTv("1399")
    private val firefly = MediaId.tmdbTv("1437")

    private fun episode(show: MediaId, season: Int, number: Int, airDay: Long?, runtime: Int? = null) = Episode(
        id = EpisodeId(show, season, number),
        seasonNumber = season,
        episodeNumber = number,
        name = "S${season}E$number",
        airDateEpochDay = airDay,
        stillUrl = null,
        runtimeMinutes = runtime,
    )

    private fun seasonsOf(show: MediaId) = listOf(
        Season(1, "Season 1", listOf(episode(show, 1, 1, 50, runtime = 62), episode(show, 1, 2, 60))),
        Season(2, "Season 2", listOf(episode(show, 2, 1, 400, runtime = 55))),
    )

    @BeforeTest
    fun setUp() {
        driver = inMemoryDriver(::CountingDriver)
        database = MuvissDatabase(driver)
        clock = FakeClock(1_000L)
        store = SqlDelightEpisodeCatalogStore(database.episodeQueries, CatalogTestDispatchers(UnconfinedTestDispatcher()), clock)
    }

    @Test
    fun `a saved catalog round-trips field for field`() = runTest {
        store.save(got, seasonsOf(got))

        assertEquals(seasonsOf(got), store.load(listOf(got))[got])
    }

    @Test
    fun `seasons come back in show order regardless of insertion order`() = runTest {
        store.save(got, seasonsOf(got).reversed())

        val loaded = store.load(listOf(got))[got].orEmpty()
        assertEquals(listOf(1, 2), loaded.map { it.number })
        assertEquals(listOf(1, 2), loaded.first().episodes.map { it.episodeNumber })
    }

    @Test
    fun `an unknown title is absent rather than empty`() = runTest {
        store.save(got, seasonsOf(got))

        assertEquals(setOf(got), store.load(listOf(got, firefly)).keys)
    }

    @Test
    fun `saving again replaces the title's catalog wholesale`() = runTest {
        store.save(got, seasonsOf(got))
        clock.advanceTo(2_000L)

        store.save(got, listOf(Season(1, "Season 1", listOf(episode(got, 1, 1, 50, runtime = 62)))))

        val loaded = store.load(listOf(got))[got].orEmpty()
        assertEquals(1, loaded.size)
        assertEquals(1, loaded.single().episodes.size)
    }

    @Test
    fun `one title's catalog does not disturb another's`() = runTest {
        store.save(got, seasonsOf(got))
        store.save(firefly, seasonsOf(firefly))

        store.save(got, emptyList())

        assertEquals(seasonsOf(firefly), store.load(listOf(firefly))[firefly])
    }

    @Test
    fun `an undated episode keeps its null air date`() = runTest {
        store.save(got, listOf(Season(0, "Specials", listOf(episode(got, 0, 1, airDay = null)))))

        val special = store.load(listOf(got))[got]!!.single().episodes.single()
        assertEquals(null, special.airDateEpochDay)
        assertEquals(null, special.runtimeMinutes)
    }

    @Test
    fun `the cache reads the store instead of the source once a catalog is stored`() = runTest {
        val source = RecordingCatalogSource(mapOf(got to seasonsOf(got)))
        val cache = EpisodeCatalogCache(FetchEpisodeCatalogUseCase(source), store)

        cache.loadMissing(listOf(got))
        assertEquals(listOf(got), source.fetched)

        // A second cache stands in for the next app launch: memory is cold,
        // the database is not.
        val nextLaunch = EpisodeCatalogCache(FetchEpisodeCatalogUseCase(source), store)
        nextLaunch.loadMissing(listOf(got))

        assertEquals(listOf(got), source.fetched, "the catalog survived the process, so nothing had to be fetched again")
        assertEquals(seasonsOf(got), nextLaunch.catalogs.value[got])
    }

    /**
     * A deterministic budget rather than a timing assertion (there is no
     * benchmark infrastructure here): reading N titles costs N statements,
     * never one per episode. A per-episode fan-out is the regression that
     * would make a widget refresh visibly slow on a large library, and it is
     * exactly what a naive `Season`/`Episode` split into two tables would
     * have produced.
     */
    @Test
    fun `loading N titles costs N queries, not one per episode`() = runTest {
        val shows = (1..20).map { MediaId.tmdbTv(it.toString()) }
        shows.forEach { store.save(it, seasonsOf(it)) }
        driver.reset()

        val loaded = store.load(shows)

        assertEquals(20, loaded.size)
        val selects = driver.executedSql.filter { it.contains("FROM episode", ignoreCase = true) }
        assertEquals(20, selects.size, "one SELECT per title, whatever each one's episode count is")
        assertTrue(driver.executedSql.none { it.contains("FROM episode WHERE episodeId", ignoreCase = true) })
    }
}
