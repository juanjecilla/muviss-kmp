@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.search.ui

import app.cash.turbine.test
import com.codingpit.muviss.feature.search.domain.SearchMediaUseCase
import com.codingpit.muviss.feature.search.domain.SearchRepository
import com.codingpit.muviss.feature.search.domain.TrendingUseCase
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
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
    private val searchResult: Result<List<MediaSummary>> = Result.success(emptyList()),
    private val trendingResult: Result<List<MediaSummary>> = Result.success(emptyList()),
) : SearchRepository {
    override suspend fun search(query: String) = searchResult
    override suspend fun trending() = trendingResult
    override suspend fun details(id: MediaId) = Result.success(MediaDetails(MediaSummary(id, "t")))
}

class SearchViewModelTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(repo: FakeRepo) = SearchViewModel(SearchMediaUseCase(repo), TrendingUseCase(repo))

    private val trendingItem = MediaSummary(MediaId.tmdbMovie("1"), "Trending")
    private val searchItem = MediaSummary(MediaId.tmdbTv("2"), "Result")

    @Test
    fun loads_trending_on_init() = runTest {
        val vm = viewModel(FakeRepo(trendingResult = Result.success(listOf(trendingItem))))
        advanceUntilIdle()
        assertEquals(listOf(trendingItem), vm.state.value.results)
        assertTrue(vm.state.value.showingTrending)
    }

    @Test
    fun query_runs_search_after_debounce() = runTest {
        val vm = viewModel(
            FakeRepo(
                searchResult = Result.success(listOf(searchItem)),
                trendingResult = Result.success(listOf(trendingItem)),
            ),
        )
        advanceUntilIdle()
        vm.onQueryChange("matrix")
        advanceUntilIdle()
        assertEquals(listOf(searchItem), vm.state.value.results)
        assertFalse(vm.state.value.showingTrending)
    }

    @Test
    fun failure_surfaces_error_via_flow() = runTest {
        val vm = viewModel(FakeRepo(trendingResult = Result.failure(RuntimeException("net"))))
        vm.state.test {
            // StateFlow replays; drain until the error lands.
            var current = awaitItem()
            while (current.error == null) current = awaitItem()
            assertEquals("net", current.error)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
