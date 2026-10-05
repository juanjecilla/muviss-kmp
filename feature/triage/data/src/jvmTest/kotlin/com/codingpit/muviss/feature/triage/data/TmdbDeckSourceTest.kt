package com.codingpit.muviss.feature.triage.data

import com.codingpit.muviss.core.network.MetadataProvider
import com.codingpit.muviss.core.network.MetadataProviderRegistry
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.MediaType
import com.codingpit.muviss.models.PagedResult
import com.codingpit.muviss.models.SourceId
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

private class FixedTotalPagesProvider(private val totalPages: Int) : MetadataProvider {
    override val source = SourceId.TMDB

    override suspend fun search(query: String, page: Int): PagedResult<MediaSummary> = error("unused")

    override suspend fun trending(): List<MediaSummary> = error("unused")

    override suspend fun details(id: MediaId): MediaDetails = error("unused")

    override suspend fun discover(type: MediaType, page: Int, genreId: String?): PagedResult<MediaSummary> = PagedResult(emptyList(), page = page, totalPages = totalPages)
}

class TmdbDeckSourceTest {

    private fun source(totalPages: Int, scheduler: TestCoroutineScheduler) = TmdbDeckSource(
        MetadataProviderRegistry(listOf(FixedTotalPagesProvider(totalPages))),
        ImmediateDispatchers(StandardTestDispatcher(scheduler)),
    )

    @Test
    fun `discover's reported page count is clamped to the last page TMDB will serve`() = runTest {
        // TMDB reports total_pages in the tens of thousands for /discover but
        // answers page 501 with a 422 — reading the raw count would walk the
        // deck into a load error instead of "caught up".
        val page = source(totalPages = 43_210, testScheduler).page(MediaType.MOVIE, 1, null).getOrThrow()

        assertEquals(TmdbDeckSource.MAX_DISCOVER_PAGE, page.totalPages)
    }

    @Test
    fun `a catalogue shorter than the ceiling keeps its own page count`() = runTest {
        val page = source(totalPages = 12, testScheduler).page(MediaType.TV, 1, null).getOrThrow()

        assertEquals(12, page.totalPages)
    }
}
