@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.search.ui

import com.codingpit.muviss.core.common.connectivity.ConnectivityMonitor
import com.codingpit.muviss.feature.collection.api.CollectionSummary
import com.codingpit.muviss.feature.search.domain.DiscoverMediaUseCase
import com.codingpit.muviss.feature.search.domain.GenresUseCase
import com.codingpit.muviss.feature.search.domain.RecommendationsUseCase
import com.codingpit.muviss.feature.search.domain.SearchMediaUseCase
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.PagedResult
import com.codingpit.muviss.models.WatchStatus
import kotlinx.coroutines.CompletableDeferred
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

/**
 * The "For you" skeleton (#723) follows its own request, not Discover's. Discover can finish first and clear
 * `loading` while the recommendations are still on their way; the row must stay a placeholder until they land,
 * or the page reflows when they do.
 */
class SearchForYouSkeletonTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(repo: FakeRepo, collectionApi: FakeSearchCollectionApi) = SearchViewModel(
        SearchMediaUseCase(repo),
        DiscoverMediaUseCase(repo),
        GenresUseCase(repo),
        RecommendationsUseCase(repo),
        collectionApi,
        FakeTriageApi(),
        FakeMutingApi(),
        ConnectivityMonitor.AlwaysOnline,
    )

    private fun libraryTitle(id: MediaId, favorite: Boolean = false) = CollectionSummary(
        id,
        "Saved title",
        posterUrl = null,
        status = WatchStatus.NOT_STARTED,
        favorite = favorite,
        rating = null,
        addedAtEpochMs = 0L,
    )

    @Test
    fun for_you_skeleton_stays_up_until_recommendations_arrive_after_discover_has_loaded() = runTest {
        val seed = MediaId.tmdbMovie("1")
        val recommended = MediaSummary(MediaId.tmdbMovie("99"), "Recommended")
        val release = CompletableDeferred<Unit>()
        val vm = viewModel(
            FakeRepo(
                recommendationsResult = {
                    release.await()
                    Result.success(PagedResult(listOf(recommended), 1, 1))
                },
            ),
            FakeSearchCollectionApi(listOf(libraryTitle(seed, favorite = true))),
        )
        advanceUntilIdle()

        // Discover is done; the recommendations request is still waiting on the gate.
        assertFalse(vm.state.value.loading)
        assertTrue(vm.state.value.forYouSkeleton)
        assertTrue(vm.state.value.discoverSkeleton)

        release.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf(recommended), vm.state.value.forYou)
        assertFalse(vm.state.value.forYouSkeleton)
        assertFalse(vm.state.value.discoverSkeleton)
    }

    @Test
    fun for_you_skeleton_is_off_when_the_library_has_no_seed() = runTest {
        val vm = viewModel(FakeRepo(), FakeSearchCollectionApi(listOf(libraryTitle(MediaId.tmdbMovie("1")))))
        advanceUntilIdle()

        assertFalse(vm.state.value.hasForYouSeeds)
        assertFalse(vm.state.value.forYouSkeleton)
    }
}
