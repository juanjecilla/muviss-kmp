package com.codingpit.muviss.feature.search.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.codingpit.muviss.feature.collection.api.CollectionApi
import com.codingpit.muviss.feature.progress.api.ProgressApi
import com.codingpit.muviss.feature.search.domain.MediaDetailUseCase
import com.codingpit.muviss.feature.search.domain.WatchProvidersUseCase
import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.Season
import com.codingpit.muviss.models.WatchProviders
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DetailUiState(
    val loading: Boolean = true,
    val details: MediaDetails? = null,
    val error: String? = null,
    val saved: Boolean = false,
    val favorite: Boolean = false,
    /** Per-show new-episode notification opt-out (EPIC 5); only meaningful while [saved] is true. */
    val notificationsMuted: Boolean = false,
    /** Personal 1-10 rating, or null for unrated (EPIC 15); only meaningful while [saved] is true. */
    val rating: Int? = null,
    /** Personal free-text note, or null for none (EPIC 15); only meaningful while [saved] is true. */
    val note: String? = null,
    /** Seen episode ids (movies use the single id from [EpisodeId.forMovie]) — drives checkmarks, season bars, and the movie toggle. */
    val seenEpisodes: Set<EpisodeId> = emptySet(),
    /** Null while loading; an empty [WatchProviders] once loaded means "hide the section" — no failure surfaced, it's a nice-to-have. */
    val watchProviders: WatchProviders? = null,
) {
    fun isSeen(episodeId: EpisodeId): Boolean = episodeId in seenEpisodes

    fun seenCountIn(season: Season): Int = season.episodes.count { it.id in seenEpisodes }

    val movieWatched: Boolean
        get() = details?.let { EpisodeId.forMovie(it.id) in seenEpisodes } ?: false
}

/**
 * Loads a title's detail and mirrors its library membership and watch
 * progress through [CollectionApi] and [ProgressApi] — the collection and
 * progress features' public contracts, never their domain/data/ui — so this
 * feature can offer add/remove, favorite, and episode-tracking controls
 * without depending on how either is implemented.
 */
class DetailViewModel(
    private val mediaId: MediaId,
    private val loadDetail: MediaDetailUseCase,
    private val collectionApi: CollectionApi,
    private val progressApi: ProgressApi,
    private val loadWatchProviders: WatchProvidersUseCase,
) : ViewModel() {

    private val _state = MutableStateFlow(DetailUiState())
    val state: StateFlow<DetailUiState> = _state.asStateFlow()

    init {
        load()
        collectionApi.observeMembership(mediaId)
            .onEach { membership ->
                _state.update {
                    it.copy(
                        saved = membership != null,
                        favorite = membership?.favorite ?: false,
                        notificationsMuted = membership?.notificationsMuted ?: false,
                        rating = membership?.rating,
                        note = membership?.note,
                    )
                }
            }
            .launchIn(viewModelScope)
        progressApi.observeSeenEpisodes(mediaId)
            .onEach { seen -> _state.update { it.copy(seenEpisodes = seen) } }
            .launchIn(viewModelScope)
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            loadDetail(mediaId).fold(
                onSuccess = { d -> _state.update { it.copy(loading = false, details = d) } },
                onFailure = { e -> _state.update { it.copy(loading = false, error = e.message ?: "Something went wrong") } },
            )
        }
        loadWhereToWatch()
    }

    /**
     * Loaded independently of [load]: a provider outage here shouldn't block
     * showing the title's details, so failure just leaves the section hidden
     * rather than surfacing an error.
     */
    private fun loadWhereToWatch() {
        viewModelScope.launch {
            loadWatchProviders(mediaId).onSuccess { providers ->
                _state.update { it.copy(watchProviders = providers) }
            }
        }
    }

    /** Adds the loaded title to the library, or removes it if already saved. */
    fun toggleSaved() {
        val details = _state.value.details ?: return
        viewModelScope.launch {
            if (_state.value.saved) collectionApi.remove(mediaId) else collectionApi.add(details)
        }
    }

    fun toggleFavorite() {
        viewModelScope.launch { collectionApi.setFavorite(mediaId, !_state.value.favorite) }
    }

    /** Mutes/un-mutes this show's new-episode notifications (EPIC 5), independent of the global Settings toggle. */
    fun toggleNotificationsMuted() {
        viewModelScope.launch { collectionApi.setNotificationsMuted(mediaId, !_state.value.notificationsMuted) }
    }

    /** Sets the personal rating (1-10), or clears it (EPIC 15) if [rating] is the one already set — tapping the same star twice un-rates. */
    fun setRating(rating: Int) {
        val next = if (_state.value.rating == rating) null else rating
        viewModelScope.launch { collectionApi.setRating(mediaId, next) }
    }

    /** Explicitly clears the personal rating (EPIC 15). */
    fun clearRating() {
        viewModelScope.launch { collectionApi.setRating(mediaId, null) }
    }

    /** Persists the personal note (EPIC 15); collection's `:api` normalizes a blank note to null. */
    fun setNote(note: String) {
        viewModelScope.launch { collectionApi.setNote(mediaId, note) }
    }

    /** Ticks a single episode's checkmark. */
    fun toggleEpisodeSeen(episodeId: EpisodeId) {
        viewModelScope.launch { progressApi.setEpisodeSeen(episodeId, !_state.value.isSeen(episodeId)) }
    }

    /** Marks every episode in [season] as seen. */
    fun markSeasonSeen(season: Season) {
        viewModelScope.launch { progressApi.markSeasonSeen(season) }
    }

    /** "I'm caught up through here": marks every episode at or before [episodeId] as seen. */
    fun markPreviousSeen(episodeId: EpisodeId) {
        val seasons = _state.value.details?.seasons ?: return
        viewModelScope.launch { progressApi.markPreviousSeen(seasons, episodeId) }
    }

    /** Toggles a movie's watched flag. */
    fun toggleMovieWatched() {
        viewModelScope.launch { progressApi.setMovieWatched(mediaId, !_state.value.movieWatched) }
    }
}
