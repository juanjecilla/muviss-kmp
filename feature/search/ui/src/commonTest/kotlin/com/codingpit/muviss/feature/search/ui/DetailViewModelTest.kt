@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.search.ui

import com.codingpit.muviss.feature.collection.api.CollectionApi
import com.codingpit.muviss.feature.collection.api.CollectionMembership
import com.codingpit.muviss.feature.collection.api.CollectionSummary
import com.codingpit.muviss.feature.progress.api.ProgressApi
import com.codingpit.muviss.feature.search.domain.MediaDetailUseCase
import com.codingpit.muviss.feature.search.domain.SearchRepository
import com.codingpit.muviss.models.Episode
import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.Season
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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

private class FakeDetailRepo(private val details: MediaDetails) : SearchRepository {
    override suspend fun search(query: String) = Result.success(emptyList<MediaSummary>())
    override suspend fun trending() = Result.success(emptyList<MediaSummary>())
    override suspend fun details(id: MediaId) = Result.success(details)
}

private class FakeCollectionApi : CollectionApi {
    private val membership = MutableStateFlow<CollectionMembership?>(null)
    val added = mutableListOf<MediaId>()
    val removed = mutableListOf<MediaId>()

    override fun observeMembership(mediaId: MediaId): Flow<CollectionMembership?> = membership
    override fun observeSummaries(): Flow<List<CollectionSummary>> = error("not used")

    override suspend fun add(details: MediaDetails) {
        added += details.summary.id
        membership.value = CollectionMembership(details.summary.id, favorite = false)
    }

    override suspend fun remove(mediaId: MediaId) {
        removed += mediaId
        membership.value = null
    }

    override suspend fun setFavorite(mediaId: MediaId, favorite: Boolean) {
        membership.value = membership.value?.copy(favorite = favorite)
    }
}

private class FakeProgressApi : ProgressApi {
    private val seen = MutableStateFlow<Set<EpisodeId>>(emptySet())
    val markedSeasons = mutableListOf<Season>()
    val markedPrevious = mutableListOf<EpisodeId>()

    override fun observeSeenEpisodes(mediaId: MediaId): Flow<Set<EpisodeId>> = seen

    override suspend fun setEpisodeSeen(episodeId: EpisodeId, seen: Boolean) {
        this.seen.value = if (seen) this.seen.value + episodeId else this.seen.value - episodeId
    }

    override suspend fun markSeasonSeen(season: Season) {
        markedSeasons += season
        seen.value = seen.value + season.episodes.map { it.id }
    }

    override suspend fun markPreviousSeen(seasons: List<Season>, target: EpisodeId) {
        markedPrevious += target
    }

    override suspend fun setMovieWatched(mediaId: MediaId, watched: Boolean) {
        val id = EpisodeId.forMovie(mediaId)
        seen.value = if (watched) seen.value + id else seen.value - id
    }
}

class DetailViewModelTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val mediaId = MediaId.tmdbMovie("603")
    private val details = MediaDetails(MediaSummary(mediaId, "The Matrix"))

    private val show = MediaId.tmdbTv("1399")
    private val episode1 = Episode(EpisodeId(show, 1, 1), 1, 1, "Winter Is Coming")
    private val episode2 = Episode(EpisodeId(show, 1, 2), 1, 2, "The Kingsroad")
    private val tvDetails = MediaDetails(
        summary = MediaSummary(show, "Game of Thrones"),
        seasons = listOf(Season(1, "Season 1", listOf(episode1, episode2))),
    )

    private fun viewModel(
        collectionApi: FakeCollectionApi = FakeCollectionApi(),
        progressApi: FakeProgressApi = FakeProgressApi(),
        detailsToLoad: MediaDetails = details,
        id: MediaId = mediaId,
    ) = DetailViewModel(id, MediaDetailUseCase(FakeDetailRepo(detailsToLoad)), collectionApi, progressApi)

    @Test
    fun starts_unsaved_and_unfavorited() = runTest {
        val vm = viewModel()
        advanceUntilIdle()

        assertFalse(vm.state.value.saved)
        assertFalse(vm.state.value.favorite)
    }

    @Test
    fun toggleSaved_adds_the_loaded_details_when_not_saved() = runTest {
        val api = FakeCollectionApi()
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.toggleSaved()
        advanceUntilIdle()

        assertEquals(listOf(mediaId), api.added)
        assertTrue(vm.state.value.saved)
    }

    @Test
    fun toggleSaved_removes_when_already_saved() = runTest {
        val api = FakeCollectionApi()
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.toggleSaved()
        advanceUntilIdle()
        vm.toggleSaved()
        advanceUntilIdle()

        assertEquals(listOf(mediaId), api.removed)
        assertFalse(vm.state.value.saved)
    }

    @Test
    fun toggleFavorite_flips_the_flag_independent_of_saved_state() = runTest {
        val api = FakeCollectionApi()
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.toggleSaved() // save first so favorite has a membership row to flip
        advanceUntilIdle()
        vm.toggleFavorite()
        advanceUntilIdle()

        assertTrue(vm.state.value.favorite)
    }

    @Test
    fun toggleMovieWatched_flips_the_synthetic_movie_episode() = runTest {
        val progressApi = FakeProgressApi()
        val vm = viewModel(progressApi = progressApi)
        advanceUntilIdle()

        assertFalse(vm.state.value.movieWatched)
        vm.toggleMovieWatched()
        advanceUntilIdle()

        assertTrue(vm.state.value.movieWatched)
    }

    @Test
    fun toggleEpisodeSeen_updates_state_reactively() = runTest {
        val progressApi = FakeProgressApi()
        val vm = viewModel(progressApi = progressApi, detailsToLoad = tvDetails, id = show)
        advanceUntilIdle()

        assertFalse(vm.state.value.isSeen(episode1.id))
        vm.toggleEpisodeSeen(episode1.id)
        advanceUntilIdle()

        assertTrue(vm.state.value.isSeen(episode1.id))
        assertFalse(vm.state.value.isSeen(episode2.id))
    }

    @Test
    fun markSeasonSeen_delegates_to_progressApi() = runTest {
        val progressApi = FakeProgressApi()
        val vm = viewModel(progressApi = progressApi, detailsToLoad = tvDetails, id = show)
        advanceUntilIdle()

        vm.markSeasonSeen(tvDetails.seasons.single())
        advanceUntilIdle()

        assertEquals(listOf(tvDetails.seasons.single()), progressApi.markedSeasons)
        assertEquals(2, vm.state.value.seenCountIn(tvDetails.seasons.single()))
    }

    @Test
    fun markPreviousSeen_passes_the_loaded_seasons_and_target() = runTest {
        val progressApi = FakeProgressApi()
        val vm = viewModel(progressApi = progressApi, detailsToLoad = tvDetails, id = show)
        advanceUntilIdle()

        vm.markPreviousSeen(episode2.id)
        advanceUntilIdle()

        assertEquals(listOf(episode2.id), progressApi.markedPrevious)
    }
}
