package com.codingpit.muviss.feature.search.ui

import com.codingpit.muviss.core.testing.FakeClock
import com.codingpit.muviss.feature.search.domain.MediaDetailUseCase
import com.codingpit.muviss.feature.search.domain.MoreLikeThisUseCase
import com.codingpit.muviss.feature.search.domain.RecommendationsUseCase
import com.codingpit.muviss.feature.search.domain.SimilarMediaUseCase
import com.codingpit.muviss.feature.search.domain.WatchProvidersUseCase
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.WatchStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Detail offers "What's next?" only on a Watched or Finished title (EPIC 51, #545). The status is the
 * library's own derived answer, read with the title's membership and never stored (#600). These tests
 * drive the membership and check what Detail sees.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DetailNextPickStatusTest {

    private val mediaId = MediaId.tmdbMovie("603")

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun a_watched_title_is_completed() {
        val collection = FakeCollectionApi().apply { setStatus(mediaId, WatchStatus.WATCHED) }

        val vm = viewModel(collection)

        assertEquals(WatchStatus.WATCHED, vm.state.value.watchStatus)
        assertTrue(vm.state.value.completed)
    }

    @Test
    fun a_finished_title_is_completed() {
        val collection = FakeCollectionApi().apply { setStatus(mediaId, WatchStatus.FINISHED) }

        assertTrue(viewModel(collection).state.value.completed)
    }

    @Test
    fun a_title_still_being_watched_is_not_completed() {
        val collection = FakeCollectionApi().apply { setStatus(mediaId, WatchStatus.WATCHING) }

        assertFalse(viewModel(collection).state.value.completed)
    }

    @Test
    fun a_title_not_started_is_not_completed() {
        val collection = FakeCollectionApi().apply { setStatus(mediaId, WatchStatus.NOT_STARTED) }

        assertFalse(viewModel(collection).state.value.completed)
    }

    @Test
    fun a_title_outside_the_library_has_no_status_and_is_not_completed() {
        val vm = viewModel(FakeCollectionApi())

        assertEquals(null, vm.state.value.watchStatus)
        assertFalse(vm.state.value.completed)
    }

    @Test
    fun a_title_that_becomes_watched_while_open_is_completed_without_reopening() {
        val collection = FakeCollectionApi().apply { setStatus(mediaId, WatchStatus.WATCHING) }
        val vm = viewModel(collection)
        assertFalse(vm.state.value.completed)

        collection.setStatus(mediaId, WatchStatus.WATCHED)

        assertTrue(vm.state.value.completed)
    }

    private fun viewModel(collection: FakeCollectionApi): DetailViewModel {
        val repo = FakeDetailRepo(details = MediaDetails(MediaSummary(mediaId, "The Matrix", year = 1999)))
        return DetailViewModel(
            mediaId,
            MediaDetailUseCase(repo),
            DetailPeers(collection, FakeProgressApi(), FakeTriageApi(), FakeMutingApi()),
            WatchProvidersUseCase(repo),
            MoreLikeThisUseCase(RecommendationsUseCase(repo), SimilarMediaUseCase(repo)),
            FakeClock(0L),
        )
    }
}
