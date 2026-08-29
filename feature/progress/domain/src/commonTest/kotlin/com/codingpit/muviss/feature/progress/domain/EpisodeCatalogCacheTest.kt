package com.codingpit.muviss.feature.progress.domain

import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.Season
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private class FakeEpisodeCatalogSource : EpisodeCatalogSource {
    val fetchCount = mutableMapOf<MediaId, Int>()
    var seasonsById: Map<MediaId, List<Season>> = emptyMap()

    var failing: Boolean = false

    override suspend fun fetch(mediaId: MediaId): Result<List<Season>> {
        fetchCount[mediaId] = (fetchCount[mediaId] ?: 0) + 1
        return if (failing) Result.failure(IllegalStateException("offline")) else Result.success(seasonsById[mediaId].orEmpty())
    }
}

class EpisodeCatalogCacheTest {

    private val show1 = MediaId.tmdbTv("1")
    private val show2 = MediaId.tmdbTv("2")

    @Test
    fun loadMissing_fetches_only_ids_not_already_cached() = runTest {
        val source = FakeEpisodeCatalogSource()
        val store = InMemoryEpisodeCatalogStore()
        val cache = EpisodeCatalogCache(FetchEpisodeCatalogUseCase(source), store)

        cache.loadMissing(listOf(show1))
        cache.loadMissing(listOf(show1, show2))

        assertEquals(1, source.fetchCount[show1])
        assertEquals(1, source.fetchCount[show2])
    }

    @Test
    fun loadMissing_populates_the_catalogs_flow() = runTest {
        val source = FakeEpisodeCatalogSource()
        val store = InMemoryEpisodeCatalogStore()
        val seasons = listOf(Season(1, "Season 1", emptyList()))
        source.seasonsById = mapOf(show1 to seasons)
        val cache = EpisodeCatalogCache(FetchEpisodeCatalogUseCase(source), store)

        cache.loadMissing(listOf(show1))

        assertEquals(seasons, cache.catalogs.value[show1])
    }

    @Test
    fun refresh_refetches_every_currently_cached_id_by_default() = runTest {
        val source = FakeEpisodeCatalogSource()
        val store = InMemoryEpisodeCatalogStore()
        val cache = EpisodeCatalogCache(FetchEpisodeCatalogUseCase(source), store)
        cache.loadMissing(listOf(show1, show2))

        cache.refresh()

        assertEquals(2, source.fetchCount[show1])
        assertEquals(2, source.fetchCount[show2])
    }

    @Test
    fun refresh_with_explicit_ids_only_refetches_those() = runTest {
        val source = FakeEpisodeCatalogSource()
        val store = InMemoryEpisodeCatalogStore()
        val cache = EpisodeCatalogCache(FetchEpisodeCatalogUseCase(source), store)
        cache.loadMissing(listOf(show1, show2))

        cache.refresh(listOf(show1))

        assertEquals(2, source.fetchCount[show1])
        assertEquals(1, source.fetchCount[show2])
    }

    @Test
    fun loadMissing_reads_the_store_before_the_network() = runTest {
        val source = FakeEpisodeCatalogSource()
        val seasons = listOf(Season(1, "Season 1", emptyList()))
        val store = InMemoryEpisodeCatalogStore(mapOf(show1 to seasons))
        val cache = EpisodeCatalogCache(FetchEpisodeCatalogUseCase(source), store)

        cache.loadMissing(listOf(show1))

        assertEquals(seasons, cache.catalogs.value[show1])
        assertNull(source.fetchCount[show1])
    }

    @Test
    fun loadMissing_writes_through_what_it_had_to_fetch() = runTest {
        val source = FakeEpisodeCatalogSource()
        val seasons = listOf(Season(1, "Season 1", emptyList()))
        source.seasonsById = mapOf(show1 to seasons)
        val store = InMemoryEpisodeCatalogStore()
        val cache = EpisodeCatalogCache(FetchEpisodeCatalogUseCase(source), store)

        cache.loadMissing(listOf(show1))

        assertEquals(seasons, store.stored[show1])
    }

    @Test
    fun loadMissing_fetches_only_the_ids_the_store_could_not_answer() = runTest {
        val source = FakeEpisodeCatalogSource()
        val store = InMemoryEpisodeCatalogStore(mapOf(show1 to listOf(Season(1, "Season 1", emptyList()))))
        val cache = EpisodeCatalogCache(FetchEpisodeCatalogUseCase(source), store)

        cache.loadMissing(listOf(show1, show2))

        assertNull(source.fetchCount[show1])
        assertEquals(1, source.fetchCount[show2])
    }

    @Test
    fun refresh_overwrites_what_is_stored() = runTest {
        val source = FakeEpisodeCatalogSource()
        val stale = listOf(Season(1, "Season 1", emptyList()))
        val fresh = listOf(Season(1, "Season 1", emptyList()), Season(2, "Season 2", emptyList()))
        source.seasonsById = mapOf(show1 to fresh)
        val store = InMemoryEpisodeCatalogStore(mapOf(show1 to stale))
        val cache = EpisodeCatalogCache(FetchEpisodeCatalogUseCase(source), store)
        cache.loadMissing(listOf(show1))

        cache.refresh(listOf(show1))

        assertEquals(fresh, store.stored[show1])
        assertEquals(fresh, cache.catalogs.value[show1])
    }

    /**
     * A stale catalog names a real episode; an absent one names nothing. A
     * widget that went blank because the network was unavailable would be
     * worse than one naming yesterday's next episode.
     */
    @Test
    fun a_failed_refresh_leaves_the_stored_catalog_alone() = runTest {
        val source = FakeEpisodeCatalogSource()
        val stored = listOf(Season(1, "Season 1", emptyList()))
        val store = InMemoryEpisodeCatalogStore(mapOf(show1 to stored))
        val cache = EpisodeCatalogCache(FetchEpisodeCatalogUseCase(source), store)
        cache.loadMissing(listOf(show1))
        source.failing = true

        cache.refresh(listOf(show1))

        assertEquals(stored, store.stored[show1])
        assertEquals(stored, cache.catalogs.value[show1])
    }
}
