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
import com.codingpit.muviss.feature.settings.api.GenreMuting
import com.codingpit.muviss.feature.settings.api.SettingsApi
import com.codingpit.muviss.feature.triage.api.TriageApi
import com.codingpit.muviss.models.Genre
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.MediaType
import com.codingpit.muviss.models.MutedGenres
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
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
    /** The "show muted" chip is on: muted genres are back in Discover for this session (ADR 0031). */
    val showMuted: Boolean = false,
    /** Something is muted in Settings, so the chip has something to toggle. */
    val hasMuted: Boolean = false,
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
    private val settings: SettingsApi,
    connectivity: ConnectivityMonitor = ConnectivityMonitor.AlwaysOnline,
) : ViewModel() {

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    private var loadJob: Job? = null

    /** A reconnection came while nothing was failing yet; see the init block. */
    private var reconnectedBeforeFailure = false

    /** What Settings and the session chip say right now. Read by every load below. */
    private var muting = GenreMuting()

    /**
     * Muting changed while Discover was not on screen, so its carousels still carry the old exclusions.
     * They reload when the user comes back to it, instead of showing the wrong titles until the next retry.
     */
    private var discoverStale = false

    init {
        // The first Discover waits for the first muting, so it is never loaded unmuted and then replaced.
        // A later change reloads whichever mode is showing.
        var firstMuting = true
        settings.observeMuting()
            .onEach { next ->
                val changed = muting.effective != next.effective || muting.forSearch != next.forSearch
                muting = next
                _state.update { it.copy(showMuted = next.showMuted, hasMuted = next.hasMuted) }
                if (firstMuting) {
                    loadDiscover()
                } else if (changed) {
                    onMutingChanged()
                }
                firstMuting = false
            }
            .launchInReporting(viewModelScope)
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
            settings.observeMuting().map { it.effective }.distinctUntilChanged(),
        ) { library, decided, snoozed, muted -> Triple(library, decided + snoozed, muted) }
            .flatMapLatest { (library, decided, muted) -> forYouFlow(library, decided, muted) }
            .onEach { forYou -> _state.update { it.copy(forYou = forYou) } }
            .launchInReporting(viewModelScope)
        // A failure shown while offline retries itself when the connection
        // comes back, rather than waiting for a tap on Retry (#249).
        connectivity.reconnections()
            .onEach { if (_state.value.error != null) retry() else reconnectedBeforeFailure = true }
            .launchInReporting(viewModelScope)
        // A reconnection can land while a request is still in flight and fail
        // after it: remembered, it buys that failure one retry, no more (#249).
        _state.map { it.error != null }
            .distinctUntilChanged()
            .filter { showing -> showing && reconnectedBeforeFailure }
            .onEach {
                reconnectedBeforeFailure = false
                retry()
            }
            .launchInReporting(viewModelScope)
    }

    fun onQueryChange(query: String) {
        _state.update { it.copy(query = query) }
        loadJob?.cancel()
        if (query.isBlank()) {
            _state.update { it.copy(mode = SearchMode.DISCOVER, error = null, selectedGenre = null, selectedGenreType = null) }
            reloadDiscoverIfStale()
            return
        }
        loadJob = viewModelScope.launchReporting {
            delay(DEBOUNCE_MS)
            runSearch(query, page = 1)
        }
    }

    /** The chip: lifts muting for this session, or puts it back. Reloads through [SettingsApi.observeMuting]. */
    fun onShowMutedToggled() = settings.setShowMutedThisSession(!_state.value.showMuted)

    /** What a [type] browse leaves out: every muted genre except the one the user picked. */
    private fun mutedFor(type: MediaType, genreId: String?): MutedGenres = muting.effective.without(type, genreId)

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
            val muted = mutedFor(type, genre.id)
            discoverMedia(type, page = 1, genreId = genre.id, excludedGenreIds = muted.forType(type)).fold(
                onSuccess = { page ->
                    _state.update {
                        it.copy(loading = false, genreResults = page.items.filterNot(muted::hides), genrePage = page.page, genreTotalPages = page.totalPages)
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
        reloadDiscoverIfStale()
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

            SearchMode.SEARCH_RESULTS -> {
                loadJob?.cancel()
                loadJob = viewModelScope.launchReporting { runSearch(s.query, page = 1) }
            }

            SearchMode.GENRE_BROWSE -> {
                val genre = s.selectedGenre
                val type = s.selectedGenreType
                if (genre != null && type != null) selectGenre(genre, type)
            }
        }
    }

    /**
     * A muting change reloads whatever is showing. The one exception is a typed query still inside its
     * debounce: [mode] is still DISCOVER, but [retry] would cancel that pending search and nothing would
     * ever run it. Left alone it reads the new muting when it fires; Discover is marked stale for when
     * the query is cleared.
     */
    private fun onMutingChanged() {
        val s = _state.value
        val searchPending = s.mode == SearchMode.DISCOVER && s.query.isNotBlank()
        if (s.mode != SearchMode.DISCOVER || searchPending) discoverStale = true
        if (!searchPending) retry()
    }

    private fun reloadDiscoverIfStale() {
        if (discoverStale) loadDiscover()
    }

    private fun loadDiscover() {
        // A second load must not race the first: both read `muting` mid-flight, and whichever finishes
        // last wins, which could be the one that saw the older muting.
        loadJob?.cancel()
        discoverStale = false
        loadJob = viewModelScope.launchReporting {
            _state.update { it.copy(loading = true, error = null) }
            val movieGenres = genresUseCase(MediaType.MOVIE).getOrElse { return@launchReporting fail(it) }
            val tvGenres = genresUseCase(MediaType.TV).getOrElse { return@launchReporting fail(it) }
            val mutedMovies = mutedFor(MediaType.MOVIE, null)
            val mutedTv = mutedFor(MediaType.TV, null)
            val popularMovies = discoverMedia(MediaType.MOVIE, excludedGenreIds = mutedMovies.forType(MediaType.MOVIE)).getOrElse { return@launchReporting fail(it) }
            val popularTv = discoverMedia(MediaType.TV, excludedGenreIds = mutedTv.forType(MediaType.TV)).getOrElse { return@launchReporting fail(it) }
            _state.update {
                it.copy(
                    loading = false,
                    movieGenres = movieGenres,
                    tvGenres = tvGenres,
                    popularMovies = popularMovies.items.filterNot(mutedMovies::hides),
                    popularTv = popularTv.items.filterNot(mutedTv::hides),
                )
            }
        }
    }

    private fun loadMoreGenreResults(current: SearchUiState) {
        val genre = current.selectedGenre ?: return
        val type = current.selectedGenreType ?: return
        viewModelScope.launchReporting {
            _state.update { it.copy(loadingMore = true) }
            val muted = mutedFor(type, genre.id)
            val shown = current.genreResults.idSet()
            discoverMedia(type, current.genrePage + 1, genre.id, muted.forType(type), shown = shown).fold(
                onSuccess = { page ->
                    _state.update {
                        it.copy(
                            loadingMore = false,
                            genreResults = it.genreResults + page.items.filterNot(muted::hides),
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
        val shown = if (page == 1) emptySet() else _state.value.results.idSet()
        searchMedia(query, page, shown = shown).fold(
            onSuccess = { result ->
                _state.update {
                    // A later page for a query the user has since replaced is stale: appending it would mix two
                    // searches, and could repeat a title the new results already show.
                    if (page > 1 && it.query != query) return@update it.copy(loadingMore = false)
                    // Typed search is only muted when the user opted in (ADR 0031): looking a show up by name is
                    // an explicit act, unlike a feed that picked it for them.
                    val found = result.items.filterNot(muting.forSearch::hides)
                    val items = if (page == 1) found else it.results + found
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

    /** The ids on screen, handed to the paged use cases so a repeated title is dropped before it reaches the list. */
    private fun List<MediaSummary>.idSet(): Set<MediaId> = mapTo(mutableSetOf()) { it.id }

    /**
     * Seeds recommendations from [library]'s favorites/top-rated titles via
     * [ForYouSeeding.selectSeeds], fetches each seed's recommendations, and
     * merges them with [ForYouSeeding.mergeAndExclude]. Emits an empty list —
     * which hides the section — when the library carries no signal yet, same
     * "no error, just hidden" treatment as [DetailViewModel]'s watch-providers
     * and more-like-this rows.
     */
    private fun forYouFlow(library: List<CollectionSummary>, decidedIds: Set<MediaId>, muted: MutedGenres): Flow<List<MediaSummary>> = flow {
        val seeds = ForYouSeeding.selectSeeds(library)
        if (seeds.isEmpty()) {
            emit(emptyList())
            return@flow
        }
        val excluded = library.mapTo(mutableSetOf()) { it.mediaId } + decidedIds
        val recommendationsBySeed = seeds.map { seed -> recommendationsUseCase(seed).getOrNull()?.items.orEmpty() }
        emit(ForYouSeeding.mergeAndExclude(recommendationsBySeed, excluded).filterNot(muted::hides))
    }

    private companion object {
        const val DEBOUNCE_MS = 300L
    }
}
