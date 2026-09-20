@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.search.ui

import app.cash.turbine.test
import com.codingpit.muviss.feature.collection.api.CollectionApi
import com.codingpit.muviss.feature.collection.api.CollectionMembership
import com.codingpit.muviss.feature.collection.api.CollectionSummary
import com.codingpit.muviss.feature.collection.api.NewEpisodesResult
import com.codingpit.muviss.feature.search.domain.DiscoverMediaUseCase
import com.codingpit.muviss.feature.search.domain.GenresUseCase
import com.codingpit.muviss.feature.search.domain.RecommendationsUseCase
import com.codingpit.muviss.feature.search.domain.SearchMediaUseCase
import com.codingpit.muviss.feature.search.domain.SearchRepository
import com.codingpit.muviss.feature.triage.api.TriageVerdict
import com.codingpit.muviss.models.Genre
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.MediaType
import com.codingpit.muviss.models.MetadataError
import com.codingpit.muviss.models.PagedResult
import com.codingpit.muviss.models.WatchProviders
import com.codingpit.muviss.models.WatchStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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

class SearchViewModelTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(
        repo: FakeRepo,
        collectionApi: FakeSearchCollectionApi = FakeSearchCollectionApi(),
        triageApi: FakeTriageApi = FakeTriageApi(),
    ) = SearchViewModel(
        SearchMediaUseCase(repo),
        DiscoverMediaUseCase(repo),
        GenresUseCase(repo),
        RecommendationsUseCase(repo),
        collectionApi,
        triageApi,
    )

    private val movieGenre = Genre("28", "Action")
    private val tvGenre = Genre("10759", "Action & Adventure")
    private val popularMovie = MediaSummary(MediaId.tmdbMovie("1"), "Popular movie")
    private val popularTv = MediaSummary(MediaId.tmdbTv("2"), "Popular show")
    private val searchItem = MediaSummary(MediaId.tmdbTv("3"), "Result")

    @Test
    fun loads_discover_browse_on_init() = runTest {
        val vm = viewModel(
            FakeRepo(
                movieGenresResult = Result.success(listOf(movieGenre)),
                tvGenresResult = Result.success(listOf(tvGenre)),
                discoverResult = { type, page, _ ->
                    Result.success(PagedResult(if (type == MediaType.MOVIE) listOf(popularMovie) else listOf(popularTv), page, page))
                },
            ),
        )
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(SearchMode.DISCOVER, state.mode)
        assertEquals(listOf(movieGenre), state.movieGenres)
        assertEquals(listOf(tvGenre), state.tvGenres)
        assertEquals(listOf(popularMovie), state.popularMovies)
        assertEquals(listOf(popularTv), state.popularTv)
    }

    @Test
    fun discover_failure_surfaces_error() = runTest {
        val vm = viewModel(FakeRepo(movieGenresResult = Result.failure(MetadataError.Offline())))
        vm.state.test {
            var current = awaitItem()
            while (current.error == null) current = awaitItem()
            assertEquals(MetadataError.Offline().userMessage, current.error)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun a_raw_exception_message_never_reaches_the_screen() = runTest {
        val leaky = RuntimeException("Request timeout has expired [url=https://api.themoviedb.org/3/search/multi?api_key=SECRET]")
        val vm = viewModel(FakeRepo(movieGenresResult = Result.failure(leaky)))
        vm.state.test {
            var current = awaitItem()
            while (current.error == null) current = awaitItem()
            assertEquals("Something went wrong", current.error)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun a_rate_limit_shows_its_own_copy_on_search() = runTest {
        val vm = viewModel(FakeRepo(searchResult = { Result.failure(MetadataError.RateLimited(retryAfterSeconds = 30)) }))
        advanceUntilIdle()

        vm.onQueryChange("matrix")
        advanceUntilIdle()

        assertEquals(MetadataError.RateLimited().userMessage, vm.state.value.error)
    }

    @Test
    fun query_runs_search_after_debounce_and_switches_mode() = runTest {
        val vm = viewModel(FakeRepo(searchResult = { Result.success(PagedResult(listOf(searchItem), 1, 1)) }))
        advanceUntilIdle()

        vm.onQueryChange("matrix")
        advanceUntilIdle()

        assertEquals(SearchMode.SEARCH_RESULTS, vm.state.value.mode)
        assertEquals(listOf(searchItem), vm.state.value.results)
    }

    @Test
    fun blank_query_returns_to_discover_mode() = runTest {
        val vm = viewModel(FakeRepo())
        advanceUntilIdle()
        vm.onQueryChange("matrix")
        advanceUntilIdle()

        vm.onQueryChange("")
        assertEquals(SearchMode.DISCOVER, vm.state.value.mode)
    }

    @Test
    fun loadMore_appends_the_next_search_page() = runTest {
        val vm = viewModel(
            FakeRepo(
                searchResult = { page ->
                    val item = MediaSummary(MediaId.tmdbMovie(page.toString()), "Page $page")
                    Result.success(PagedResult(listOf(item), page, totalPages = 2))
                },
            ),
        )
        advanceUntilIdle()
        vm.onQueryChange("matrix")
        advanceUntilIdle()

        vm.loadMore()
        advanceUntilIdle()

        assertEquals(2, vm.state.value.results.size)
        assertEquals(2, vm.state.value.resultsPage)
        assertFalse(vm.state.value.canLoadMoreResults)
    }

    @Test
    fun loadMore_is_a_noop_once_the_last_page_is_reached() = runTest {
        val vm = viewModel(FakeRepo(searchResult = { Result.success(PagedResult(listOf(searchItem), 1, 1)) }))
        advanceUntilIdle()
        vm.onQueryChange("matrix")
        advanceUntilIdle()

        vm.loadMore()
        advanceUntilIdle()

        assertEquals(listOf(searchItem), vm.state.value.results)
    }

    @Test
    fun selectGenre_switches_to_genre_browse_and_loads_the_first_page() = runTest {
        val genreResult = MediaSummary(MediaId.tmdbMovie("42"), "Genre result")
        val vm = viewModel(
            FakeRepo(discoverResult = { _, page, _ -> Result.success(PagedResult(listOf(genreResult), page, totalPages = 2)) }),
        )
        advanceUntilIdle()

        vm.selectGenre(movieGenre, MediaType.MOVIE)
        advanceUntilIdle()

        assertEquals(SearchMode.GENRE_BROWSE, vm.state.value.mode)
        assertEquals(movieGenre, vm.state.value.selectedGenre)
        assertEquals(listOf(genreResult), vm.state.value.genreResults)
        assertTrue(vm.state.value.canLoadMoreGenreResults)
    }

    @Test
    fun loadMore_in_genre_browse_appends_the_next_page() = runTest {
        val vm = viewModel(
            FakeRepo(
                discoverResult = { _, page, _ ->
                    val item = MediaSummary(MediaId.tmdbMovie(page.toString()), "Page $page")
                    Result.success(PagedResult(listOf(item), page, totalPages = 2))
                },
            ),
        )
        advanceUntilIdle()
        vm.selectGenre(movieGenre, MediaType.MOVIE)
        advanceUntilIdle()

        vm.loadMore()
        advanceUntilIdle()

        assertEquals(2, vm.state.value.genreResults.size)
        assertFalse(vm.state.value.canLoadMoreGenreResults)
    }

    @Test
    fun clearGenre_returns_to_discover_mode_and_drops_genre_results() = runTest {
        val vm = viewModel(
            FakeRepo(discoverResult = { _, page, _ -> Result.success(PagedResult(listOf(popularMovie), page, page)) }),
        )
        advanceUntilIdle()
        vm.selectGenre(movieGenre, MediaType.MOVIE)
        advanceUntilIdle()

        vm.clearGenre()

        assertEquals(SearchMode.DISCOVER, vm.state.value.mode)
        assertEquals(null, vm.state.value.selectedGenre)
        assertTrue(vm.state.value.genreResults.isEmpty())
    }

    private fun libraryTitle(
        id: MediaId,
        favorite: Boolean = false,
        rating: Int? = null,
        addedAtEpochMs: Long = 0,
    ) = CollectionSummary(id, "Saved title", posterUrl = null, status = WatchStatus.NOT_STARTED, favorite = favorite, rating = rating, addedAtEpochMs = addedAtEpochMs)

    @Test
    fun forYou_is_hidden_when_the_library_has_no_favorite_or_top_rated_signal() = runTest {
        val library = listOf(libraryTitle(MediaId.tmdbMovie("1")), libraryTitle(MediaId.tmdbMovie("2"), rating = 5))
        val vm = viewModel(FakeRepo(), FakeSearchCollectionApi(library))
        advanceUntilIdle()

        assertTrue(vm.state.value.forYou.isEmpty())
    }

    @Test
    fun forYou_is_hidden_when_the_library_is_empty() = runTest {
        val vm = viewModel(FakeRepo(), FakeSearchCollectionApi(emptyList()))
        advanceUntilIdle()

        assertTrue(vm.state.value.forYou.isEmpty())
    }

    @Test
    fun forYou_is_populated_from_a_favorites_recommendations_when_signal_exists() = runTest {
        val seed = MediaId.tmdbMovie("1")
        val recommended = MediaSummary(MediaId.tmdbMovie("99"), "Recommended")
        val library = listOf(libraryTitle(seed, favorite = true))
        val vm = viewModel(
            FakeRepo(recommendationsResult = { id -> if (id == seed) Result.success(PagedResult(listOf(recommended), 1, 1)) else Result.success(PagedResult(emptyList(), 1, 1)) }),
            FakeSearchCollectionApi(library),
        )
        advanceUntilIdle()

        assertEquals(listOf(recommended), vm.state.value.forYou)
    }

    @Test
    fun forYou_excludes_titles_already_in_the_library() = runTest {
        val seed = MediaId.tmdbMovie("1")
        val alreadySaved = MediaId.tmdbMovie("2")
        val recommended = MediaSummary(alreadySaved, "Already saved")
        val library = listOf(libraryTitle(seed, favorite = true), libraryTitle(alreadySaved))
        val vm = viewModel(
            FakeRepo(recommendationsResult = { Result.success(PagedResult(listOf(recommended), 1, 1)) }),
            FakeSearchCollectionApi(library),
        )
        advanceUntilIdle()

        assertTrue(vm.state.value.forYou.isEmpty())
    }

    @Test
    fun forYou_excludes_titles_skipped_during_triage() = runTest {
        val seed = MediaId.tmdbMovie("1")
        val skipped = MediaId.tmdbMovie("2")
        val recommended = MediaSummary(skipped, "Skipped in triage")
        val vm = viewModel(
            FakeRepo(recommendationsResult = { Result.success(PagedResult(listOf(recommended), 1, 1)) }),
            FakeSearchCollectionApi(listOf(libraryTitle(seed, favorite = true))),
            FakeTriageApi(mapOf(skipped to TriageVerdict.SKIP)),
        )
        advanceUntilIdle()

        // "For you" is a suggestion surface, so suggesting something the user
        // explicitly rejected is the one place a skip must be respected.
        assertTrue(vm.state.value.forYou.isEmpty())
    }

    @Test
    fun search_results_still_return_a_skipped_title() = runTest {
        val skipped = MediaId.tmdbMovie("2")
        val result = MediaSummary(skipped, "Skipped in triage")
        val vm = viewModel(
            FakeRepo(searchResult = { Result.success(PagedResult(listOf(result), 1, 1)) }),
            FakeSearchCollectionApi(emptyList()),
            FakeTriageApi(mapOf(skipped to TriageVerdict.SKIP)),
        )
        vm.onQueryChange("skipped")
        advanceTimeBy(400)
        advanceUntilIdle()

        // Search is a lookup tool: it must return what was typed, skipped or
        // not, or the app simply looks broken.
        assertEquals(listOf(result), vm.state.value.results)
    }

    @Test
    fun popular_carousels_are_not_filtered_by_triage_decisions() = runTest {
        val skipped = MediaId.tmdbMovie("1")
        val popular = MediaSummary(skipped, "Popular but skipped")
        val vm = viewModel(
            FakeRepo(discoverResult = { type, _, _ -> Result.success(PagedResult(if (type == MediaType.MOVIE) listOf(popular) else emptyList(), 1, 1)) }),
            FakeSearchCollectionApi(emptyList()),
            FakeTriageApi(mapOf(skipped to TriageVerdict.SKIP)),
        )
        advanceUntilIdle()

        // The catalogue rows stay whole — filtering them would quietly
        // personalise browsing in a way nobody asked for.
        assertEquals(listOf(popular), vm.state.value.popularMovies)
    }

    @Test
    fun forYou_updates_reactively_when_the_library_changes() = runTest {
        val seed = MediaId.tmdbMovie("1")
        val recommended = MediaSummary(MediaId.tmdbMovie("99"), "Recommended")
        val collectionApi = FakeSearchCollectionApi(emptyList())
        val vm = viewModel(
            FakeRepo(recommendationsResult = { Result.success(PagedResult(listOf(recommended), 1, 1)) }),
            collectionApi,
        )
        advanceUntilIdle()
        assertTrue(vm.state.value.forYou.isEmpty())

        collectionApi.library.value = listOf(libraryTitle(seed, favorite = true))
        advanceUntilIdle()

        assertEquals(listOf(recommended), vm.state.value.forYou)
    }
}
