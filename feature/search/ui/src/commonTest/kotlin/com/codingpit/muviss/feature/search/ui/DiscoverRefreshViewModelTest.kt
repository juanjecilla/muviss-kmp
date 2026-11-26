@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.search.ui

import com.codingpit.muviss.core.testing.FakeClock
import com.codingpit.muviss.feature.collection.api.CollectionSummary
import com.codingpit.muviss.feature.search.domain.DiscoverFeed
import com.codingpit.muviss.feature.search.domain.DiscoverMediaUseCase
import com.codingpit.muviss.feature.search.domain.NoDiscoverCache
import com.codingpit.muviss.feature.search.domain.RecommendationsUseCase
import com.codingpit.muviss.feature.search.domain.SearchMediaUseCase
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.MetadataError
import com.codingpit.muviss.models.PagedResult
import com.codingpit.muviss.models.WatchStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * #965: the refresh control, the pull and Ctrl/Cmd+R all call [SearchViewModel.refresh]. A refresh asks TMDB for the
 * shelves and For you, and a second one is refused while the first is in flight, so one press never asks twice.
 */
class DiscoverRefreshViewModelTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val seed = MediaId.tmdbMovie("1")
    private val recommended = MediaSummary(MediaId.tmdbMovie("50"), "Recommended")
    private val library = listOf(
        CollectionSummary(seed, "Saved", posterUrl = null, status = WatchStatus.NOT_STARTED, favorite = true, addedAtEpochMs = 0L),
    )

    /** When set, a For you request waits on it: the test sees the request in flight, then releases it. */
    private var holdForYou: CompletableDeferred<Unit>? = null

    /** When set, every For you request fails, as it would offline. */
    private var forYouFails = false

    /** Every For you request, by seed. */
    private val forYouAsks = mutableListOf<MediaId>()

    private fun repo() = FakeRepo(
        searchResult = { Result.success(PagedResult(listOf(MediaSummary(MediaId.tmdbMovie("77"), "Dune")), 1, 1)) },
        recommendationsResult = { id ->
            forYouAsks += id
            holdForYou?.await()
            if (forYouFails) Result.failure(MetadataError.Offline()) else Result.success(PagedResult(listOf(recommended), 1, 1))
        },
    )

    private fun viewModel(repo: FakeRepo) = SearchViewModel(
        SearchMediaUseCase(repo),
        DiscoverMediaUseCase(repo),
        RecommendationsUseCase(repo),
        FakeSearchCollectionApi(library),
        FakeTriageApi(),
        FakeMutingApi(),
        DiscoverFeed(repo, NoDiscoverCache, FakeClock(epochMs = 1_000L)),
    )

    @Test
    fun refresh_asks_tmdb_for_the_shelves_and_for_you_again() = runTest {
        val repo = repo()
        val vm = viewModel(repo)
        advanceUntilIdle()
        val forYouBefore = forYouAsks.size
        val shelvesBefore = repo.discoverExcluded.size

        vm.refresh()
        advanceUntilIdle()

        assertEquals(forYouBefore + 1, forYouAsks.size, "the refresh asks TMDB for the one For you seed again")
        assertTrue(repo.discoverExcluded.size > shelvesBefore, "the refresh asks TMDB for the shelves again")
        assertFalse(vm.state.value.refreshing, "nothing is in flight once the refresh has landed")
    }

    @Test
    fun a_refresh_is_refused_while_another_is_in_flight() = runTest {
        val repo = repo()
        val vm = viewModel(repo)
        advanceUntilIdle()
        val hold = CompletableDeferred<Unit>()
        holdForYou = hold

        vm.refresh()
        runCurrent()
        assertTrue(vm.state.value.refreshing, "the held For you request is in flight")
        val asksWhileHeld = forYouAsks.size
        val shelvesWhileHeld = repo.discoverExcluded.size

        vm.refresh()
        vm.retry()
        runCurrent()

        assertEquals(asksWhileHeld, forYouAsks.size, "a second refresh must not ask For you again")
        assertEquals(shelvesWhileHeld, repo.discoverExcluded.size, "a second refresh must not ask for the shelves again")

        holdForYou = null
        hold.complete(Unit)
        advanceUntilIdle()
        assertFalse(vm.state.value.refreshing)
    }

    @Test
    fun refreshing_clears_when_the_refresh_fails() = runTest {
        val repo = repo()
        val vm = viewModel(repo)
        advanceUntilIdle()
        forYouFails = true

        vm.refresh()
        advanceUntilIdle()

        assertFalse(vm.state.value.refreshing, "a failed refresh must not leave the control disabled")
    }

    @Test
    fun a_refresh_with_a_query_typed_neither_cancels_the_search_nor_reloads_discover() = runTest {
        val repo = repo()
        val vm = viewModel(repo)
        advanceUntilIdle()
        val shelvesBefore = repo.discoverExcluded.size

        vm.onQueryChange("dune")
        vm.refresh()
        assertEquals(shelvesBefore, repo.discoverExcluded.size, "a refresh must not reload Discover under a typed query")

        advanceUntilIdle()
        assertEquals(SearchMode.SEARCH_RESULTS, vm.state.value.mode, "the pending search still runs")
        assertEquals(listOf("Dune"), vm.state.value.results.map { it.title })
    }

    @Test
    fun a_refresh_on_search_results_asks_nothing() = runTest {
        val repo = repo()
        val vm = viewModel(repo)
        advanceUntilIdle()
        vm.onQueryChange("dune")
        advanceUntilIdle()
        val asks = forYouAsks.size
        val shelves = repo.discoverExcluded.size

        vm.refresh()
        advanceUntilIdle()

        assertEquals(asks, forYouAsks.size)
        assertEquals(shelves, repo.discoverExcluded.size)
    }
}
