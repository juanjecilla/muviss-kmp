@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.progress.data

import com.codingpit.muviss.core.common.AppDispatchers
import com.codingpit.muviss.core.network.MetadataProvider
import com.codingpit.muviss.core.network.MetadataProviderRegistry
import com.codingpit.muviss.models.Episode
import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.PagedResult
import com.codingpit.muviss.models.Season
import com.codingpit.muviss.models.SourceId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class FakeProvider(
    override val source: SourceId = SourceId.TMDB,
    private val seasons: List<Season> = emptyList(),
    private val failWith: Throwable? = null,
) : MetadataProvider {
    override suspend fun search(query: String, page: Int): PagedResult<MediaSummary> = PagedResult(emptyList(), 1, 1)
    override suspend fun trending(): List<MediaSummary> = emptyList()
    override suspend fun details(id: MediaId): MediaDetails {
        failWith?.let { throw it }
        return MediaDetails(MediaSummary(id, "Show"), seasons = seasons)
    }
}

private class TestDispatchers(d: CoroutineDispatcher) : AppDispatchers {
    override val default = d
    override val io = d
}

class RegistryEpisodeCatalogSourceTest {

    private fun source(provider: MetadataProvider) = RegistryEpisodeCatalogSource(
        MetadataProviderRegistry(listOf(provider)),
        TestDispatchers(UnconfinedTestDispatcher()),
    )

    @Test
    fun fetch_returns_the_shows_seasons() = runTest {
        val id = MediaId.tmdbTv("1399")
        val seasons = listOf(
            Season(1, "Season 1", listOf(Episode(EpisodeId(id, 1, 1), 1, 1, "Pilot"))),
        )
        val result = source(FakeProvider(seasons = seasons)).fetch(id)
        assertTrue(result.isSuccess)
        assertEquals(seasons, result.getOrThrow())
    }

    @Test
    fun fetch_wraps_provider_failure_in_result() = runTest {
        val result = source(FakeProvider(failWith = IllegalStateException("boom"))).fetch(MediaId.tmdbTv("1"))
        assertTrue(result.isFailure)
    }
}
