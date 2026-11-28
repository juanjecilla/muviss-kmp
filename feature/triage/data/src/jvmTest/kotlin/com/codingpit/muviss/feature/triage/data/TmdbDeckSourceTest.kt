package com.codingpit.muviss.feature.triage.data

import com.codingpit.muviss.core.network.MetadataProvider
import com.codingpit.muviss.core.network.MetadataProviderRegistry
import com.codingpit.muviss.models.Genre
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.MediaType
import com.codingpit.muviss.models.PagedResult
import com.codingpit.muviss.models.SourceId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FixedTotalPagesProvider(private val totalPages: Int) : MetadataProvider {
    override val source = SourceId.TMDB

    override suspend fun search(query: String, page: Int): PagedResult<MediaSummary> = error("unused")

    override suspend fun trending(): List<MediaSummary> = error("unused")

    override suspend fun details(id: MediaId): MediaDetails = error("unused")

    override suspend fun discover(type: MediaType, page: Int, genreId: String?, excludedGenreIds: Set<String>, fresh: Boolean): PagedResult<MediaSummary> = PagedResult(emptyList(), page = page, totalPages = totalPages)
}

private class SuspendedProvider : MetadataProvider {
    override val source = SourceId.TMDB

    override suspend fun search(query: String, page: Int): PagedResult<MediaSummary> = error("unused")

    override suspend fun trending(): List<MediaSummary> = error("unused")

    override suspend fun details(id: MediaId): MediaDetails = error("unused")

    override suspend fun discover(type: MediaType, page: Int, genreId: String?, excludedGenreIds: Set<String>, fresh: Boolean): PagedResult<MediaSummary> = awaitCancellation()

    override suspend fun genres(type: MediaType, fresh: Boolean): List<Genre> = awaitCancellation()
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

    @Test
    fun a_cancelled_page_load_propagates_the_cancellation_instead_of_returning_a_failure() = runTest {
        // #1087: runCatching would wrap the CancellationException as a Result failure, and the deck would show a load
        // error for a load the person abandoned.
        val source = TmdbDeckSource(MetadataProviderRegistry(listOf(SuspendedProvider())), ImmediateDispatchers(StandardTestDispatcher(testScheduler)))
        var outcome: Result<*>? = null
        val job = launch { outcome = source.page(MediaType.MOVIE, 1, null) }
        runCurrent()
        job.cancel()
        job.join()

        assertNull(outcome, "a cancelled page load must throw the cancellation, not return a Result (#1087)")
    }

    @Test
    fun a_cancelled_genre_load_propagates_the_cancellation_instead_of_returning_a_failure() = runTest {
        val source = TmdbDeckSource(MetadataProviderRegistry(listOf(SuspendedProvider())), ImmediateDispatchers(StandardTestDispatcher(testScheduler)))
        var outcome: Result<*>? = null
        val job = launch { outcome = source.genres(MediaType.TV) }
        runCurrent()
        job.cancel()
        job.join()

        assertNull(outcome, "a cancelled genre load must throw the cancellation, not return a Result (#1087)")
    }

    @Test
    fun a_cancellation_from_an_inner_source_while_the_caller_is_alive_is_a_failure() = runTest {
        // #1079's rule, kept for the deck: the retry UI must still see it, so only a cancelled caller rethrows.
        val inner = object : MetadataProvider by SuspendedProvider() {
            override suspend fun discover(type: MediaType, page: Int, genreId: String?, excludedGenreIds: Set<String>, fresh: Boolean): PagedResult<MediaSummary> = throw CancellationException("inner source cancelled")
        }
        val source = TmdbDeckSource(MetadataProviderRegistry(listOf(inner)), ImmediateDispatchers(StandardTestDispatcher(testScheduler)))

        val result = source.page(MediaType.MOVIE, 1, null)

        assertTrue(result.isFailure, "the caller is not cancelled, so the failure is returned for the retry UI (#1087)")
    }
}
