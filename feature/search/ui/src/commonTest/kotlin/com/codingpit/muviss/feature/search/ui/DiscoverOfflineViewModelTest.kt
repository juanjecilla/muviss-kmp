@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.search.ui

import com.codingpit.muviss.core.common.connectivity.ConnectivityMonitor
import com.codingpit.muviss.core.testing.FakeClock
import com.codingpit.muviss.feature.search.domain.DiscoverCache
import com.codingpit.muviss.feature.search.domain.DiscoverFeed
import com.codingpit.muviss.feature.search.domain.DiscoverMediaUseCase
import com.codingpit.muviss.feature.search.domain.RecommendationsUseCase
import com.codingpit.muviss.feature.search.domain.Saved
import com.codingpit.muviss.feature.search.domain.SearchMediaUseCase
import com.codingpit.muviss.models.Genre
import com.codingpit.muviss.models.GenreKey
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.MediaType
import com.codingpit.muviss.models.MetadataError
import com.codingpit.muviss.models.MutedGenres
import com.codingpit.muviss.models.PagedResult
import kotlinx.coroutines.CompletableDeferred
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** #724: Discover opens from what was stored on this device, filters muting at read, and keeps its copy when a refresh fails. */
class DiscoverOfflineViewModelTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val talk = GenreKey(MediaType.TV, "10767")
    private val talkShow = MediaSummary(MediaId.tmdbTv("20"), "Late show", genreIds = listOf("35", "10767"))
    private val drama = MediaSummary(MediaId.tmdbTv("21"), "Drama", genreIds = listOf("18"))

    private fun viewModel(
        repo: FakeRepo,
        discoverCache: DiscoverCache,
        clock: FakeClock,
        settings: FakeMutingApi = FakeMutingApi(),
        connectivity: ConnectivityMonitor = ConnectivityMonitor.AlwaysOnline,
    ) = SearchViewModel(
        SearchMediaUseCase(repo),
        DiscoverMediaUseCase(repo),
        RecommendationsUseCase(repo),
        FakeSearchCollectionApi(),
        FakeTriageApi(),
        settings,
        DiscoverFeed(repo, discoverCache, clock),
        connectivity = connectivity,
    )

    private fun offline() = FakeRepo(
        movieGenresResult = Result.failure(MetadataError.Offline()),
        tvGenresResult = Result.failure(MetadataError.Offline()),
        discoverResult = { _, _, _ -> Result.failure(MetadataError.Offline()) },
    )

    private val talkGenre = Genre("10767", "Talk")

    /** What Discover stored on this device at [now]: the movie and TV shelves and their genre lists. */
    private suspend fun storedDiscover(cache: MemoryDiscoverCache, now: Long) {
        cache.saveGenres(MediaType.MOVIE, listOf(Genre("28", "Action")), now, "en-US")
        cache.saveGenres(MediaType.TV, listOf(talkGenre, Genre("18", "Drama")), now, "en-US")
        cache.savePopular(MediaType.MOVIE, 1, PagedResult(emptyList(), page = 1, totalPages = 1), now, "en-US")
        cache.savePopular(MediaType.TV, 1, PagedResult(listOf(talkShow, drama), page = 1, totalPages = 1), now, "en-US")
    }

    @Test
    fun a_stored_discover_opens_offline_with_no_error_and_its_muted_titles_filtered() = runTest {
        val cache = MemoryDiscoverCache()
        storedDiscover(cache, now = 0L)
        // Every network call fails: the stored copy is what the person sees, and it is still fresh.
        val vm = viewModel(offline(), cache, FakeClock(epochMs = 1_000L), settings = FakeMutingApi(MutedGenres(setOf(talk))))
        advanceUntilIdle()

        assertEquals(listOf(drama), vm.state.value.popularTv, "the muted talk show is filtered from the stored copy")
        assertFalse(vm.state.value.loading)
        assertNull(vm.state.value.error)
        assertFalse(vm.state.value.discoverSaved, "a fresh copy is not a saved one")
    }

    @Test
    fun a_refresh_that_fails_over_a_stored_copy_keeps_it_and_says_so_once_it_is_past_its_ttl() = runTest {
        val cache = MemoryDiscoverCache()
        storedDiscover(cache, now = 0L)
        val vm = viewModel(offline(), cache, FakeClock(epochMs = DiscoverFeed.TTL_MS + 1))
        advanceUntilIdle()

        assertEquals(listOf(talkShow, drama), vm.state.value.popularTv, "the stored shelf stays on screen")
        assertNull(vm.state.value.error, "a stored copy is never an error")
        assertTrue(vm.state.value.discoverSaved, "past its TTL and refreshed without success: say it is saved")
    }

    @Test
    fun a_reconnection_refreshes_a_saved_copy_whose_refresh_failed() = runTest {
        // Past its TTL and refreshed offline: the copy is saved, not an error, and nothing else would retry it.
        val cache = MemoryDiscoverCache()
        storedDiscover(cache, now = 0L)
        var reachable = false
        val online = MutableStateFlow(false)
        val vm = viewModel(
            FakeRepo(
                discoverResult = { _, page, _ ->
                    if (reachable) Result.success(PagedResult(listOf(drama), page, page)) else Result.failure(MetadataError.Offline())
                },
            ),
            cache,
            FakeClock(epochMs = DiscoverFeed.TTL_MS + 1),
            connectivity = object : ConnectivityMonitor {
                override val isOnline: Flow<Boolean> = online
            },
        )
        advanceUntilIdle()
        assertTrue(vm.state.value.discoverSaved)

        reachable = true
        online.value = true
        advanceUntilIdle()

        assertFalse(vm.state.value.discoverSaved, "the connection returned, so the saved copy was refreshed")
        assertEquals(listOf(drama), vm.state.value.popularTv)
    }

    @Test
    fun a_reconnection_does_not_cancel_a_search_typed_over_a_saved_copy() = runTest {
        // A saved copy that failed to refresh is retried on reconnection, but only while Discover itself shows: a
        // typed query has its own pending search, and a retry would cancel it.
        val cache = MemoryDiscoverCache()
        storedDiscover(cache, now = 0L)
        var searches = 0
        val online = MutableStateFlow(false)
        val vm = viewModel(
            FakeRepo(
                discoverResult = { _, _, _ -> Result.failure(MetadataError.Offline()) },
                searchResult = { page ->
                    searches++
                    Result.success(PagedResult(listOf(drama), page, page))
                },
            ),
            cache,
            FakeClock(epochMs = DiscoverFeed.TTL_MS + 1),
            connectivity = object : ConnectivityMonitor {
                override val isOnline: Flow<Boolean> = online
            },
        )
        advanceUntilIdle()
        assertTrue(vm.state.value.discoverSaved)

        vm.onQueryChange("drama")
        online.value = true
        advanceUntilIdle()

        assertTrue(searches > 0, "the typed search ran: the reconnection did not cancel it")
    }

    @Test
    fun a_muting_change_during_a_pending_search_forces_the_refresh_once_the_query_clears() = runTest {
        // A stored copy is fresh, so Discover does not reach TMDB. Unmuting while a query is pending must still
        // reach TMDB when the query clears: the stored answer was filtered by the old muting.
        val cache = MemoryDiscoverCache()
        storedDiscover(cache, now = 0L)
        var discoverCalls = 0
        val settings = FakeMutingApi(MutedGenres(setOf(talk)))
        val vm = viewModel(
            FakeRepo(
                discoverResult = { _, page, _ ->
                    discoverCalls++
                    Result.success(PagedResult(listOf(drama), page, page))
                },
            ),
            cache,
            FakeClock(epochMs = 1_000L),
            settings = settings,
        )
        advanceUntilIdle()
        assertEquals(0, discoverCalls, "a fresh stored copy is shown without a request")

        vm.onQueryChange("drama")
        settings.configured.value = MutedGenres.NONE
        vm.onQueryChange("")
        advanceUntilIdle()

        assertTrue(discoverCalls > 0, "the muting change reached TMDB when Discover came back")
    }

    @Test
    fun a_cancelled_forced_refresh_leaves_the_unmuted_genre_owed_on_the_next_reload() = runTest {
        // Unmute during a pending search, clear the query, and hold the TMDB request. Another query cancels it; clearing
        // that query must still send the refresh, and its answer carries the unmuted genre.
        val cache = MemoryDiscoverCache()
        storedDiscover(cache, now = 0L)
        val settings = FakeMutingApi(MutedGenres(setOf(talk)))
        val gate = CompletableDeferred<Unit>()
        var holding = false
        val vm = viewModel(
            FakeRepo(
                discoverGate = { _, _ -> if (holding) gate.await() },
                discoverResult = { type, page, _ ->
                    Result.success(PagedResult(if (type == MediaType.TV) listOf(talkShow, drama) else emptyList(), page, page))
                },
            ),
            cache,
            FakeClock(epochMs = 1_000L),
            settings = settings,
        )
        advanceUntilIdle()
        assertEquals(listOf(drama), vm.state.value.popularTv, "the talk show is muted in the stored copy")

        holding = true
        vm.onQueryChange("drama")
        settings.configured.value = MutedGenres.NONE
        vm.onQueryChange("")
        advanceUntilIdle() // the forced refresh is now held on the gate
        vm.onQueryChange("dra") // cancels that refresh while it waits
        vm.onQueryChange("")
        advanceUntilIdle()
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf(talkShow, drama), vm.state.value.popularTv, "the next reload refreshed, so the unmuted talk show is back")
    }

    /** #974: a refresh the person asks for says so when it fails, even while the copy is inside its TTL. */
    @Test
    fun a_refresh_the_person_asked_for_says_so_when_it_fails_inside_the_ttl() = runTest {
        val cache = MemoryDiscoverCache()
        storedDiscover(cache, now = 0L)
        val vm = viewModel(offline(), cache, FakeClock(epochMs = 1_000L))
        advanceUntilIdle()
        assertFalse(vm.state.value.discoverSaved, "the fresh copy needed no refresh, so nothing failed")

        vm.retry()
        advanceUntilIdle()

        assertEquals(listOf(talkShow, drama), vm.state.value.popularTv, "the stored shelf stays on screen")
        assertNull(vm.state.value.error, "a stored copy is never an error")
        assertTrue(vm.state.value.discoverSaved, "the person asked for the refresh and it failed: say so")
    }

    /** #974: an automatic refresh keeps the silent rule while the copy is inside its TTL. */
    @Test
    fun an_automatic_refresh_that_fails_inside_the_ttl_stays_silent() = runTest {
        val cache = MemoryDiscoverCache()
        storedDiscover(cache, now = 0L)
        val settings = FakeMutingApi()
        val vm = viewModel(offline(), cache, FakeClock(epochMs = 1_000L), settings = settings)
        advanceUntilIdle()

        // A muting change refreshes Discover without anyone asking for it.
        settings.configured.value = MutedGenres(setOf(talk))
        advanceUntilIdle()

        assertEquals(listOf(drama), vm.state.value.popularTv, "the copy is re-filtered for the new muting")
        assertFalse(vm.state.value.discoverSaved, "an automatic refresh inside the TTL is silent, as before")
    }

    /** #980: a Retry that lands on an automatic refresh in flight is the person's request, so its failure is said. */
    @Test
    fun a_retry_that_lands_on_an_automatic_refresh_says_so_when_that_refresh_fails_inside_the_ttl() = runTest {
        val cache = MemoryDiscoverCache()
        storedDiscover(cache, now = 0L)
        val settings = FakeMutingApi()
        val gate = CompletableDeferred<Unit>()
        var holding = false
        val vm = viewModel(
            FakeRepo(
                discoverGate = { _, _ -> if (holding) gate.await() },
                discoverResult = { _, _, _ -> Result.failure(MetadataError.Offline()) },
            ),
            cache,
            FakeClock(epochMs = 1_000L),
            settings = settings,
        )
        advanceUntilIdle()

        // A muting change starts an automatic refresh, and it waits on the gate.
        holding = true
        settings.configured.value = MutedGenres(setOf(talk))
        advanceUntilIdle()
        assertTrue(vm.state.value.refreshing, "the automatic refresh is in flight")

        vm.retry()
        advanceUntilIdle()
        assertTrue(vm.state.value.refreshing, "the Retry does not restart or cancel the refresh in flight")
        assertFalse(vm.state.value.discoverSaved, "nothing has failed yet")

        gate.complete(Unit)
        advanceUntilIdle()

        assertTrue(vm.state.value.discoverSaved, "the person's Retry landed on that refresh, so its failure is said")
        assertEquals(listOf(drama), vm.state.value.popularTv, "the stored copy stays on screen")
    }

    /** #974: a refresh that succeeds clears the mark a failed one left. */
    @Test
    fun a_refresh_that_succeeds_clears_the_saved_mark() = runTest {
        val cache = MemoryDiscoverCache()
        storedDiscover(cache, now = 0L)
        var reachable = false
        val vm = viewModel(
            FakeRepo(
                discoverResult = { _, page, _ ->
                    if (reachable) Result.success(PagedResult(listOf(drama), page, page)) else Result.failure(MetadataError.Offline())
                },
            ),
            cache,
            FakeClock(epochMs = 1_000L),
        )
        advanceUntilIdle()
        vm.retry()
        advanceUntilIdle()
        assertTrue(vm.state.value.discoverSaved, "the refresh failed inside the TTL, and the person asked for it")

        reachable = true
        vm.retry()
        advanceUntilIdle()

        assertFalse(vm.state.value.discoverSaved, "the refresh answered, so the mark is gone")
        assertEquals(listOf(drama), vm.state.value.popularTv)
    }

    @Test
    fun a_cold_start_that_cannot_reach_the_network_keeps_the_error_state() = runTest {
        val vm = viewModel(FakeRepo(movieGenresResult = Result.failure(MetadataError.Offline())), MemoryDiscoverCache(), FakeClock(epochMs = 0L))
        advanceUntilIdle()

        assertNotNull(vm.state.value.error, "nothing stored and the network failed: the error state, as before")
        assertFalse(vm.state.value.discoverSaved)
    }
}

/** An in-memory [DiscoverCache]: what a device keeps between visits, for the Discover tests (#724). */
private class MemoryDiscoverCache : DiscoverCache {
    var language = "en-US"
    private val genres = mutableMapOf<Pair<MediaType, String>, Saved<List<Genre>>>()
    private val pages = mutableMapOf<Triple<MediaType, Int, String>, Saved<PagedResult<MediaSummary>>>()

    override fun language(): String = language

    override suspend fun genres(type: MediaType, language: String): Saved<List<Genre>>? = genres[type to language]
    override suspend fun saveGenres(type: MediaType, genres: List<Genre>, fetchedAtEpochMs: Long, language: String) {
        this.genres[type to language] = Saved(genres, fetchedAtEpochMs)
    }
    override suspend fun popular(type: MediaType, page: Int, language: String): Saved<PagedResult<MediaSummary>>? = pages[Triple(type, page, language)]
    override suspend fun savePopular(type: MediaType, page: Int, result: PagedResult<MediaSummary>, fetchedAtEpochMs: Long, language: String) {
        pages[Triple(type, page, language)] = Saved(result, fetchedAtEpochMs)
    }
}
