package com.codingpit.muviss.feature.search.domain

import com.codingpit.muviss.models.EpisodeDetails
import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.Genre
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.MediaType
import com.codingpit.muviss.models.PagedResult
import com.codingpit.muviss.models.WatchProviders
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Serves one canned page for search and discover; everything else is unused here. */
private class PageRepository(private val canned: PagedResult<MediaSummary>) : SearchRepository {
    override suspend fun search(query: String, page: Int) = Result.success(canned)
    override suspend fun trending() = Result.success(emptyList<MediaSummary>())
    override suspend fun details(id: MediaId) = Result.success(MediaDetails(MediaSummary(id, "t")))
    override suspend fun discover(type: MediaType, page: Int, genreId: String?, excludedGenreIds: Set<String>) = Result.success(canned)
    override suspend fun genres(type: MediaType) = Result.success(emptyList<Genre>())
    override suspend fun watchProviders(id: MediaId) = Result.success(WatchProviders())
    override suspend fun recommendations(id: MediaId, page: Int) = Result.success(PagedResult(emptyList<MediaSummary>(), 1, 1))
    override suspend fun similar(id: MediaId, page: Int) = Result.success(PagedResult(emptyList<MediaSummary>(), 1, 1))
    override suspend fun episodeDetails(episodeId: EpisodeId) = Result.success(EpisodeDetails(episodeId, "Episode", 1, 1))
}

private fun movie(id: String) = MediaSummary(MediaId.tmdbMovie(id), "Movie $id")

class SearchUseCasesTest {

    @Test
    fun search_drops_ids_already_shown() = runTest {
        val repo = PageRepository(PagedResult(listOf(movie("1"), movie("2"), movie("3")), page = 2, totalPages = 5))

        val result = SearchMediaUseCase(repo)("matrix", page = 2, shown = setOf(MediaId.tmdbMovie("2"))).getOrThrow()

        assertEquals(listOf(movie("1"), movie("3")), result.items)
    }

    @Test
    fun search_drops_repeats_within_the_page_and_keeps_the_first() = runTest {
        val first = MediaSummary(MediaId.tmdbMovie("7"), "First copy")
        val second = MediaSummary(MediaId.tmdbMovie("7"), "Second copy")
        val repo = PageRepository(PagedResult(listOf(first, movie("8"), second), page = 1, totalPages = 1))

        val result = SearchMediaUseCase(repo)("matrix").getOrThrow()

        assertEquals(listOf(first, movie("8")), result.items)
    }

    @Test
    fun search_keeps_the_order_of_what_remains() = runTest {
        val repo = PageRepository(PagedResult(listOf(movie("5"), movie("1"), movie("9"), movie("2")), page = 2, totalPages = 3))

        val result = SearchMediaUseCase(repo)("matrix", page = 2, shown = setOf(MediaId.tmdbMovie("1"))).getOrThrow()

        assertEquals(listOf(movie("5"), movie("9"), movie("2")), result.items)
    }

    @Test
    fun search_keeps_the_paging_fields_when_a_page_comes_back_shorter() = runTest {
        val repo = PageRepository(PagedResult(listOf(movie("1"), movie("2")), page = 2, totalPages = 4))

        val result = SearchMediaUseCase(repo)("matrix", page = 2, shown = setOf(MediaId.tmdbMovie("2"))).getOrThrow()

        assertEquals(PagedResult(listOf(movie("1")), page = 2, totalPages = 4), result)
    }

    @Test
    fun discover_drops_ids_already_shown() = runTest {
        val repo = PageRepository(PagedResult(listOf(movie("1"), movie("2")), page = 2, totalPages = 9))

        val result = DiscoverMediaUseCase(repo)(MediaType.MOVIE, page = 2, shown = setOf(MediaId.tmdbMovie("1"))).getOrThrow()

        assertEquals(listOf(movie("2")), result.items)
    }

    @Test
    fun discover_drops_repeats_within_the_page() = runTest {
        val repo = PageRepository(PagedResult(listOf(movie("4"), movie("4"), movie("6")), page = 1, totalPages = 1))

        val result = DiscoverMediaUseCase(repo)(MediaType.MOVIE).getOrThrow()

        assertEquals(listOf(movie("4"), movie("6")), result.items)
    }
}
