package com.codingpit.muviss.feature.cowatch.data

import com.codingpit.muviss.core.common.AppDispatchers
import com.codingpit.muviss.core.network.MetadataLocale
import com.codingpit.muviss.core.network.MetadataProvider
import com.codingpit.muviss.core.network.MetadataProviderRegistry
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.PagedResult
import com.codingpit.muviss.models.SourceId
import com.codingpit.muviss.models.WatchProviders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

private class SourceTestDispatchers(d: CoroutineDispatcher) : AppDispatchers {
    override val default = d
    override val io = d
}

private class SourceTestLocale : MetadataLocale {
    override val region: String = "ES"
    override val language: String = "en-US"
}

/** A provider whose only reachable method is [watchProviders]; anything else is not part of this lookup. */
private class WatchProvidersOnlyProvider(
    private val onWatchProviders: suspend () -> WatchProviders,
) : MetadataProvider {
    override val source: SourceId = SourceId.TMDB

    override suspend fun search(query: String, page: Int): PagedResult<MediaSummary> = error("not part of this lookup")

    override suspend fun trending(): List<MediaSummary> = error("not part of this lookup")

    override suspend fun details(id: MediaId): MediaDetails = error("not part of this lookup")

    override suspend fun watchProviders(id: MediaId, region: String): WatchProviders = onWatchProviders()
}

class TmdbWatchProviderSourceTest {

    private val movie = MediaId.tmdbMovie("1")

    private fun sourceWith(provider: MetadataProvider) = TmdbWatchProviderSource(
        registry = MetadataProviderRegistry(listOf(provider)),
        locale = SourceTestLocale(),
        dispatchers = SourceTestDispatchers(UnconfinedTestDispatcher()),
    )

    @Test
    fun `a cancelled lookup propagates the cancellation instead of returning it as a failure`() = runTest {
        val source = sourceWith(WatchProvidersOnlyProvider { throw CancellationException("navigated away") })

        // A Result.failure here would let the Next Pick fill keep requesting until its next suspension point.
        assertFailsWith<CancellationException> { source.fetchFlatrateIds(movie, "US") }
    }

    @Test
    fun `an ordinary lookup failure still comes back as a failed Result`() = runTest {
        val source = sourceWith(WatchProvidersOnlyProvider { throw IllegalStateException("boom") })

        val result = source.fetchFlatrateIds(movie, "US")

        assertTrue(result.isFailure)
        assertIs<IllegalStateException>(result.exceptionOrNull())
    }
}
