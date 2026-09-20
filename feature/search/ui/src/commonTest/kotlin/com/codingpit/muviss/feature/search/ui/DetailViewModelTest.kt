@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.search.ui

import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.feature.collection.api.CollectionApi
import com.codingpit.muviss.feature.collection.api.CollectionMembership
import com.codingpit.muviss.feature.collection.api.CollectionSummary
import com.codingpit.muviss.feature.collection.api.NewEpisodesResult
import com.codingpit.muviss.feature.progress.api.EpisodePlay
import com.codingpit.muviss.feature.progress.api.ProgressApi
import com.codingpit.muviss.feature.progress.api.WatchNextItem
import com.codingpit.muviss.feature.search.domain.MediaDetailUseCase
import com.codingpit.muviss.feature.search.domain.MoreLikeThisUseCase
import com.codingpit.muviss.feature.search.domain.RecommendationsUseCase
import com.codingpit.muviss.feature.search.domain.SearchRepository
import com.codingpit.muviss.feature.search.domain.SimilarMediaUseCase
import com.codingpit.muviss.feature.search.domain.WatchProvidersUseCase
import com.codingpit.muviss.models.Episode
import com.codingpit.muviss.models.EpisodeDetails
import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.Genre
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.MediaType
import com.codingpit.muviss.models.MetadataError
import com.codingpit.muviss.models.PagedResult
import com.codingpit.muviss.models.Season
import com.codingpit.muviss.models.WatchProvider
import com.codingpit.muviss.models.WatchProviders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeDetailRepo(
    private val details: MediaDetails,
    private val watchProviders: Result<WatchProviders> = Result.success(WatchProviders()),
    private val recommendations: Result<PagedResult<MediaSummary>> = Result.success(PagedResult(emptyList(), 1, 1)),
    private val similar: Result<PagedResult<MediaSummary>> = Result.success(PagedResult(emptyList(), 1, 1)),
    private val detailsFailure: Throwable? = null,
) : SearchRepository {
    override suspend fun search(query: String, page: Int) = Result.success(PagedResult(emptyList<MediaSummary>(), 1, 1))
    override suspend fun trending() = Result.success(emptyList<MediaSummary>())
    override suspend fun details(id: MediaId) = detailsFailure?.let { Result.failure<MediaDetails>(it) } ?: Result.success(details)
    override suspend fun discover(type: MediaType, page: Int, genreId: String?) = Result.success(PagedResult(emptyList<MediaSummary>(), 1, 1))
    override suspend fun genres(type: MediaType) = Result.success(emptyList<Genre>())
    override suspend fun watchProviders(id: MediaId) = watchProviders
    override suspend fun recommendations(id: MediaId, page: Int) = recommendations
    override suspend fun similar(id: MediaId, page: Int) = similar
    override suspend fun episodeDetails(episodeId: EpisodeId) = Result.success(EpisodeDetails(episodeId, "Episode", episodeId.seasonNumber, episodeId.episodeNumber))
}

/** Bundles [FakeDetailRepo]'s independently-loaded-section fakes into one test-helper param, keeping [DetailViewModelTest.viewModel]'s parameter count under detekt's LongParameterList threshold. */
private data class DetailRepoFakes(
    val triageApi: FakeTriageApi = FakeTriageApi(),
    val watchProviders: Result<WatchProviders> = Result.success(WatchProviders()),
    val recommendations: Result<PagedResult<MediaSummary>> = Result.success(PagedResult(emptyList(), 1, 1)),
    val similar: Result<PagedResult<MediaSummary>> = Result.success(PagedResult(emptyList(), 1, 1)),
    val detailsFailure: Throwable? = null,
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

/**
 * Models rewatch history the way the real one does (ADR 0011): plays are the
 * record, `seen` follows from whether any remain. Tests that only care about
 * ticks can still read [seenIds].
 */
internal class FakeProgressApi : ProgressApi {
    private val plays = MutableStateFlow<Map<EpisodeId, List<Long>>>(emptyMap())
    val markedSeasons = mutableListOf<Season>()
    val unmarkedSeasons = mutableListOf<Season>()
    val markedShows = mutableListOf<List<Season>>()
    val markedPrevious = mutableListOf<EpisodeId>()
    val clearedEpisodes = mutableListOf<EpisodeId>()
    var now = 1_000L

    val seenIds: Set<EpisodeId> get() = plays.value.filterValues { it.isNotEmpty() }.keys

    private fun addPlay(episodeId: EpisodeId) {
        plays.value = plays.value + (episodeId to (plays.value[episodeId].orEmpty() + now))
    }

    override fun observeSeenEpisodes(mediaId: MediaId): Flow<Set<EpisodeId>> = plays.map { all -> all.filterValues { it.isNotEmpty() }.keys }

    // Watch-next (EPIC 22) — this fake's subject never asks for it.
    override fun observeWatchNext(): Flow<List<WatchNextItem>> = flowOf(emptyList())
    override suspend fun refreshWatchNextCatalogs() = Unit

    override fun observeSeenActivityEpochDays(): Flow<Set<Long>> = MutableStateFlow(emptySet())

    override fun observePlayCounts(mediaId: MediaId): Flow<Map<EpisodeId, Int>> = plays.map { all -> all.filterValues { it.isNotEmpty() }.mapValues { it.value.size } }

    override fun observePlays(episodeId: EpisodeId): Flow<List<EpisodePlay>> = plays.map { all -> all[episodeId].orEmpty().sortedDescending().map { EpisodePlay(episodeId, it) } }

    override fun observeRewatchCounts(sinceEpochMs: Long): Flow<Map<MediaId, Int>> = flowOf(emptyMap())

    override fun observeRewatchTimestamps(sinceEpochMs: Long): Flow<List<Long>> = flowOf(emptyList())

    override suspend fun setEpisodeSeen(episodeId: EpisodeId, seen: Boolean) {
        if (seen) {
            if (plays.value[episodeId].isNullOrEmpty()) addPlay(episodeId)
        } else {
            plays.value = plays.value - episodeId
        }
    }

    override suspend fun recordPlay(episodeId: EpisodeId) = addPlay(episodeId)

    override suspend fun removeLatestPlay(episodeId: EpisodeId) {
        val remaining = plays.value[episodeId].orEmpty().dropLast(1)
        plays.value = if (remaining.isEmpty()) plays.value - episodeId else plays.value + (episodeId to remaining)
    }

    override suspend fun clearPlays(episodeId: EpisodeId) {
        clearedEpisodes += episodeId
        plays.value = plays.value - episodeId
    }

    override suspend fun markSeasonAiredSeen(season: Season, todayEpochDay: Long): List<EpisodeId> {
        markedSeasons += season
        return airedUnseen(listOf(season), todayEpochDay).onEach { addPlay(it) }
    }

    override suspend fun markShowAiredSeen(seasons: List<Season>, todayEpochDay: Long): List<EpisodeId> {
        markedShows += seasons
        return airedUnseen(seasons, todayEpochDay).onEach { addPlay(it) }
    }

    private fun airedUnseen(seasons: List<Season>, todayEpochDay: Long): List<EpisodeId> = seasons
        .flatMap { it.episodes }
        .filter { episode ->
            val airDate = episode.airDateEpochDay
            airDate != null && airDate <= todayEpochDay && plays.value[episode.id].isNullOrEmpty()
        }
        .map { it.id }

    override suspend fun unmarkSeason(season: Season) {
        unmarkedSeasons += season
        season.episodes.forEach { removeLatestPlay(it.id) }
    }

    override suspend fun unmarkShow(seasons: List<Season>) {
        seasons.forEach { unmarkSeason(it) }
    }

    override suspend fun undoBulkMark(episodeIds: List<EpisodeId>) {
        episodeIds.forEach { removeLatestPlay(it) }
    }

    override suspend fun markPreviousSeen(seasons: List<Season>, target: EpisodeId) {
        markedPrevious += target
    }

    override suspend fun markAllAiredSeen(seasons: List<Season>, todayEpochDay: Long) = Unit

    override suspend fun clearProgress(mediaId: MediaId) {
        plays.value = emptyMap()
    }

    override suspend fun setMovieWatched(mediaId: MediaId, watched: Boolean) = setEpisodeSeen(EpisodeId.forMovie(mediaId), watched)
}

/** Fixed "today" so aired-vs-unaired is a property of the fixture, not of the calendar. */
private class TestClock(private val todayEpochMs: Long) : AppClock {
    override fun nowEpochMs(): Long = todayEpochMs
}

class DetailViewModelTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val mediaId = MediaId.tmdbMovie("603")
    private val details = MediaDetails(MediaSummary(mediaId, "The Matrix"))

    private val show = MediaId.tmdbTv("1399")

    // Air dates matter now: bulk marks tick aired episodes only, so a fixture
    // without them would silently mark nothing.
    private val episode1 = Episode(EpisodeId(show, 1, 1), 1, 1, "Winter Is Coming", airDateEpochDay = 10L)
    private val episode2 = Episode(EpisodeId(show, 1, 2), 1, 2, "The Kingsroad", airDateEpochDay = 11L)
    private val unairedEpisode = Episode(EpisodeId(show, 1, 3), 1, 3, "Not Out Yet", airDateEpochDay = 9_999L)
    private val undatedSpecial = Episode(EpisodeId(show, 1, 4), 1, 4, "Undated Special", airDateEpochDay = null)
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
        val repo = FakeDetailRepo(detailsToLoad, repoFakes.watchProviders, repoFakes.recommendations, repoFakes.similar, repoFakes.detailsFailure)
        return DetailViewModel(
            id,
            MediaDetailUseCase(repo),
            DetailPeers(collectionApi, progressApi, repoFakes.triageApi),
            WatchProvidersUseCase(repo),
            MoreLikeThisUseCase(RecommendationsUseCase(repo), SimilarMediaUseCase(repo)),
            // Day 10 onwards has aired; unairedEpisode (day 9999) has not.
            TestClock(todayEpochMs = 20L * 86_400_000L),
        )
    }

    @Test
    fun a_failed_load_shows_mapped_copy_for_a_metadata_error() = runTest {
        val vm = viewModel(repoFakes = DetailRepoFakes(detailsFailure = MetadataError.RateLimited(retryAfterSeconds = 12)))
        advanceUntilIdle()

        assertEquals(MetadataError.RateLimited().userMessage, vm.state.value.error)
    }

    @Test
    fun a_failed_load_never_shows_a_raw_exception_message() = runTest {
        val leaky = IllegalStateException("Fields [id, title] are required for type MediaDetails; url=https://x?api_key=SECRET")
        val vm = viewModel(repoFakes = DetailRepoFakes(detailsFailure = leaky))
        advanceUntilIdle()

        assertEquals("Something went wrong", vm.state.value.error)
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
    fun marking_a_season_seen_leaves_unaired_and_undated_episodes_alone() = runTest {
        val progressApi = FakeProgressApi()
        val season = Season(1, "Season 1", listOf(episode1, episode2, unairedEpisode, undatedSpecial))
        val vm = viewModel(
            progressApi = progressApi,
            detailsToLoad = MediaDetails(MediaSummary(show, "Game of Thrones"), seasons = listOf(season)),
            id = show,
        )
        advanceUntilIdle()

        vm.markSeasonSeen(season)
        advanceUntilIdle()

        // Ticking these would push seenEpisodes past airedEpisodes, and
        // WatchProgress `require`s otherwise — the library screen would throw
        // the next time it derived this title's status.
        assertFalse(vm.state.value.isSeen(unairedEpisode.id))
        assertFalse(vm.state.value.isSeen(undatedSpecial.id))
        assertEquals(2, vm.state.value.seenCountIn(season))
    }

    @Test
    fun marking_a_season_seen_offers_an_undo_for_exactly_what_it_wrote() = runTest {
        val progressApi = FakeProgressApi()
        val season = tvDetails.seasons.single()
        val vm = viewModel(progressApi = progressApi, detailsToLoad = tvDetails, id = show)
        advanceUntilIdle()
        vm.toggleEpisodeSeen(episode1.id)
        advanceUntilIdle()

        vm.markSeasonSeen(season)
        advanceUntilIdle()

        val undo = vm.state.value.pendingUndo
        assertNotNull(undo)
        assertEquals(listOf(episode2.id), undo.episodeIds, "episode1 was already seen, so the undo must not touch it")
        // Names the season, rather than showing a raw template to the user.
        assertEquals("${season.name} marked seen", undo.message)

        vm.undoBulkMark()
        advanceUntilIdle()

        assertTrue(vm.state.value.isSeen(episode1.id), "the undo took back only what the bulk mark wrote")
        assertFalse(vm.state.value.isSeen(episode2.id))
        assertNull(vm.state.value.pendingUndo)
    }

    @Test
    fun unmarking_a_season_drops_one_viewing_from_each_seen_episode() = runTest {
        val progressApi = FakeProgressApi()
        val season = tvDetails.seasons.single()
        val vm = viewModel(progressApi = progressApi, detailsToLoad = tvDetails, id = show)
        advanceUntilIdle()

        // episode1 genuinely watched twice, episode2 once.
        vm.recordRewatch(episode1.id)
        vm.recordRewatch(episode1.id)
        vm.recordRewatch(episode2.id)
        advanceUntilIdle()

        vm.unmarkSeason(season)
        advanceUntilIdle()

        assertTrue(vm.state.value.isSeen(episode1.id), "a title watched twice is not unwatched by one undo")
        assertEquals(1, vm.state.value.playCountOf(episode1.id))
        assertFalse(vm.state.value.isSeen(episode2.id))
    }

    @Test
    fun marking_the_whole_show_seen_covers_every_season() = runTest {
        val progressApi = FakeProgressApi()
        val seasonTwo = Season(2, "Season 2", listOf(Episode(EpisodeId(show, 2, 1), 2, 1, "Valar", airDateEpochDay = 12L)))
        val details = MediaDetails(
            MediaSummary(show, "Game of Thrones"),
            seasons = listOf(tvDetails.seasons.single(), seasonTwo),
        )
        val vm = viewModel(progressApi = progressApi, detailsToLoad = details, id = show)
        advanceUntilIdle()

        vm.markShowSeen()
        advanceUntilIdle()

        assertEquals(1, progressApi.markedShows.size)
        assertEquals(2, vm.state.value.seenCountIn(details.seasons.first()))
        assertEquals(1, vm.state.value.seenCountIn(seasonTwo))
        assertNotNull(vm.state.value.pendingUndo)
    }

    @Test
    fun watching_an_episode_again_adds_a_viewing_and_keeps_it_seen() = runTest {
        val vm = viewModel(detailsToLoad = tvDetails, id = show)
        advanceUntilIdle()
        vm.toggleEpisodeSeen(episode1.id)
        advanceUntilIdle()

        vm.recordRewatch(episode1.id)
        advanceUntilIdle()

        assertEquals(2, vm.state.value.playCountOf(episode1.id))
        assertTrue(vm.state.value.isSeen(episode1.id))
    }

    @Test
    fun taking_back_a_mistaken_tick_drops_only_the_newest_viewing() = runTest {
        val vm = viewModel(detailsToLoad = tvDetails, id = show)
        advanceUntilIdle()
        vm.recordRewatch(episode1.id)
        vm.recordRewatch(episode1.id)
        advanceUntilIdle()

        vm.undoLatestPlay(episode1.id)
        advanceUntilIdle()

        assertEquals(1, vm.state.value.playCountOf(episode1.id))
        assertTrue(vm.state.value.isSeen(episode1.id))

        vm.undoLatestPlay(episode1.id)
        advanceUntilIdle()

        assertEquals(0, vm.state.value.playCountOf(episode1.id))
        assertFalse(vm.state.value.isSeen(episode1.id), "with no viewings left it was never watched")
    }

    @Test
    fun clearing_an_episodes_history_forgets_every_viewing() = runTest {
        val progressApi = FakeProgressApi()
        val vm = viewModel(progressApi = progressApi, detailsToLoad = tvDetails, id = show)
        advanceUntilIdle()
        vm.recordRewatch(episode1.id)
        vm.recordRewatch(episode1.id)
        advanceUntilIdle()

        vm.clearEpisodeHistory(episode1.id)
        advanceUntilIdle()

        assertEquals(listOf(episode1.id), progressApi.clearedEpisodes)
        assertEquals(0, vm.state.value.playCountOf(episode1.id))
        assertFalse(vm.state.value.isSeen(episode1.id))
    }

    @Test
    fun play_counts_reach_the_state_so_rows_can_show_them() = runTest {
        val vm = viewModel(detailsToLoad = tvDetails, id = show)
        advanceUntilIdle()

        vm.recordRewatch(episode1.id)
        vm.recordRewatch(episode1.id)
        vm.recordRewatch(episode1.id)
        advanceUntilIdle()

        assertEquals(3, vm.state.value.playCountOf(episode1.id))
        assertEquals(0, vm.state.value.playCountOf(episode2.id))
    }

    @Test
    fun dismissing_the_undo_leaves_the_progress_it_wrote_in_place() = runTest {
        val vm = viewModel(detailsToLoad = tvDetails, id = show)
        advanceUntilIdle()

        vm.markSeasonSeen(tvDetails.seasons.single())
        advanceUntilIdle()
        vm.dismissUndo()

        assertNull(vm.state.value.pendingUndo)
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
