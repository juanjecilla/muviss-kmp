@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.search.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.codingpit.muviss.core.common.connectivity.ConnectivityMonitor
import com.codingpit.muviss.core.common.connectivity.reconnections
import com.codingpit.muviss.core.common.crash.launchInReporting
import com.codingpit.muviss.core.common.crash.launchReporting
import com.codingpit.muviss.core.designsystem.text.UiText
import com.codingpit.muviss.core.designsystem.text.toUiText
import com.codingpit.muviss.feature.collection.api.CollectionApi
import com.codingpit.muviss.feature.collection.api.CollectionSummary
import com.codingpit.muviss.feature.search.domain.DiscoverMediaUseCase
import com.codingpit.muviss.feature.search.domain.ForYouSeeding
import com.codingpit.muviss.feature.search.domain.GenresUseCase
import com.codingpit.muviss.feature.search.domain.RecommendationsUseCase
import com.codingpit.muviss.feature.search.domain.SearchMediaUseCase
import com.codingpit.muviss.feature.search.ui.generated.resources.Res
import com.codingpit.muviss.feature.search.ui.generated.resources.error_generic
import com.codingpit.muviss.feature.triage.api.TriageApi
import com.codingpit.muviss.models.Genre
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.MediaType
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update

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
    val error: UiText? = null,
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
@Suppress("LongParameterList") // the seventh is the connectivity signal (#249), defaulted so tests can leave it out
class SearchViewModel(
    private val searchMedia: SearchMediaUseCase,
    private val discoverMedia: DiscoverMediaUseCase,
    private val genresUseCase: GenresUseCase,
    private val recommendationsUseCase: RecommendationsUseCase,
    private val collectionApi: CollectionApi,
    private val triageApi: TriageApi,
    connectivity: ConnectivityMonitor = ConnectivityMonitor.AlwaysOnline,
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
        // Skipped titles are excluded alongside saved ones, and snoozed ones
        // with them (EPIC 42): "ask me later" is about the title, not about the
        // deck, so recommending it here an hour later contradicts what the user
        // just said. See ForYouSeeding.mergeAndExclude for why only this
        // surface filters any of them.
        combine(
            collectionApi.observeSummaries(),
            triageApi.observeDecidedIds(),
            triageApi.observeSnoozedIds(),
        ) { library, decided, snoozed -> library to (decided + snoozed) }
            .flatMapLatest { (library, decided) -> forYouFlow(library, decided) }
            .onEach { forYou -> _state.update { it.copy(forYou = forYou) } }
            .launchInReporting(viewModelScope)
        // A failure shown while offline retries itself when the connection
        // comes back, rather than waiting for a tap on Retry (#249).
        connectivity.reconnections()
            .onEach { if (_state.value.error != null) retry() }
            .launchInReporting(viewModelScope)
    }

    fun onQueryChange(query: String) {
        _state.update { it.copy(query = query) }
        loadJob?.cancel()
        if (query.isBlank()) {
            _state.update { it.copy(mode = SearchMode.DISCOVER, error = null, selectedGenre = null, selectedGenreType = null) }
            return
        }
        loadJob = viewModelScope.launchReporting {
            delay(DEBOUNCE_MS)
            runSearch(query, page = 1)
        }
    }

    /** Switches to [SearchMode.GENRE_BROWSE] and loads its first page. */
    fun selectGenre(genre: Genre, type: MediaType) {
        loadJob?.cancel()
        loadJob = viewModelScope.launchReporting {
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
                onFailure = { e -> _state.update { it.copy(loading = false, error = e.toUiText(UiText.Resource(Res.string.error_generic))) } },
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
            SearchMode.SEARCH_RESULTS -> if (s.canLoadMoreResults) viewModelScope.launchReporting { runSearch(s.query, s.resultsPage + 1) }
            SearchMode.GENRE_BROWSE -> if (s.canLoadMoreGenreResults) loadMoreGenreResults(s)
            SearchMode.DISCOVER -> Unit
        }
    }

    fun retry() {
        val s = _state.value
        when (s.mode) {
            SearchMode.DISCOVER -> loadDiscover()

            SearchMode.SEARCH_RESULTS -> loadJob = viewModelScope.launchReporting { runSearch(s.query, page = 1) }

            SearchMode.GENRE_BROWSE -> {
                val genre = s.selectedGenre
                val type = s.selectedGenreType
                if (genre != null && type != null) selectGenre(genre, type)
            }
        }
    }

    private fun loadDiscover() {
        loadJob = viewModelScope.launchReporting {
            _state.update { it.copy(loading = true, error = null) }
            val movieGenres = genresUseCase(MediaType.MOVIE).getOrElse { return@launchReporting fail(it) }
            val tvGenres = genresUseCase(MediaType.TV).getOrElse { return@launchReporting fail(it) }
            val popularMovies = discoverMedia(MediaType.MOVIE).getOrElse { return@launchReporting fail(it) }
            val popularTv = discoverMedia(MediaType.TV).getOrElse { return@launchReporting fail(it) }
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
        viewModelScope.launchReporting {
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
                onFailure = { e -> _state.update { it.copy(loadingMore = false, error = e.toUiText(UiText.Resource(Res.string.error_generic))) } },
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
            onFailure = { e -> _state.update { it.copy(loading = false, loadingMore = false, error = e.toUiText(UiText.Resource(Res.string.error_generic))) } },
        )
    }

    private fun fail(e: Throwable) {
        _state.update { it.copy(loading = false, error = e.toUiText(UiText.Resource(Res.string.error_generic))) }
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
    }
}
