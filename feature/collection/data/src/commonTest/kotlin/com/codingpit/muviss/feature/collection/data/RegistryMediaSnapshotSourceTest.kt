@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.collection.data

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
    private val failWith: Throwable? = null,
) : MetadataProvider {
    override suspend fun search(query: String, page: Int): List<MediaSummary> = emptyList()
    override suspend fun trending(): List<MediaSummary> = emptyList()
    override suspend fun details(id: MediaId): MediaDetails {
        failWith?.let { throw it }
        return MediaDetails(MediaSummary(id, "Refreshed"))
    }
}

private class TestDispatchers(d: CoroutineDispatcher) : AppDispatchers {
    override val default = d
    override val io = d
}

class RegistryMediaSnapshotSourceTest {

    private fun source(provider: MetadataProvider) = RegistryMediaSnapshotSource(
        MetadataProviderRegistry(listOf(provider)),
        TestDispatchers(UnconfinedTestDispatcher()),
    )

    @Test
    fun fetch_routes_by_media_id_source_and_returns_details() = runTest {
        val id = MediaId.tmdbTv("1399")
        val result = source(FakeProvider()).fetch(id)
        assertTrue(result.isSuccess)
        assertEquals("Refreshed", result.getOrThrow().summary.title)
    }

    @Test
    fun fetch_wraps_provider_failure_in_result() = runTest {
        val result = source(FakeProvider(failWith = IllegalStateException("boom"))).fetch(MediaId.tmdbMovie("1"))
        assertTrue(result.isFailure)
    }
}
