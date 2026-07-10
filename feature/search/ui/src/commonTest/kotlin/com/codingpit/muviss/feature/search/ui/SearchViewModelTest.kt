@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.search.ui

import app.cash.turbine.test
import com.codingpit.muviss.feature.search.domain.DiscoverMediaUseCase
import com.codingpit.muviss.feature.search.domain.GenresUseCase
import com.codingpit.muviss.feature.search.domain.SearchMediaUseCase
import com.codingpit.muviss.feature.search.domain.SearchRepository
import com.codingpit.muviss.models.Genre
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.MediaType
import com.codingpit.muviss.models.PagedResult
import com.codingpit.muviss.models.WatchProviders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class FakeRepo(
    private val searchResult: (page: Int) -> Result<PagedResult<MediaSummary>> = { Result.success(PagedResult(emptyList(), it, it)) },
    private val movieGenresResult: Result<List<Genre>> = Result.success(emptyList()),
    private val tvGenresResult: Result<List<Genre>> = Result.success(emptyList()),
    private val discoverResult: (type: MediaType, page: Int, genreId: String?) -> Result<PagedResult<MediaSummary>> =
        { _, page, _ -> Result.success(PagedResult(emptyList(), page, page)) },
) : SearchRepository {
    override suspend fun search(query: String, page: Int) = searchResult(page)
    override suspend fun trending() = Result.success(emptyList<MediaSummary>())
    override suspend fun details(id: MediaId) = Result.success(MediaDetails(MediaSummary(id, "t")))
    override suspend fun discover(type: MediaType, page: Int, genreId: String?) = discoverResult(type, page, genreId)
    override suspend fun genres(type: MediaType) = if (type == MediaType.MOVIE) movieGenresResult else tvGenresResult
    override suspend fun watchProviders(id: MediaId) = Result.success(WatchProviders())
}

class SearchViewModelTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(repo: FakeRepo) = SearchViewModel(SearchMediaUseCase(repo), DiscoverMediaUseCase(repo), GenresUseCase(repo))

    private val movieGenre = Genre("28", "Action")
    private val tvGenre = Genre("10759", "Action & Adventure")
    private val popularMovie = MediaSummary(MediaId.tmdbMovie("1"), "Popular movie")
    private val popularTv = MediaSummary(MediaId.tmdbTv("2"), "Popular show")
    private val searchItem = MediaSummary(MediaId.tmdbTv("3"), "Result")

    @Test
    fun loads_discover_browse_on_init() = runTest {
        val vm = viewModel(
            FakeRepo(
                movieGenresResult = Result.success(listOf(movieGenre)),
                tvGenresResult = Result.success(listOf(tvGenre)),
                discoverResult = { type, page, _ ->
                    Result.success(PagedResult(if (type == MediaType.MOVIE) listOf(popularMovie) else listOf(popularTv), page, page))
                },
            ),
        )
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(SearchMode.DISCOVER, state.mode)
        assertEquals(listOf(movieGenre), state.movieGenres)
        assertEquals(listOf(tvGenre), state.tvGenres)
        assertEquals(listOf(popularMovie), state.popularMovies)
        assertEquals(listOf(popularTv), state.popularTv)
    }

    @Test
    fun discover_failure_surfaces_error() = runTest {
        val vm = viewModel(FakeRepo(movieGenresResult = Result.failure(RuntimeException("net"))))
        vm.state.test {
            var current = awaitItem()
            while (current.error == null) current = awaitItem()
            assertEquals("net", current.error)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun query_runs_search_after_debounce_and_switches_mode() = runTest {
        val vm = viewModel(FakeRepo(searchResult = { Result.success(PagedResult(listOf(searchItem), 1, 1)) }))
        advanceUntilIdle()

        vm.onQueryChange("matrix")
        advanceUntilIdle()

        assertEquals(SearchMode.SEARCH_RESULTS, vm.state.value.mode)
        assertEquals(listOf(searchItem), vm.state.value.results)
    }

    @Test
    fun blank_query_returns_to_discover_mode() = runTest {
        val vm = viewModel(FakeRepo())
        advanceUntilIdle()
        vm.onQueryChange("matrix")
        advanceUntilIdle()

        vm.onQueryChange("")
        assertEquals(SearchMode.DISCOVER, vm.state.value.mode)
    }

    @Test
    fun loadMore_appends_the_next_search_page() = runTest {
        val vm = viewModel(
            FakeRepo(
                searchResult = { page ->
                    val item = MediaSummary(MediaId.tmdbMovie(page.toString()), "Page $page")
                    Result.success(PagedResult(listOf(item), page, totalPages = 2))
                },
            ),
        )
        advanceUntilIdle()
        vm.onQueryChange("matrix")
        advanceUntilIdle()

        vm.loadMore()
        advanceUntilIdle()

        assertEquals(2, vm.state.value.results.size)
        assertEquals(2, vm.state.value.resultsPage)
        assertFalse(vm.state.value.canLoadMoreResults)
    }

    @Test
    fun loadMore_is_a_noop_once_the_last_page_is_reached() = runTest {
        val vm = viewModel(FakeRepo(searchResult = { Result.success(PagedResult(listOf(searchItem), 1, 1)) }))
        advanceUntilIdle()
        vm.onQueryChange("matrix")
        advanceUntilIdle()

        vm.loadMore()
        advanceUntilIdle()

        assertEquals(listOf(searchItem), vm.state.value.results)
    }

    @Test
    fun selectGenre_switches_to_genre_browse_and_loads_the_first_page() = runTest {
        val genreResult = MediaSummary(MediaId.tmdbMovie("42"), "Genre result")
        val vm = viewModel(
            FakeRepo(discoverResult = { _, page, _ -> Result.success(PagedResult(listOf(genreResult), page, totalPages = 2)) }),
        )
        advanceUntilIdle()

        vm.selectGenre(movieGenre, MediaType.MOVIE)
        advanceUntilIdle()

        assertEquals(SearchMode.GENRE_BROWSE, vm.state.value.mode)
        assertEquals(movieGenre, vm.state.value.selectedGenre)
        assertEquals(listOf(genreResult), vm.state.value.genreResults)
        assertTrue(vm.state.value.canLoadMoreGenreResults)
    }

    @Test
    fun loadMore_in_genre_browse_appends_the_next_page() = runTest {
        val vm = viewModel(
            FakeRepo(
                discoverResult = { _, page, _ ->
                    val item = MediaSummary(MediaId.tmdbMovie(page.toString()), "Page $page")
                    Result.success(PagedResult(listOf(item), page, totalPages = 2))
                },
            ),
        )
        advanceUntilIdle()
        vm.selectGenre(movieGenre, MediaType.MOVIE)
        advanceUntilIdle()

        vm.loadMore()
        advanceUntilIdle()

        assertEquals(2, vm.state.value.genreResults.size)
        assertFalse(vm.state.value.canLoadMoreGenreResults)
    }

    @Test
    fun clearGenre_returns_to_discover_mode_and_drops_genre_results() = runTest {
        val vm = viewModel(
            FakeRepo(discoverResult = { _, page, _ -> Result.success(PagedResult(listOf(popularMovie), page, page)) }),
        )
        advanceUntilIdle()
        vm.selectGenre(movieGenre, MediaType.MOVIE)
        advanceUntilIdle()

        vm.clearGenre()

        assertEquals(SearchMode.DISCOVER, vm.state.value.mode)
        assertEquals(null, vm.state.value.selectedGenre)
        assertTrue(vm.state.value.genreResults.isEmpty())
    }
}
