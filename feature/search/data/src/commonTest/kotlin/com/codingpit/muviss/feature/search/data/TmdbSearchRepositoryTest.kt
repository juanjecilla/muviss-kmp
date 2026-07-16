@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.search.data

import com.codingpit.muviss.core.common.AppDispatchers
import com.codingpit.muviss.core.network.MetadataLocale
import com.codingpit.muviss.core.network.MetadataProvider
import com.codingpit.muviss.core.network.MetadataProviderRegistry
import com.codingpit.muviss.models.Genre
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.MediaType
import com.codingpit.muviss.models.PagedResult
import com.codingpit.muviss.models.SourceId
import com.codingpit.muviss.models.WatchProvider
import com.codingpit.muviss.models.WatchProviders
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Bundles [FakeProvider]'s EPIC 16 fakes into one constructor param, keeping its parameter count under detekt's LongParameterList threshold. */
private data class RecommendationFakes(
    val recommendationsResults: List<MediaSummary> = emptyList(),
    val similarResults: List<MediaSummary> = emptyList(),
)

private class FakeProvider(
    private val results: List<MediaSummary> = emptyList(),
    private val discoverResults: List<MediaSummary> = emptyList(),
    private val genreList: List<Genre> = emptyList(),
    private val providers: WatchProviders = WatchProviders(),
    private val recs: RecommendationFakes = RecommendationFakes(),
    private val failWith: Throwable? = null,
) : MetadataProvider {
    override val source: SourceId = SourceId.TMDB
    var lastWatchProvidersRegion: String? = null
    var lastDiscoverGenreId: String? = null
    var lastRecommendationsPage: Int? = null
    var lastSimilarPage: Int? = null

    override suspend fun search(query: String, page: Int): PagedResult<MediaSummary> {
        failWith?.let { throw it }
        return PagedResult(results, page = page, totalPages = page + 1)
    }

    override suspend fun trending(): List<MediaSummary> = results

    override suspend fun details(id: MediaId): MediaDetails = MediaDetails(MediaSummary(id, "Title"))

    override suspend fun discover(type: MediaType, page: Int, genreId: String?): PagedResult<MediaSummary> {
        lastDiscoverGenreId = genreId
        return PagedResult(discoverResults, page = page, totalPages = page + 1)
    }

    override suspend fun genres(type: MediaType): List<Genre> = genreList

    override suspend fun watchProviders(id: MediaId, region: String): WatchProviders {
        lastWatchProvidersRegion = region
        return providers
    }

    override suspend fun recommendations(id: MediaId, page: Int): PagedResult<MediaSummary> {
        failWith?.let { throw it }
        lastRecommendationsPage = page
        return PagedResult(recs.recommendationsResults, page = page, totalPages = page + 1)
    }

    override suspend fun similar(id: MediaId, page: Int): PagedResult<MediaSummary> {
        lastSimilarPage = page
        return PagedResult(recs.similarResults, page = page, totalPages = page + 1)
    }
}

private class TestDispatchers(d: CoroutineDispatcher) : AppDispatchers {
    override val default = d
    override val io = d
}

private class TestLocale(override val region: String = "ES", override val language: String = "en-US") : MetadataLocale

class TmdbSearchRepositoryTest {

    private fun repo(provider: MetadataProvider, locale: MetadataLocale = TestLocale()): TmdbSearchRepository = TmdbSearchRepository(
        MetadataProviderRegistry(listOf(provider)),
        TestDispatchers(UnconfinedTestDispatcher()),
        locale,
    )

    @Test
    fun search_returns_provider_results() = runTest {
        val summary = MediaSummary(MediaId.tmdbMovie("603"), "The Matrix", year = 1999)
        val result = repo(FakeProvider(results = listOf(summary))).search("matrix")
        assertTrue(result.isSuccess)
        assertEquals(listOf(summary), result.getOrThrow().items)
    }

    @Test
    fun search_wraps_provider_failure_in_result() = runTest {
        val result = repo(FakeProvider(failWith = IllegalStateException("boom"))).search("x")
        assertTrue(result.isFailure)
    }

    @Test
    fun search_passes_the_requested_page_through() = runTest {
        val result = repo(FakeProvider()).search("matrix", page = 2)
        assertEquals(2, result.getOrThrow().page)
        assertTrue(result.getOrThrow().hasMore)
    }

    @Test
    fun details_routes_by_media_id_source() = runTest {
        val id = MediaId.tmdbTv("1399")
        val result = repo(FakeProvider()).details(id)
        assertTrue(result.isSuccess)
        assertEquals(id, result.getOrThrow().id)
    }

    @Test
    fun discover_returns_provider_page_and_forwards_the_genre_filter() = runTest {
        val summary = MediaSummary(MediaId.tmdbMovie("27205"), "Inception")
        val provider = FakeProvider(discoverResults = listOf(summary))
        val result = repo(provider).discover(MediaType.MOVIE, page = 1, genreId = "878")

        assertTrue(result.isSuccess)
        assertEquals(listOf(summary), result.getOrThrow().items)
        assertEquals("878", provider.lastDiscoverGenreId)
    }

    @Test
    fun genres_returns_the_providers_genre_list() = runTest {
        val genres = listOf(Genre("28", "Action"))
        val result = repo(FakeProvider(genreList = genres)).genres(MediaType.MOVIE)
        assertEquals(genres, result.getOrThrow())
    }

    @Test
    fun watchProviders_uses_the_configured_locales_region() = runTest {
        val providers = WatchProviders(flatrate = listOf(WatchProvider("8", "Netflix")))
        val provider = FakeProvider(providers = providers)
        val result = repo(provider, locale = TestLocale(region = "FR")).watchProviders(MediaId.tmdbMovie("1"))

        assertEquals(providers, result.getOrThrow())
        assertEquals("FR", provider.lastWatchProvidersRegion)
    }

    @Test
    fun recommendations_routes_by_media_id_source_and_forwards_the_page() = runTest {
        val summary = MediaSummary(MediaId.tmdbMovie("27205"), "Inception")
        val provider = FakeProvider(recs = RecommendationFakes(recommendationsResults = listOf(summary)))
        val result = repo(provider).recommendations(MediaId.tmdbMovie("603"), page = 2)

        assertTrue(result.isSuccess)
        assertEquals(listOf(summary), result.getOrThrow().items)
        assertEquals(2, provider.lastRecommendationsPage)
    }

    @Test
    fun recommendations_wraps_provider_failure_in_result() = runTest {
        val result = repo(FakeProvider(failWith = IllegalStateException("boom"))).recommendations(MediaId.tmdbMovie("603"))
        assertTrue(result.isFailure)
    }

    @Test
    fun similar_routes_by_media_id_source_and_forwards_the_page() = runTest {
        val summary = MediaSummary(MediaId.tmdbTv("1399"), "Game of Thrones")
        val provider = FakeProvider(recs = RecommendationFakes(similarResults = listOf(summary)))
        val result = repo(provider).similar(MediaId.tmdbTv("1396"), page = 3)

        assertTrue(result.isSuccess)
        assertEquals(listOf(summary), result.getOrThrow().items)
        assertEquals(3, provider.lastSimilarPage)
    }
}
