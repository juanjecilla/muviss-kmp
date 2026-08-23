@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.search.ui

import com.codingpit.muviss.feature.collection.api.CollectionApi
import com.codingpit.muviss.feature.collection.api.CollectionMembership
import com.codingpit.muviss.feature.collection.api.CollectionSummary
import com.codingpit.muviss.feature.collection.api.NewEpisodesResult
import com.codingpit.muviss.feature.progress.api.ProgressApi
import com.codingpit.muviss.feature.search.domain.MediaDetailUseCase
import com.codingpit.muviss.feature.search.domain.MoreLikeThisUseCase
import com.codingpit.muviss.feature.search.domain.RecommendationsUseCase
import com.codingpit.muviss.feature.search.domain.SearchRepository
import com.codingpit.muviss.feature.search.domain.SimilarMediaUseCase
import com.codingpit.muviss.feature.search.domain.WatchProvidersUseCase
import com.codingpit.muviss.models.Episode
import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.Genre
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.MediaType
import com.codingpit.muviss.models.PagedResult
import com.codingpit.muviss.models.Season
import com.codingpit.muviss.models.WatchProvider
import com.codingpit.muviss.models.WatchProviders
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

private class FakeDetailRepo(
    private val details: MediaDetails,
    private val watchProviders: Result<WatchProviders> = Result.success(WatchProviders()),
    private val recommendations: Result<PagedResult<MediaSummary>> = Result.success(PagedResult(emptyList(), 1, 1)),
    private val similar: Result<PagedResult<MediaSummary>> = Result.success(PagedResult(emptyList(), 1, 1)),
) : SearchRepository {
    override suspend fun search(query: String, page: Int) = Result.success(PagedResult(emptyList<MediaSummary>(), 1, 1))
    override suspend fun trending() = Result.success(emptyList<MediaSummary>())
    override suspend fun details(id: MediaId) = Result.success(details)
    override suspend fun discover(type: MediaType, page: Int, genreId: String?) = Result.success(PagedResult(emptyList<MediaSummary>(), 1, 1))
    override suspend fun genres(type: MediaType) = Result.success(emptyList<Genre>())
    override suspend fun watchProviders(id: MediaId) = watchProviders
    override suspend fun recommendations(id: MediaId, page: Int) = recommendations
    override suspend fun similar(id: MediaId, page: Int) = similar
}

/** Bundles [FakeDetailRepo]'s independently-loaded-section fakes into one test-helper param, keeping [DetailViewModelTest.viewModel]'s parameter count under detekt's LongParameterList threshold. */
private data class DetailRepoFakes(
    val triageApi: FakeTriageApi = FakeTriageApi(),
    val watchProviders: Result<WatchProviders> = Result.success(WatchProviders()),
    val recommendations: Result<PagedResult<MediaSummary>> = Result.success(PagedResult(emptyList(), 1, 1)),
    val similar: Result<PagedResult<MediaSummary>> = Result.success(PagedResult(emptyList(), 1, 1)),
)

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

    override suspend fun setNotificationsMuted(mediaId: MediaId, muted: Boolean) {
        membership.value = membership.value?.copy(notificationsMuted = muted)
    }

    override suspend fun setRating(mediaId: MediaId, rating: Int?) {
        membership.value = membership.value?.copy(rating = rating)
    }

    override suspend fun setNote(mediaId: MediaId, note: String?) {
        membership.value = membership.value?.copy(note = note)
    }

    override suspend fun refreshAndFindNewEpisodes(): List<NewEpisodesResult> = error("not used")
}

private class FakeProgressApi : ProgressApi {
    private val seen = MutableStateFlow<Set<EpisodeId>>(emptySet())
    val markedSeasons = mutableListOf<Season>()
    val markedPrevious = mutableListOf<EpisodeId>()

    override fun observeSeenEpisodes(mediaId: MediaId): Flow<Set<EpisodeId>> = seen

    override fun observeSeenActivityEpochDays(): Flow<Set<Long>> = MutableStateFlow(emptySet())

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

    override suspend fun markAllAiredSeen(seasons: List<Season>, todayEpochDay: Long) = Unit

    override suspend fun clearProgress(mediaId: MediaId) = Unit

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
        repoFakes: DetailRepoFakes = DetailRepoFakes(),
    ): DetailViewModel {
        val repo = FakeDetailRepo(detailsToLoad, repoFakes.watchProviders, repoFakes.recommendations, repoFakes.similar)
        return DetailViewModel(
            id,
            MediaDetailUseCase(repo),
            DetailPeers(collectionApi, progressApi, repoFakes.triageApi),
            WatchProvidersUseCase(repo),
            MoreLikeThisUseCase(RecommendationsUseCase(repo), SimilarMediaUseCase(repo)),
        )
    }

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
    fun toggleNotificationsMuted_flips_the_flag_independent_of_favorite() = runTest {
        val api = FakeCollectionApi()
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.toggleSaved() // save first so mute has a membership row to flip
        advanceUntilIdle()
        vm.toggleNotificationsMuted()
        advanceUntilIdle()

        assertTrue(vm.state.value.notificationsMuted)
        assertFalse(vm.state.value.favorite)
    }

    @Test
    fun setRating_sets_the_personal_rating() = runTest {
        val api = FakeCollectionApi()
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.toggleSaved() // save first so rating has a membership row to set.
        advanceUntilIdle()
        vm.setRating(8)
        advanceUntilIdle()

        assertEquals(8, vm.state.value.rating)
    }

    @Test
    fun setRating_with_the_same_value_again_clears_it() = runTest {
        val api = FakeCollectionApi()
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.toggleSaved()
        advanceUntilIdle()
        vm.setRating(8)
        advanceUntilIdle()
        vm.setRating(8)
        advanceUntilIdle()

        assertEquals(null, vm.state.value.rating)
    }

    @Test
    fun clearRating_clears_regardless_of_the_current_value() = runTest {
        val api = FakeCollectionApi()
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.toggleSaved()
        advanceUntilIdle()
        vm.setRating(3)
        advanceUntilIdle()
        vm.clearRating()
        advanceUntilIdle()

        assertEquals(null, vm.state.value.rating)
    }

    @Test
    fun setNote_persists_the_note_text() = runTest {
        val api = FakeCollectionApi()
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.toggleSaved()
        advanceUntilIdle()
        vm.setNote("Great rewatch")
        advanceUntilIdle()

        assertEquals("Great rewatch", vm.state.value.note)
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

    @Test
    fun watchProviders_starts_null_and_populates_once_loaded() = runTest {
        val providers = WatchProviders(flatrate = listOf(WatchProvider("8", "Netflix")))
        val vm = viewModel(repoFakes = DetailRepoFakes(watchProviders = Result.success(providers)))
        advanceUntilIdle()

        assertEquals(providers, vm.state.value.watchProviders)
    }

    @Test
    fun watchProviders_failure_leaves_the_section_hidden_rather_than_erroring() = runTest {
        val vm = viewModel(repoFakes = DetailRepoFakes(watchProviders = Result.failure(RuntimeException("no providers"))))
        advanceUntilIdle()

        assertEquals(null, vm.state.value.watchProviders)
        assertEquals(null, vm.state.value.error)
    }

    @Test
    fun watchProviders_empty_result_is_reported_as_is_so_the_screen_can_hide_the_section() = runTest {
        val vm = viewModel(repoFakes = DetailRepoFakes(watchProviders = Result.success(WatchProviders())))
        advanceUntilIdle()

        assertEquals(WatchProviders(), vm.state.value.watchProviders)
        assertTrue(vm.state.value.watchProviders!!.isEmpty)
    }

    @Test
    fun moreLikeThis_uses_recommendations_when_present() = runTest {
        val recommended = MediaSummary(MediaId.tmdbMovie("99"), "Recommended")
        val similarTitle = MediaSummary(MediaId.tmdbMovie("77"), "Similar")
        val vm = viewModel(
            repoFakes = DetailRepoFakes(
                recommendations = Result.success(PagedResult(listOf(recommended), 1, 1)),
                similar = Result.success(PagedResult(listOf(similarTitle), 1, 1)),
            ),
        )
        advanceUntilIdle()

        assertEquals(listOf(recommended), vm.state.value.moreLikeThis)
    }

    @Test
    fun moreLikeThis_falls_back_to_similar_when_recommendations_are_empty() = runTest {
        val similarTitle = MediaSummary(MediaId.tmdbMovie("77"), "Similar")
        val vm = viewModel(
            repoFakes = DetailRepoFakes(
                recommendations = Result.success(PagedResult(emptyList(), 1, 1)),
                similar = Result.success(PagedResult(listOf(similarTitle), 1, 1)),
            ),
        )
        advanceUntilIdle()

        assertEquals(listOf(similarTitle), vm.state.value.moreLikeThis)
    }

    @Test
    fun moreLikeThis_falls_back_to_similar_when_recommendations_fail() = runTest {
        val similarTitle = MediaSummary(MediaId.tmdbMovie("77"), "Similar")
        val vm = viewModel(
            repoFakes = DetailRepoFakes(
                recommendations = Result.failure(RuntimeException("no recommendations")),
                similar = Result.success(PagedResult(listOf(similarTitle), 1, 1)),
            ),
        )
        advanceUntilIdle()

        assertEquals(listOf(similarTitle), vm.state.value.moreLikeThis)
    }

    @Test
    fun moreLikeThis_is_empty_when_both_recommendations_and_similar_are_empty() = runTest {
        val vm = viewModel()
        advanceUntilIdle()

        assertTrue(vm.state.value.moreLikeThis.isEmpty())
    }
}
