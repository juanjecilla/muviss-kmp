package com.codingpit.muviss.feature.progress.domain

import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.Season
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

private class FakeEpisodeCatalogSource : EpisodeCatalogSource {
    val fetchCount = mutableMapOf<MediaId, Int>()
    var seasonsById: Map<MediaId, List<Season>> = emptyMap()

    override suspend fun fetch(mediaId: MediaId): Result<List<Season>> {
        fetchCount[mediaId] = (fetchCount[mediaId] ?: 0) + 1
        return Result.success(seasonsById[mediaId].orEmpty())
    }
}

class EpisodeCatalogCacheTest {

    private val show1 = MediaId.tmdbTv("1")
    private val show2 = MediaId.tmdbTv("2")

    @Test
    fun loadMissing_fetches_only_ids_not_already_cached() = runTest {
        val source = FakeEpisodeCatalogSource()
        val cache = EpisodeCatalogCache(FetchEpisodeCatalogUseCase(source))

        cache.loadMissing(listOf(show1))
        cache.loadMissing(listOf(show1, show2))

        assertEquals(1, source.fetchCount[show1])
        assertEquals(1, source.fetchCount[show2])
    }

    @Test
    fun loadMissing_populates_the_catalogs_flow() = runTest {
        val source = FakeEpisodeCatalogSource()
        val seasons = listOf(Season(1, "Season 1", emptyList()))
        source.seasonsById = mapOf(show1 to seasons)
        val cache = EpisodeCatalogCache(FetchEpisodeCatalogUseCase(source))

        cache.loadMissing(listOf(show1))

        assertEquals(seasons, cache.catalogs.value[show1])
    }

    @Test
    fun refresh_refetches_every_currently_cached_id_by_default() = runTest {
        val source = FakeEpisodeCatalogSource()
        val cache = EpisodeCatalogCache(FetchEpisodeCatalogUseCase(source))
        cache.loadMissing(listOf(show1, show2))

        cache.refresh()

        assertEquals(2, source.fetchCount[show1])
        assertEquals(2, source.fetchCount[show2])
    }

    @Test
    fun refresh_with_explicit_ids_only_refetches_those() = runTest {
        val source = FakeEpisodeCatalogSource()
        val cache = EpisodeCatalogCache(FetchEpisodeCatalogUseCase(source))
        cache.loadMissing(listOf(show1, show2))

        cache.refresh(listOf(show1))

        assertEquals(2, source.fetchCount[show1])
        assertEquals(1, source.fetchCount[show2])
    }
}
