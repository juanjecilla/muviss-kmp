@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.search.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.codingpit.muviss.feature.collection.api.CollectionApi
import com.codingpit.muviss.feature.collection.api.CollectionSummary
import com.codingpit.muviss.feature.search.domain.DiscoverMediaUseCase
import com.codingpit.muviss.feature.search.domain.ForYouSeeding
import com.codingpit.muviss.feature.search.domain.GenresUseCase
import com.codingpit.muviss.feature.search.domain.RecommendationsUseCase
import com.codingpit.muviss.feature.search.domain.SearchMediaUseCase
import com.codingpit.muviss.feature.triage.api.TriageApi
import com.codingpit.muviss.models.Genre
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.MediaType
import com.codingpit.muviss.models.toUserMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Which content [SearchUiState] is currently showing below the search field. */
enum class SearchMode {
    /** Blank query, no genre picked: genre chip rows + "Popular now" carousels. */
    DISCOVER,

    /** Blank query, a genre chip tapped: a paged grid for that genre. */
    GENRE_BROWSE,

    /** Non-blank query: a paged grid of search results. */
    SEARCH_RESULTS,
}

data class SearchUiState(
    val query: String = "",
    val mode: SearchMode = SearchMode.DISCOVER,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val error: String? = null,
    // SEARCH_RESULTS
    val results: List<MediaSummary> = emptyList(),
    val resultsPage: Int = 1,
    val resultsTotalPages: Int = 1,
    // DISCOVER
    val movieGenres: List<Genre> = emptyList(),
    val tvGenres: List<Genre> = emptyList(),
    val popularMovies: List<MediaSummary> = emptyList(),
    val popularTv: List<MediaSummary> = emptyList(),
    /** "For you" section (EPIC 16), seeded from library favorites/top-rated titles; empty hides the section (see [ForYouSeeding]). */
    val forYou: List<MediaSummary> = emptyList(),
    // GENRE_BROWSE
    val selectedGenre: Genre? = null,
    val selectedGenreType: MediaType? = null,
    val genreResults: List<MediaSummary> = emptyList(),
    val genrePage: Int = 1,
    val genreTotalPages: Int = 1,
) {
    val canLoadMoreResults: Boolean get() = resultsPage < resultsTotalPages
    val canLoadMoreGenreResults: Boolean get() = genrePage < genreTotalPages
}

/**
 * Drives the search screen: a debounced, paged query; a discover browse
 * (genre chips + popularity carousels, plus a "For you" section seeded from
 * the library — EPIC 16) shown while the query is blank; and a paged genre
 * drill-down when a chip is tapped. Progress is not touched here — this
 * feature is read-only discovery.
 */
class SearchViewModel(
    private val searchMedia: SearchMediaUseCase,
    private val discoverMedia: DiscoverMediaUseCase,
    private val genresUseCase: GenresUseCase,
    private val recommendationsUseCase: RecommendationsUseCase,
    private val collectionApi: CollectionApi,
    private val triageApi: TriageApi,
) : ViewModel() {

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    private var loadJob: Job? = null

    init {
        loadDiscover()
        // Independent of loadDiscover's one-shot load: the library can change
        // any time (a favorite toggled, a new top-rated title added), so this
        // stays subscribed and re-derives "For you" reactively, same pattern
        // as progress:ui's UpcomingViewModel reacting to collectionApi.
        // Skipped titles are excluded alongside saved ones — see
        // ForYouSeeding.mergeAndExclude for why only this surface filters them.
        combine(collectionApi.observeSummaries(), triageApi.observeDecidedIds(), ::Pair)
            .flatMapLatest { (library, decided) -> forYouFlow(library, decided) }
            .onEach { forYou -> _state.update { it.copy(forYou = forYou) } }
            .launchIn(viewModelScope)
    }

    fun onQueryChange(query: String) {
        _state.update { it.copy(query = query) }
        loadJob?.cancel()
        if (query.isBlank()) {
            _state.update { it.copy(mode = SearchMode.DISCOVER, error = null, selectedGenre = null, selectedGenreType = null) }
            return
        }
        loadJob = viewModelScope.launch {
            delay(DEBOUNCE_MS)
            runSearch(query, page = 1)
        }
    }

    /** Switches to [SearchMode.GENRE_BROWSE] and loads its first page. */
    fun selectGenre(genre: Genre, type: MediaType) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    mode = SearchMode.GENRE_BROWSE,
                    selectedGenre = genre,
                    selectedGenreType = type,
                    loading = true,
                    error = null,
                )
            }
            discoverMedia(type, page = 1, genreId = genre.id).fold(
                onSuccess = { page ->
                    _state.update {
                        it.copy(loading = false, genreResults = page.items, genrePage = page.page, genreTotalPages = page.totalPages)
                    }
                },
                onFailure = { e -> _state.update { it.copy(loading = false, error = e.toUserMessage(DEFAULT_ERROR)) } },
            )
        }
    }

    /** Leaves [SearchMode.GENRE_BROWSE] back to the discover carousels. */
    fun clearGenre() {
        loadJob?.cancel()
        _state.update {
            it.copy(
                mode = SearchMode.DISCOVER,
                selectedGenre = null,
                selectedGenreType = null,
                genreResults = emptyList(),
                genrePage = 1,
                genreTotalPages = 1,
                error = null,
            )
        }
    }

    /** Loads the next page for whichever paged mode is active; a no-op in [SearchMode.DISCOVER]. */
    fun loadMore() {
        val s = _state.value
        if (s.loadingMore) return
        when (s.mode) {
            SearchMode.SEARCH_RESULTS -> if (s.canLoadMoreResults) viewModelScope.launch { runSearch(s.query, s.resultsPage + 1) }
            SearchMode.GENRE_BROWSE -> if (s.canLoadMoreGenreResults) loadMoreGenreResults(s)
            SearchMode.DISCOVER -> Unit
        }
    }

    fun retry() {
        val s = _state.value
        when (s.mode) {
            SearchMode.DISCOVER -> loadDiscover()

            SearchMode.SEARCH_RESULTS -> loadJob = viewModelScope.launch { runSearch(s.query, page = 1) }

            SearchMode.GENRE_BROWSE -> {
                val genre = s.selectedGenre
                val type = s.selectedGenreType
                if (genre != null && type != null) selectGenre(genre, type)
            }
        }
    }

    private fun loadDiscover() {
        loadJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            val movieGenres = genresUseCase(MediaType.MOVIE).getOrElse { return@launch fail(it) }
            val tvGenres = genresUseCase(MediaType.TV).getOrElse { return@launch fail(it) }
            val popularMovies = discoverMedia(MediaType.MOVIE).getOrElse { return@launch fail(it) }
            val popularTv = discoverMedia(MediaType.TV).getOrElse { return@launch fail(it) }
            _state.update {
                it.copy(
                    loading = false,
                    movieGenres = movieGenres,
                    tvGenres = tvGenres,
                    popularMovies = popularMovies.items,
                    popularTv = popularTv.items,
                )
            }
        }
    }

    private fun loadMoreGenreResults(current: SearchUiState) {
        val genre = current.selectedGenre ?: return
        val type = current.selectedGenreType ?: return
        viewModelScope.launch {
            _state.update { it.copy(loadingMore = true) }
            discoverMedia(type, current.genrePage + 1, genre.id).fold(
                onSuccess = { page ->
                    _state.update {
                        it.copy(
                            loadingMore = false,
                            genreResults = it.genreResults + page.items,
                            genrePage = page.page,
                            genreTotalPages = page.totalPages,
                        )
                    }
                },
                onFailure = { e -> _state.update { it.copy(loadingMore = false, error = e.toUserMessage(DEFAULT_ERROR)) } },
            )
        }
    }

    private suspend fun runSearch(query: String, page: Int) {
        if (page == 1) {
            _state.update { it.copy(mode = SearchMode.SEARCH_RESULTS, loading = true, error = null) }
        } else {
            _state.update { it.copy(loadingMore = true) }
        }
        searchMedia(query, page).fold(
            onSuccess = { result ->
                _state.update {
                    val items = if (page == 1) result.items else it.results + result.items
                    it.copy(
                        loading = false,
                        loadingMore = false,
                        mode = SearchMode.SEARCH_RESULTS,
                        results = items,
                        resultsPage = result.page,
                        resultsTotalPages = result.totalPages,
                    )
                }
            },
            onFailure = { e -> _state.update { it.copy(loading = false, loadingMore = false, error = e.toUserMessage(DEFAULT_ERROR)) } },
        )
    }

    private fun fail(e: Throwable) {
        _state.update { it.copy(loading = false, error = e.toUserMessage(DEFAULT_ERROR)) }
    }

    /**
     * Seeds recommendations from [library]'s favorites/top-rated titles via
     * [ForYouSeeding.selectSeeds], fetches each seed's recommendations, and
     * merges them with [ForYouSeeding.mergeAndExclude]. Emits an empty list —
     * which hides the section — when the library carries no signal yet, same
     * "no error, just hidden" treatment as [DetailViewModel]'s watch-providers
     * and more-like-this rows.
     */
    private fun forYouFlow(library: List<CollectionSummary>, decidedIds: Set<MediaId>): Flow<List<MediaSummary>> = flow {
        val seeds = ForYouSeeding.selectSeeds(library)
        if (seeds.isEmpty()) {
            emit(emptyList())
            return@flow
        }
        val excluded = library.mapTo(mutableSetOf()) { it.mediaId } + decidedIds
        val recommendationsBySeed = seeds.map { seed -> recommendationsUseCase(seed).getOrNull()?.items.orEmpty() }
        emit(ForYouSeeding.mergeAndExclude(recommendationsBySeed, excluded))
    }

    private companion object {
        const val DEBOUNCE_MS = 300L
        const val DEFAULT_ERROR = "Something went wrong"
    }
}
