@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.search.ui

import com.codingpit.muviss.core.common.connectivity.ConnectivityMonitor
import com.codingpit.muviss.core.testing.FakeClock
import com.codingpit.muviss.feature.collection.api.CollectionSummary
import com.codingpit.muviss.feature.search.domain.DiscoverCache
import com.codingpit.muviss.feature.search.domain.DiscoverFeed
import com.codingpit.muviss.feature.search.domain.DiscoverMediaUseCase
import com.codingpit.muviss.feature.search.domain.ForYouAnswer
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
import com.codingpit.muviss.models.WatchStatus
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

/** #785: "For you" opens from its stored copy, and muting and the library are applied to that copy at read. */
class ForYouOfflineViewModelTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val seed = MediaId.tmdbMovie("1")
    private val newSeed = MediaId.tmdbMovie("9")
    private val recommended = MediaSummary(MediaId.tmdbMovie("50"), "Recommended")
    private val talkShow = MediaSummary(MediaId.tmdbTv("20"), "Late show", genreIds = listOf("10767"))
    private val talk = GenreKey(MediaType.TV, "10767")

    private fun favorite(id: MediaId, addedAt: Long = 0L) = CollectionSummary(
        id,
        "Saved",
        posterUrl = null,
        status = WatchStatus.NOT_STARTED,
        favorite = true,
        addedAtEpochMs = addedAt,
    )

    private fun plain(id: MediaId) = CollectionSummary(id, "Saved", posterUrl = null, status = WatchStatus.NOT_STARTED)

    /** Every recommendations request, so a test can prove an offline or muted change asked TMDB for nothing. */
    private class Asks {
        val seeds = mutableListOf<MediaId>()
    }

    private fun offlineRepo(asks: Asks, answers: Map<MediaId, List<MediaSummary>> = emptyMap()) = FakeRepo(
        movieGenresResult = Result.failure(MetadataError.Offline()),
        tvGenresResult = Result.failure(MetadataError.Offline()),
        discoverResult = { _, _, _ -> Result.failure(MetadataError.Offline()) },
        recommendationsResult = { id ->
            asks.seeds += id
            answers[id]?.let { Result.success(PagedResult(it, 1, 1)) } ?: Result.failure(MetadataError.Offline())
        },
    )

    @Suppress("LongParameterList") // one argument per fake; the connectivity signal is the one #924 added
    private fun viewModel(
        repo: FakeRepo,
        cache: DiscoverCache,
        library: FakeSearchCollectionApi,
        settings: FakeMutingApi = FakeMutingApi(),
        triage: FakeTriageApi = FakeTriageApi(),
        connectivity: ConnectivityMonitor = ConnectivityMonitor.AlwaysOnline,
    ) = SearchViewModel(
        SearchMediaUseCase(repo),
        DiscoverMediaUseCase(repo),
        RecommendationsUseCase(repo),
        library,
        triage,
        settings,
        DiscoverFeed(repo, cache, FakeClock(epochMs = 1_000L)),
        connectivity = connectivity,
    )

    @Test
    fun a_stored_copy_shows_offline_with_no_request() = runTest {
        val cache = ForYouMemoryCache()
        cache.saveForYou(listOf(seed), listOf(ForYouAnswer(seed, listOf(recommended), fetchedAtEpochMs = 0L)))
        val asks = Asks()

        val vm = viewModel(offlineRepo(asks), cache, FakeSearchCollectionApi(listOf(favorite(seed))))
        advanceUntilIdle()

        assertEquals(listOf(recommended), vm.state.value.forYou, "the stored copy is on screen with every request failing")
        assertEquals(emptyList(), asks.seeds, "the copy is fresh (6 hours is not up), so no request is made at all")
    }

    @Test
    fun a_mute_change_and_a_saved_title_re_filter_the_stored_copy_without_a_request() = runTest {
        val cache = ForYouMemoryCache()
        cache.saveForYou(listOf(seed), listOf(ForYouAnswer(seed, listOf(recommended, talkShow), fetchedAtEpochMs = 0L)))
        val library = FakeSearchCollectionApi(listOf(favorite(seed)))
        val settings = FakeMutingApi()
        val asks = Asks()
        val vm = viewModel(offlineRepo(asks), cache, library, settings)
        advanceUntilIdle()
        assertEquals(listOf(recommended, talkShow), vm.state.value.forYou)

        // The person saves the recommended title: same seed set, so the copy is read again, not refetched.
        library.library.value = listOf(favorite(seed), plain(recommended.id))
        advanceUntilIdle()
        assertEquals(listOf(talkShow), vm.state.value.forYou, "a title saved since the copy was stored never reappears")

        // Talk is muted: the stored talk show leaves, and nothing is asked of TMDB.
        settings.configured.value = MutedGenres(setOf(talk))
        advanceUntilIdle()
        assertEquals(emptyList(), vm.state.value.forYou)
        assertEquals(emptyList(), asks.seeds, "a library or mute change on a fresh copy never reaches the network")
    }

    @Test
    fun a_seed_change_is_a_miss_and_never_shows_the_previous_sets_titles_offline() = runTest {
        val cache = ForYouMemoryCache()
        cache.saveForYou(listOf(seed), listOf(ForYouAnswer(seed, listOf(recommended), fetchedAtEpochMs = 0L)))
        val library = FakeSearchCollectionApi(listOf(favorite(seed)))
        val vm = viewModel(offlineRepo(Asks()), cache, library)
        advanceUntilIdle()
        assertEquals(listOf(recommended), vm.state.value.forYou)

        // A new favourite is a new seed set: nothing is stored for it, and the request fails offline.
        library.library.value = listOf(favorite(seed), favorite(newSeed, addedAt = 10L))
        advanceUntilIdle()

        assertEquals(emptyList(), vm.state.value.forYou, "the old set's copy is not used for the new one")
    }

    @Test
    fun an_online_load_with_no_copy_fetches_and_stores_it() = runTest {
        val cache = ForYouMemoryCache()
        val repo = FakeRepo(recommendationsResult = { id -> Result.success(PagedResult(if (id == seed) listOf(recommended) else emptyList(), 1, 1)) })

        val vm = viewModel(repo, cache, FakeSearchCollectionApi(listOf(favorite(seed))))
        advanceUntilIdle()

        assertEquals(listOf(recommended), vm.state.value.forYou)
        assertEquals(listOf(recommended.id), cache.forYou(listOf(seed))?.single()?.items?.map { it.id }, "what TMDB answered is stored for the next offline open")
    }

    /** #924: a seed that failed while offline is asked again when the connection returns, and only that seed. */
    @Test
    fun a_reconnection_asks_again_for_a_seed_that_failed_offline() = runTest {
        val online = MutableStateFlow(false)
        var reachable = false
        val asks = Asks()
        val repo = FakeRepo(recommendationsResult = { id ->
            asks.seeds += id
            if (reachable) Result.success(PagedResult(listOf(recommended), 1, 1)) else Result.failure(MetadataError.Offline())
        })

        val vm = viewModel(repo, ForYouMemoryCache(), FakeSearchCollectionApi(listOf(favorite(seed))), connectivity = monitorOf(online))
        advanceUntilIdle()
        assertEquals(emptyList(), vm.state.value.forYou)
        assertEquals(listOf(seed), asks.seeds)

        reachable = true
        online.value = true
        advanceUntilIdle()

        assertEquals(listOf(recommended), vm.state.value.forYou, "the seed is asked again once the connection is back")
        assertEquals(listOf(seed, seed), asks.seeds, "one more request, for the seed that failed")
    }

    /** #974: a forced For you request that fails says so inside the TTL, and one that answers clears it. */
    @Test
    fun a_forced_for_you_request_that_fails_inside_the_ttl_says_so_until_one_answers() = runTest {
        val cache = ForYouMemoryCache()
        cache.saveForYou(listOf(seed), listOf(ForYouAnswer(seed, listOf(recommended), fetchedAtEpochMs = 1_000L)))
        val asks = Asks()
        var answering = false
        // Discover itself answers, so the mark can only come from For you.
        val repo = FakeRepo(recommendationsResult = { id ->
            asks.seeds += id
            if (answering) Result.success(PagedResult(listOf(talkShow), 1, 1)) else Result.failure(MetadataError.Offline())
        })
        val vm = viewModel(repo, cache, FakeSearchCollectionApi(listOf(favorite(seed))))
        advanceUntilIdle()
        assertFalse(vm.state.value.discoverSaved, "a fresh copy is answered without a request")

        vm.retry()
        advanceUntilIdle()
        assertEquals(listOf(recommended), vm.state.value.forYou, "the stored answer stays on screen")
        assertTrue(vm.state.value.discoverSaved, "the forced request failed inside the TTL: say so")

        answering = true
        vm.retry()
        advanceUntilIdle()
        assertEquals(listOf(talkShow), vm.state.value.forYou)
        assertFalse(vm.state.value.discoverSaved, "a forced request that answers clears the mark")
    }

    /** #974: an automatic For you request that fails stays silent inside the TTL. */
    @Test
    fun an_automatic_for_you_request_that_fails_stays_silent() = runTest {
        val cache = ForYouMemoryCache()
        cache.saveForYou(listOf(seed), listOf(ForYouAnswer(seed, listOf(recommended), fetchedAtEpochMs = 1_000L)))
        val asks = Asks()
        val repo = FakeRepo(recommendationsResult = { id ->
            asks.seeds += id
            Result.failure(MetadataError.Offline())
        })
        val library = FakeSearchCollectionApi(listOf(favorite(seed)))
        val vm = viewModel(repo, cache, library)
        advanceUntilIdle()

        // A saved title is a new seed set: the request for it is automatic, and it fails.
        library.library.value = listOf(favorite(seed), favorite(newSeed))
        advanceUntilIdle()

        assertTrue(asks.seeds.isNotEmpty(), "the new seed set was asked")
        assertFalse(vm.state.value.discoverSaved, "an automatic request that fails inside the TTL is silent")
    }

    /** #924: Discover's Retry refreshes For you too, even when its stored copy is still fresh. */
    @Test
    fun a_retry_on_discover_asks_for_every_for_you_seed_even_when_its_copy_is_fresh() = runTest {
        val cache = ForYouMemoryCache()
        cache.saveForYou(listOf(seed), listOf(ForYouAnswer(seed, listOf(recommended), fetchedAtEpochMs = 1_000L)))
        val asks = Asks()
        val repo = FakeRepo(recommendationsResult = { id ->
            asks.seeds += id
            Result.success(PagedResult(listOf(talkShow), 1, 1))
        })
        val vm = viewModel(repo, cache, FakeSearchCollectionApi(listOf(favorite(seed))))
        advanceUntilIdle()
        assertEquals(listOf(recommended), vm.state.value.forYou)
        assertEquals(emptyList(), asks.seeds, "a fresh copy is answered without a request")

        vm.retry()
        advanceUntilIdle()

        assertEquals(listOf(seed), asks.seeds, "Retry asks TMDB for the seed although its copy is fresh")
        assertEquals(listOf(talkShow), vm.state.value.forYou)
    }

    private fun monitorOf(online: Flow<Boolean>) = object : ConnectivityMonitor {
        override val isOnline: Flow<Boolean> = online
    }

    /**
     * Keeps one For you copy, the way the table does; the shelves are not under test here, so they store nothing.
     * The copy is stored raw, so the tests can see that muting and the library are applied only at read.
     */
    private class ForYouMemoryCache : DiscoverCache {
        private var copy: Pair<List<String>, List<ForYouAnswer>>? = null

        override suspend fun forYou(seeds: List<MediaId>): List<ForYouAnswer>? {
            val (keys, answers) = copy ?: return null
            if (keys != seeds.map { it.toString() }.sorted()) return null
            return seeds.mapNotNull { seed -> answers.firstOrNull { it.seed == seed } }
        }

        override suspend fun saveForYou(seeds: List<MediaId>, answers: List<ForYouAnswer>) {
            copy = seeds.map { it.toString() }.sorted() to answers
        }

        override suspend fun genres(type: MediaType): Saved<List<Genre>>? = null
        override suspend fun saveGenres(type: MediaType, genres: List<Genre>, fetchedAtEpochMs: Long) = Unit
        override suspend fun popular(type: MediaType, page: Int): Saved<PagedResult<MediaSummary>>? = null
        override suspend fun savePopular(type: MediaType, page: Int, result: PagedResult<MediaSummary>, fetchedAtEpochMs: Long) = Unit
    }
}
