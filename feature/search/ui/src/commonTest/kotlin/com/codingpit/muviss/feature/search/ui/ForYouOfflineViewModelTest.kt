@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.search.ui

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
import kotlin.test.assertNotNull

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

    private fun viewModel(
        repo: FakeRepo,
        cache: DiscoverCache,
        library: FakeSearchCollectionApi,
        settings: FakeMutingApi = FakeMutingApi(),
        triage: FakeTriageApi = FakeTriageApi(),
    ) = SearchViewModel(
        SearchMediaUseCase(repo),
        DiscoverMediaUseCase(repo),
        RecommendationsUseCase(repo),
        library,
        triage,
        settings,
        DiscoverFeed(repo, cache, FakeClock(epochMs = 1_000L)),
    )

    @Test
    fun a_stored_copy_shows_offline_with_no_request() = runTest {
        val cache = ForYouMemoryCache()
        cache.saveForYou(listOf(seed), listOf(ForYouAnswer(seed, listOf(recommended), fetchedAtEpochMs = 0L)), "en-US")
        val asks = Asks()

        val vm = viewModel(offlineRepo(asks), cache, FakeSearchCollectionApi(listOf(favorite(seed))))
        advanceUntilIdle()

        assertEquals(listOf(recommended), vm.state.value.forYou, "the stored copy is on screen with every request failing")
        assertEquals(emptyList(), asks.seeds, "the copy is fresh (6 hours is not up), so no request is made at all")
    }

    @Test
    fun a_mute_change_and_a_saved_title_re_filter_the_stored_copy_without_a_request() = runTest {
        val cache = ForYouMemoryCache()
        cache.saveForYou(listOf(seed), listOf(ForYouAnswer(seed, listOf(recommended, talkShow), fetchedAtEpochMs = 0L)), "en-US")
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
        cache.saveForYou(listOf(seed), listOf(ForYouAnswer(seed, listOf(recommended), fetchedAtEpochMs = 0L)), "en-US")
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
        assertEquals(listOf(recommended.id), cache.forYou(listOf(seed), "en-US")?.single()?.items?.map { it.id }, "what TMDB answered is stored for the next offline open")
    }

    /**
     * #785 (review of #926): when the seed set changes, the previous set's titles leave the row at once and the
     * skeleton stays up until the new set's own copy or answer lands. Its request is held here, so the middle of the
     * change is visible.
     */
    @Test
    fun a_new_seed_set_clears_the_previous_row_while_its_own_answer_is_pending() = runTest {
        val cache = ForYouMemoryCache()
        cache.saveForYou(listOf(seed), listOf(ForYouAnswer(seed, listOf(recommended), fetchedAtEpochMs = 1_000L)), "en-US")
        val hold = CompletableDeferred<Unit>()
        val repo = FakeRepo(recommendationsResult = { id ->
            if (id == newSeed) hold.await()
            Result.success(PagedResult(listOf(talkShow), 1, 1))
        })
        val library = FakeSearchCollectionApi(listOf(favorite(seed)))
        val vm = viewModel(repo, cache, library)
        advanceUntilIdle()
        assertEquals(listOf(recommended), vm.state.value.forYou, "the previous set's copy is on screen")

        library.library.value = listOf(favorite(seed), favorite(newSeed, addedAt = 10L))
        advanceUntilIdle()

        assertEquals(emptyList(), vm.state.value.forYou, "the previous set's titles do not stay on screen for the new set")
        assertEquals(true, vm.state.value.forYouLoading, "the row keeps loading until the new set's answer lands")
        hold.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(talkShow), vm.state.value.forYou, "the new set's answer is shown once it lands")
    }

    /**
     * Keeps one For you copy, the way the table does; the shelves are not under test here, so they store nothing.
     * The copy is stored raw, so the tests can see that muting and the library are applied only at read.
     */
    private class ForYouMemoryCache : DiscoverCache {
        /** The language the device is in; a run reads it once, when it starts. Every copy is filed under the run's language. */
        var language = "en-US"
        private var copy: Triple<List<String>, String, List<ForYouAnswer>>? = null

        override fun language(): String = language

        override suspend fun forYou(seeds: List<MediaId>, language: String): List<ForYouAnswer>? {
            val (keys, copyLanguage, answers) = copy ?: return null
            if (keys != seeds.map { it.toString() }.sorted() || copyLanguage != language) return null
            return seeds.mapNotNull { seed -> answers.firstOrNull { it.seed == seed } }
        }

        override suspend fun saveForYou(seeds: List<MediaId>, answers: List<ForYouAnswer>, language: String) {
            copy = Triple(seeds.map { it.toString() }.sorted(), language, answers)
        }

        override suspend fun genres(type: MediaType, language: String): Saved<List<Genre>>? = null
        override suspend fun saveGenres(type: MediaType, genres: List<Genre>, fetchedAtEpochMs: Long, language: String) = Unit
        override suspend fun popular(type: MediaType, page: Int, language: String): Saved<PagedResult<MediaSummary>>? = null
        override suspend fun savePopular(type: MediaType, page: Int, result: PagedResult<MediaSummary>, fetchedAtEpochMs: Long, language: String) = Unit
    }
}
