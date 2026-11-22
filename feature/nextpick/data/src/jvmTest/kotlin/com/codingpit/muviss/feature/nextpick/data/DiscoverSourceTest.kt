package com.codingpit.muviss.feature.nextpick.data

import com.codingpit.muviss.core.network.DiscoverFilters
import com.codingpit.muviss.core.network.MetadataProvider
import com.codingpit.muviss.feature.collection.api.CollectionApi
import com.codingpit.muviss.feature.nextpick.api.PickCriteria
import com.codingpit.muviss.feature.nextpick.api.PickSource
import com.codingpit.muviss.feature.nextpick.domain.PickContext
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.MediaType
import com.codingpit.muviss.models.MetadataError
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

    private val source = DiscoverSource(FailingCatalogue(), { emptyLibrary }, thisYear = { 2026 })

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
}
