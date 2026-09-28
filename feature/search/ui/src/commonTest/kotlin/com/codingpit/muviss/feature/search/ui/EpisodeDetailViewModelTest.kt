@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.search.ui

import com.codingpit.muviss.feature.search.domain.EpisodeDetailUseCase
import com.codingpit.muviss.feature.search.domain.SearchRepository
import com.codingpit.muviss.models.EpisodeDetails
import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.Genre
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.MediaType
import com.codingpit.muviss.models.MetadataError
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

internal class FakeEpisodeRepo(private val result: Result<EpisodeDetails>) : SearchRepository {
    override suspend fun search(query: String, page: Int) = Result.success(PagedResult(emptyList<MediaSummary>(), page, page))
    override suspend fun trending() = Result.success(emptyList<MediaSummary>())
    override suspend fun details(id: MediaId) = Result.success(MediaDetails(MediaSummary(id, "t")))
    override suspend fun discover(type: MediaType, page: Int, genreId: String?) = Result.success(PagedResult(emptyList<MediaSummary>(), page, page))
    override suspend fun genres(type: MediaType) = Result.success(emptyList<Genre>())
    override suspend fun watchProviders(id: MediaId) = Result.success(WatchProviders())
    override suspend fun recommendations(id: MediaId, page: Int) = Result.success(PagedResult(emptyList<MediaSummary>(), 1, 1))
    override suspend fun similar(id: MediaId, page: Int) = Result.success(PagedResult(emptyList<MediaSummary>(), 1, 1))
    override suspend fun episodeDetails(episodeId: EpisodeId) = result
}

class EpisodeDetailViewModelTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val show = MediaId.tmdbTv("1399")
    private val episodeId = EpisodeId(show, 3, 9)
    private val details = EpisodeDetails(episodeId, "The Rains of Castamere", 3, 9, overview = "Robb visits the Twins.")

    private fun viewModel(
        result: Result<EpisodeDetails> = Result.success(details),
        progressApi: FakeProgressApi = FakeProgressApi(),
    ) = EpisodeDetailViewModel(episodeId, EpisodeDetailUseCase(FakeEpisodeRepo(result)), progressApi)

    @Test
    fun it_loads_the_episode() = runTest {
        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(details, vm.state.value.details)
        assertFalse(vm.state.value.loading)
    }

    @Test
    fun a_failure_surfaces_rather_than_showing_an_empty_page() = runTest {
        val vm = viewModel(result = Result.failure(MetadataError.NotFound()))
        advanceUntilIdle()

        assertEquals(MetadataError.NotFound().userMessage, vm.state.value.error)
    }

    @Test
    fun a_raw_exception_never_reaches_the_screen() = runTest {
        val vm = viewModel(result = Result.failure(IllegalStateException("Timeout for https://api.themoviedb.org/3/tv/1?api_key=SECRET")))
        advanceUntilIdle()

        assertEquals("Something went wrong", vm.state.value.error)
    }

    @Test
    fun it_shows_the_users_own_watch_history() = runTest {
        val progressApi = FakeProgressApi()
        val vm = viewModel(progressApi = progressApi)
        advanceUntilIdle()

        assertEquals(0, vm.state.value.playCount)
        assertFalse(vm.state.value.seen)

        vm.recordRewatch()
        vm.recordRewatch()
        advanceUntilIdle()

        assertEquals(2, vm.state.value.playCount)
        assertTrue(vm.state.value.seen)
    }

    @Test
    fun undoing_the_latest_viewing_keeps_the_ones_before_it() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        vm.recordRewatch()
        vm.recordRewatch()
        advanceUntilIdle()

        vm.undoLatestPlay()
        advanceUntilIdle()

        assertEquals(1, vm.state.value.playCount)
        assertTrue(vm.state.value.seen)
    }

    @Test
    fun clearing_the_history_forgets_every_viewing() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        vm.recordRewatch()
        vm.recordRewatch()
        vm.recordRewatch()
        advanceUntilIdle()

        vm.clearHistory()
        advanceUntilIdle()

        assertEquals(0, vm.state.value.playCount)
        assertFalse(vm.state.value.seen, "clearing history is what un-watching an episode outright means")
    }
}
