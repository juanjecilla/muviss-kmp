@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.search.ui

import com.codingpit.muviss.core.common.connectivity.ConnectivityMonitor
import com.codingpit.muviss.core.testing.FakeClock
import com.codingpit.muviss.feature.search.domain.MediaDetailUseCase
import com.codingpit.muviss.feature.search.domain.MoreLikeThisUseCase
import com.codingpit.muviss.feature.search.domain.RecommendationsUseCase
import com.codingpit.muviss.feature.search.domain.SimilarMediaUseCase
import com.codingpit.muviss.feature.search.domain.WatchProvidersUseCase
import com.codingpit.muviss.models.Episode
import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.Season
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The write paths of Detail's primary action and its Undo (#721, ADR 0037): an already-seen tick writes nothing,
 * a later progress write releases the entry an Undo would take back, Add never removes, and catch-up adds an
 * unsaved show and never ticks an unaired episode. Split from [DetailViewModelTest] to keep that class under
 * detekt's LargeClass budget.
 */
class DetailProgressWritesTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val mediaId = MediaId.tmdbMovie("603")
    private val details = MediaDetails(MediaSummary(mediaId, "The Matrix"))

    private val show = MediaId.tmdbTv("1399")
    private val episode1 = Episode(EpisodeId(show, 1, 1), 1, 1, "Winter Is Coming", airDateEpochDay = 10L)
    private val episode2 = Episode(EpisodeId(show, 1, 2), 1, 2, "The Kingsroad", airDateEpochDay = 11L)
    private val unairedEpisode = Episode(EpisodeId(show, 1, 3), 1, 3, "Not Out Yet", airDateEpochDay = 9_999L)
    private val tvDetails = MediaDetails(
        summary = MediaSummary(show, "Game of Thrones"),
        seasons = listOf(Season(1, "Season 1", listOf(episode1, episode2))),
    )

    private fun viewModel(
        collectionApi: FakeCollectionApi = FakeCollectionApi(),
        progressApi: FakeProgressApi = FakeProgressApi(),
        detailsToLoad: MediaDetails = details,
        id: MediaId = mediaId,
    ): DetailViewModel {
        val fakes = DetailRepoFakes()
        val repo = FakeDetailRepo(detailsToLoad, fakes.watchProviders, fakes.recommendations, fakes.similar, fakes.detailsFailure)
        return DetailViewModel(
            id,
            MediaDetailUseCase(repo),
            DetailPeers(collectionApi, progressApi, fakes.triageApi, FakeMutingApi()),
            WatchProvidersUseCase(repo),
            MoreLikeThisUseCase(RecommendationsUseCase(repo), SimilarMediaUseCase(repo)),
            FakeClock(20L * 86_400_000L),
            ConnectivityMonitor.AlwaysOnline,
            flowOf(true),
        )
    }

    @Test
    fun tick_next_on_an_episode_already_seen_writes_nothing_and_offers_no_undo() = runTest {
        val collection = FakeCollectionApi()
        val progress = FakeProgressApi()
        progress.setEpisodeSeen(episode1.id, true)
        val vm = viewModel(collectionApi = collection, progressApi = progress, detailsToLoad = tvDetails, id = show)

        vm.tickNext(episode1.id)

        assertNull(vm.state.value.pendingUndo)
        assertTrue(collection.added.isEmpty())
        assertEquals(setOf(episode1.id), progress.seenIds)
    }

    @Test
    fun mark_movie_watched_on_a_tv_id_adds_nothing_to_the_collection_and_writes_no_tick() = runTest {
        val collection = FakeCollectionApi()
        val progress = FakeProgressApi()
        val vm = viewModel(collectionApi = collection, progressApi = progress, detailsToLoad = tvDetails, id = show)

        vm.markMovieWatched()

        assertTrue(collection.added.isEmpty())
        assertTrue(progress.seenIds.isEmpty())
        assertNull(vm.state.value.pendingUndo)
    }

    @Test
    fun mark_movie_watched_on_an_already_watched_movie_offers_no_undo() = runTest {
        val progress = FakeProgressApi()
        progress.setEpisodeSeen(EpisodeId.forMovie(mediaId), true)
        val vm = viewModel(progressApi = progress)

        vm.markMovieWatched()

        assertNull(vm.state.value.pendingUndo)
        assertTrue(EpisodeId.forMovie(mediaId) in progress.seenIds)
    }

    @Test
    fun a_later_progress_write_releases_the_entry_so_undo_leaves_it_alone() = runTest {
        val collection = FakeCollectionApi()
        val progress = FakeProgressApi()
        val vm = viewModel(collectionApi = collection, progressApi = progress, detailsToLoad = tvDetails, id = show)

        vm.tickNext(episode1.id)
        assertEquals(true, vm.state.value.pendingUndo?.createdEntry)
        vm.toggleEpisodeSeen(episode2.id)
        assertEquals(false, vm.state.value.pendingUndo?.createdEntry)
        vm.undoBulkMark(checkNotNull(vm.state.value.pendingUndo))

        // The entry is kept, and Undo takes back the latest tick (episode 2), not the one the entry was created for.
        assertTrue(collection.removed.isEmpty())
        assertEquals(setOf(episode1.id), progress.seenIds)
    }

    @Test
    fun a_single_checkmark_tap_offers_undo_that_takes_the_tick_back() = runTest {
        val progress = FakeProgressApi()
        val vm = viewModel(progressApi = progress, detailsToLoad = tvDetails, id = show)

        vm.toggleEpisodeSeen(episode1.id)
        assertEquals(listOf(episode1.id), vm.state.value.pendingUndo?.episodeIds)
        vm.undoBulkMark(checkNotNull(vm.state.value.pendingUndo))

        assertTrue(progress.seenIds.isEmpty())
    }

    @Test
    fun a_single_checkmark_tap_on_an_unsaved_show_undo_removes_the_entry_it_created() = runTest {
        val collection = FakeCollectionApi()
        val progress = FakeProgressApi()
        val vm = viewModel(collectionApi = collection, progressApi = progress, detailsToLoad = tvDetails, id = show)

        vm.toggleEpisodeSeen(episode1.id)
        assertEquals(true, vm.state.value.pendingUndo?.createdEntry)
        vm.undoBulkMark(checkNotNull(vm.state.value.pendingUndo))

        assertEquals(listOf(show), collection.removed)
        assertTrue(progress.seenIds.isEmpty())
    }

    /**
     * A seen read that dies before it reports leaves the button on Loading. The error page shows instead, and Retry
     * subscribes again: the read reports, and the error clears.
     */
    @Test
    fun a_progress_read_that_fails_before_reporting_shows_an_error_and_retry_resubscribes() = runTest {
        val progress = FakeProgressApi().apply { failNextSeenObservation = true }
        val vm = viewModel(progressApi = progress, detailsToLoad = tvDetails, id = show)

        assertTrue(vm.state.value.error != null)
        assertTrue(!vm.state.value.progressLoaded)

        vm.load()

        assertTrue(vm.state.value.progressLoaded)
        assertNull(vm.state.value.error)
    }

    /** The same for the library read: without a membership answer the primary action can never leave Loading. */
    @Test
    fun a_library_read_that_fails_before_reporting_shows_an_error_and_retry_resubscribes() = runTest {
        val collection = FakeCollectionApi().apply { failNextMembershipObservation = true }
        val vm = viewModel(collectionApi = collection, detailsToLoad = tvDetails, id = show)

        assertTrue(vm.state.value.error != null)
        assertTrue(!vm.state.value.membershipLoaded)

        vm.load()

        assertTrue(vm.state.value.membershipLoaded)
        assertNull(vm.state.value.error)
    }

    /** Every reload cancels the previous seen collector before starting its own: there is never more than one. */
    @Test
    fun repeated_reloads_keep_exactly_one_progress_collector() = runTest {
        val progress = FakeProgressApi()
        val vm = viewModel(progressApi = progress, detailsToLoad = tvDetails, id = show)

        vm.load()
        vm.load()
        vm.load()

        assertEquals(1, progress.activeSeenCollectors)
    }

    /**
     * A removal queued behind an in-flight tick publishes its Undo after the tick's, so the Undo left on screen is
     * the removal's and tapping it brings the entry back. The tick is held inside the lock by its fresh read.
     */
    @Test
    fun a_removal_queued_behind_an_in_flight_tick_keeps_its_own_undo() = runTest {
        val collection = FakeCollectionApi()
        val progress = FakeProgressApi()
        val vm = viewModel(collectionApi = collection, progressApi = progress, detailsToLoad = tvDetails, id = show)

        progress.seenReady.value = false
        vm.toggleEpisodeSeen(episode1.id)
        vm.removeFromLibrary()
        progress.seenReady.value = true

        val removal = checkNotNull(vm.state.value.pendingUndo)
        assertTrue(removal.removedFromLibrary)
        vm.undoBulkMark(removal)
        assertEquals(listOf(show), collection.restored)
    }

    /**
     * A snackbar that timed out after a newer tick offered its Undo must not clear that newer Undo. Dismissing
     * the stale record leaves the pending one in place.
     */
    @Test
    fun a_dismiss_for_a_replaced_undo_leaves_the_newer_one_pending() = runTest {
        val progress = FakeProgressApi()
        val vm = viewModel(progressApi = progress, detailsToLoad = tvDetails, id = show)

        vm.toggleEpisodeSeen(episode1.id)
        val stale = checkNotNull(vm.state.value.pendingUndo)
        vm.toggleEpisodeSeen(episode2.id)
        val pendingSecond = vm.state.value.pendingUndo

        vm.dismissUndo(stale)

        assertTrue(vm.state.value.pendingUndo === pendingSecond)
    }

    /**
     * An Undo tapped for the first tick must not reverse the second one: the second tick replaced the pending Undo
     * before the first Undo got the lock, so the tap is stale and writes nothing.
     */
    @Test
    fun an_undo_tapped_for_an_earlier_tick_does_nothing_once_a_later_tick_replaced_it() = runTest {
        val progress = FakeProgressApi()
        val vm = viewModel(progressApi = progress, detailsToLoad = tvDetails, id = show)

        vm.toggleEpisodeSeen(episode1.id)
        val tappedFirst = checkNotNull(vm.state.value.pendingUndo)
        vm.toggleEpisodeSeen(episode2.id)
        val pendingSecond = vm.state.value.pendingUndo

        vm.undoBulkMark(tappedFirst)

        assertEquals(setOf(episode1.id, episode2.id), progress.seenIds)
        assertTrue(vm.state.value.pendingUndo === pendingSecond)
    }

    @Test
    fun a_checkmark_tap_on_an_episode_already_seen_but_not_yet_loaded_writes_nothing_and_offers_no_undo() = runTest {
        val progress = FakeProgressApi()
        progress.setEpisodeSeen(episode1.id, seen = true)
        // The screen's copy was taken before the tick landed: it does not show episode1 as seen yet.
        progress.firstCollectionSnapshot = emptySet()
        val vm = viewModel(progressApi = progress, detailsToLoad = tvDetails, id = show)
        assertTrue(episode1.id !in vm.state.value.seenEpisodes, "the UI snapshot must exclude the episode")
        assertTrue(episode1.id in progress.observeSeenEpisodes(show).first(), "the fresh read must include it")

        vm.toggleEpisodeSeen(episode1.id)

        assertNull(vm.state.value.pendingUndo)
        assertEquals(setOf(episode1.id), progress.seenIds)
        assertEquals(1, progress.observePlayCounts(show).first()[episode1.id])
    }

    @Test
    fun mark_show_seen_with_nothing_aired_adds_no_entry_and_offers_no_undo() = runTest {
        val collection = FakeCollectionApi()
        val progress = FakeProgressApi()
        val notAired = MediaDetails(MediaSummary(show, "Not Yet"), seasons = listOf(Season(1, "Season 1", listOf(unairedEpisode))))
        val vm = viewModel(collectionApi = collection, progressApi = progress, detailsToLoad = notAired, id = show)

        vm.markShowSeen()

        assertTrue(collection.added.isEmpty())
        assertTrue(progress.seenIds.isEmpty())
        assertNull(vm.state.value.pendingUndo)
    }

    @Test
    fun mark_season_seen_with_nothing_aired_adds_no_entry_and_offers_no_undo() = runTest {
        val collection = FakeCollectionApi()
        val progress = FakeProgressApi()
        val notAired = Season(1, "Season 1", listOf(unairedEpisode))
        val vm = viewModel(collectionApi = collection, progressApi = progress, detailsToLoad = MediaDetails(MediaSummary(show, "Not Yet"), seasons = listOf(notAired)), id = show)

        vm.markSeasonSeen(notAired)

        assertTrue(collection.added.isEmpty())
        assertTrue(progress.seenIds.isEmpty())
        assertNull(vm.state.value.pendingUndo)
    }

    @Test
    fun add_to_library_tapped_twice_never_removes_the_title() = runTest {
        val collection = FakeCollectionApi()
        val vm = viewModel(collectionApi = collection)

        vm.addToLibrary()
        vm.addToLibrary()

        assertEquals(listOf(mediaId), collection.added)
        assertTrue(collection.removed.isEmpty())
    }

    @Test
    fun mark_previous_seen_adds_an_unsaved_show_before_recording_progress() = runTest {
        val collection = FakeCollectionApi()
        val progress = FakeProgressApi()
        val vm = viewModel(collectionApi = collection, progressApi = progress, detailsToLoad = tvDetails, id = show)

        vm.markPreviousSeen(episode2.id)

        assertEquals(listOf(show), collection.added)
        assertEquals(listOf(episode2.id), progress.markedPrevious)
    }

    @Test
    fun catching_up_through_an_already_seen_episode_adds_no_entry_and_writes_nothing() = runTest {
        val collection = FakeCollectionApi()
        val progress = FakeProgressApi()
        progress.setEpisodeSeen(episode1.id, seen = true)
        val vm = viewModel(collectionApi = collection, progressApi = progress, detailsToLoad = tvDetails, id = show)

        vm.markPreviousSeen(episode1.id)

        assertTrue(collection.added.isEmpty())
        assertTrue(progress.markedPrevious.isEmpty())
    }

    @Test
    fun mark_previous_seen_on_an_unaired_target_does_nothing() = runTest {
        val collection = FakeCollectionApi()
        val progress = FakeProgressApi()
        val withUnaired = MediaDetails(MediaSummary(show, "Game of Thrones"), seasons = listOf(Season(1, "Season 1", listOf(episode1, unairedEpisode))))
        val vm = viewModel(collectionApi = collection, progressApi = progress, detailsToLoad = withUnaired, id = show)

        vm.markPreviousSeen(unairedEpisode.id)

        assertTrue(progress.markedPrevious.isEmpty())
        assertTrue(collection.added.isEmpty())
    }

    @Test
    fun a_rewatch_after_an_offered_undo_is_not_taken_back_by_that_undo() = runTest {
        val progress = FakeProgressApi()
        val vm = viewModel(progressApi = progress, detailsToLoad = tvDetails, id = show)

        vm.tickNext(episode1.id)
        assertEquals(listOf(episode1.id), vm.state.value.pendingUndo?.episodeIds)
        val tapped = checkNotNull(vm.state.value.pendingUndo)
        vm.recordRewatch(episode1.id)
        assertNull(vm.state.value.pendingUndo)
        vm.undoBulkMark(tapped)

        // Undo would have removed the newest viewing, which is the rewatch; both viewings must remain.
        assertEquals(2, progress.observePlayCounts(show).first()[episode1.id])
    }
}
