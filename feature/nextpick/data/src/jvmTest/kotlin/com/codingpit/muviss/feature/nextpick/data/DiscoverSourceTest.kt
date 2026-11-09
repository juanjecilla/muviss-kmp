package com.codingpit.muviss.feature.nextpick.data

import com.codingpit.muviss.core.network.DiscoverFilters
import com.codingpit.muviss.core.network.MetadataProvider
import com.codingpit.muviss.feature.collection.api.CollectionApi
import com.codingpit.muviss.feature.nextpick.api.PickCriteria
import com.codingpit.muviss.feature.nextpick.api.PickMood
import com.codingpit.muviss.feature.nextpick.api.PickSource
import com.codingpit.muviss.feature.nextpick.domain.PickContext
import com.codingpit.muviss.feature.nextpick.domain.PickMuting
import com.codingpit.muviss.feature.settings.api.TasteItem
import com.codingpit.muviss.feature.settings.api.TasteKind
import com.codingpit.muviss.models.GenreKey
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.MediaType
import com.codingpit.muviss.models.MetadataError
import com.codingpit.muviss.models.MutedGenres
import com.codingpit.muviss.models.PagedResult
import com.codingpit.muviss.models.SourceId
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DiscoverSourceTest {

    /** A catalogue whose `/discover` always fails the way TMDB does. */
    private class FailingCatalogue : MetadataProvider {
        override val source = SourceId.TMDB

        override suspend fun search(query: String, page: Int): PagedResult<MediaSummary> = error("unused")

        override suspend fun trending(): List<MediaSummary> = error("unused")

        override suspend fun details(id: MediaId): MediaDetails = error("unused")

        override suspend fun discoverFiltered(type: MediaType, page: Int, filters: DiscoverFilters): PagedResult<MediaSummary> = throw MetadataError.RateLimited()
    }

    /** Only `observeSummaries` is read; anything else is a test bug. */
    private val emptyLibrary = Proxy.newProxyInstance(CollectionApi::class.java.classLoader, arrayOf(CollectionApi::class.java)) { _, method, _ ->
        if (method.name == "observeSummaries") flowOf(emptyList<Any>()) else error("unused: ${method.name}")
    } as CollectionApi

    private val source = DiscoverSource(
        FailingCatalogue(),
        { emptyLibrary },
        thisYear = { 2026 },
        muting = PickMuting { MutedGenres.NONE },
        taste = { FakeTasteProfile() },
        region = { "US" },
    )

    @Test
    fun `a failed discover yields no candidates when either source is fine`() = runTest {
        assertEquals(emptyList(), source.gather(PickCriteria(source = PickSource.EITHER), PickContext(online = true)))
    }

    @Test
    fun `a failed discover still fails a session that asked for something new`() = runTest {
        assertFailsWith<MetadataError.RateLimited> {
            source.gather(PickCriteria(source = PickSource.SOMETHING_NEW), PickContext(online = true))
        }
    }

    /** Records each `/discover` request by type, so a test can read the filters TMDB would have received. */
    private class RecordingCatalogue : MetadataProvider {
        override val source = SourceId.TMDB
        val filters = mutableMapOf<MediaType, DiscoverFilters>()

        override suspend fun search(query: String, page: Int): PagedResult<MediaSummary> = error("unused")

        override suspend fun trending(): List<MediaSummary> = error("unused")

        override suspend fun details(id: MediaId): MediaDetails = error("unused")

        override suspend fun discoverFiltered(type: MediaType, page: Int, filters: DiscoverFilters): PagedResult<MediaSummary> {
            this.filters[type] = filters
            return PagedResult(emptyList(), page = page, totalPages = page)
        }
    }

    private suspend fun discoverWith(
        muted: MutedGenres = MutedGenres.NONE,
        services: List<Int> = emptyList(),
    ): RecordingCatalogue {
        val catalogue = RecordingCatalogue()
        val taste = services.map { TasteItem(TasteKind.SERVICE, it.toString(), "Service $it") }
        DiscoverSource(
            metadata = catalogue,
            collection = { emptyLibrary },
            thisYear = { 2026 },
            muting = PickMuting { muted },
            taste = { FakeTasteProfile(taste) },
            region = { "ES" },
        ).gather(PickCriteria(source = PickSource.EITHER, onlyMyServices = services.isNotEmpty()), PickContext(online = true))
        return catalogue
    }

    @Test
    fun `muted genres of each type go to discover as excludedGenreIds`() = runTest {
        val muted = MutedGenres(setOf(GenreKey(MediaType.MOVIE, "27"), GenreKey(MediaType.TV, "10759")))
        val catalogue = discoverWith(muted = muted)

        assertEquals(setOf("27"), catalogue.filters.getValue(MediaType.MOVIE).excludedGenreIds)
        assertEquals(setOf("10759"), catalogue.filters.getValue(MediaType.TV).excludedGenreIds)
    }

    @Test
    fun `a genre a mood picked is not excluded from discover`() = runTest {
        val muted = MutedGenres(setOf(GenreKey(MediaType.MOVIE, "35"), GenreKey(MediaType.MOVIE, "27")))
        val catalogue = RecordingCatalogue()
        DiscoverSource(catalogue, { emptyLibrary }, { 2026 }, PickMuting { muted }, { FakeTasteProfile() }, { "US" })
            .gather(PickCriteria(moods = setOf(PickMood.LIGHT)), PickContext(online = true))

        assertEquals(setOf("27"), catalogue.filters.getValue(MediaType.MOVIE).excludedGenreIds)
    }

    @Test
    fun `only my services sends the services and the region to discover`() = runTest {
        val catalogue = discoverWith(services = listOf(8, 337))

        val filters = catalogue.filters.getValue(MediaType.MOVIE)
        assertEquals(setOf(8, 337), filters.watchProviderIds)
        assertEquals("ES", filters.watchRegion)
    }

    @Test
    fun `without only my services discover is not narrowed by the services`() = runTest {
        val catalogue = RecordingCatalogue()
        DiscoverSource(catalogue, { emptyLibrary }, { 2026 }, PickMuting { MutedGenres.NONE }, { FakeTasteProfile(listOf(TasteItem(TasteKind.SERVICE, "8", "Netflix"))) }, { "ES" })
            .gather(PickCriteria(onlyMyServices = false), PickContext(online = true))

        assertEquals(emptySet(), catalogue.filters.getValue(MediaType.MOVIE).watchProviderIds)
        assertEquals(null, catalogue.filters.getValue(MediaType.MOVIE).watchRegion)
    }
}
