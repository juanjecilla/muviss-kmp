@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.search.data

import com.codingpit.muviss.core.common.AppDispatchers
import com.codingpit.muviss.core.network.MetadataProvider
import com.codingpit.muviss.core.network.MetadataProviderRegistry
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.SourceId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class FakeProvider(
    override val source: SourceId = SourceId.TMDB,
    private val results: List<MediaSummary> = emptyList(),
    private val failWith: Throwable? = null,
) : MetadataProvider {
    override suspend fun search(query: String, page: Int): List<MediaSummary> {
        failWith?.let { throw it }
        return results
    }

    override suspend fun trending(): List<MediaSummary> = results

    override suspend fun details(id: MediaId): MediaDetails = MediaDetails(MediaSummary(id, "Title"))
}

private class TestDispatchers(d: CoroutineDispatcher) : AppDispatchers {
    override val default = d
    override val io = d
}

class TmdbSearchRepositoryTest {

    private fun repo(provider: MetadataProvider): TmdbSearchRepository = TmdbSearchRepository(
        MetadataProviderRegistry(listOf(provider)),
        TestDispatchers(UnconfinedTestDispatcher()),
    )

    @Test
    fun search_returns_provider_results() = runTest {
        val summary = MediaSummary(MediaId.tmdbMovie("603"), "The Matrix", year = 1999)
        val result = repo(FakeProvider(results = listOf(summary))).search("matrix")
        assertTrue(result.isSuccess)
        assertEquals(listOf(summary), result.getOrThrow())
    }

    @Test
    fun search_wraps_provider_failure_in_result() = runTest {
        val result = repo(FakeProvider(failWith = IllegalStateException("boom"))).search("x")
        assertTrue(result.isFailure)
    }

    @Test
    fun details_routes_by_media_id_source() = runTest {
        val id = MediaId.tmdbTv("1399")
        val result = repo(FakeProvider()).details(id)
        assertTrue(result.isSuccess)
        assertEquals(id, result.getOrThrow().id)
    }
}
