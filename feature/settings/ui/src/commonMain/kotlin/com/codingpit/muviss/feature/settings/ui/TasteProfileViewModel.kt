package com.codingpit.muviss.feature.settings.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.codingpit.muviss.core.common.crash.launchInReporting
import com.codingpit.muviss.core.common.crash.launchReporting
import com.codingpit.muviss.feature.settings.api.TasteItem
import com.codingpit.muviss.feature.settings.api.TasteKind
import com.codingpit.muviss.feature.settings.api.TasteProfileApi
import com.codingpit.muviss.feature.settings.domain.LoadGenresUseCase
import com.codingpit.muviss.feature.settings.domain.ObserveSettingsUseCase
import com.codingpit.muviss.feature.settings.domain.TasteCatalog
import com.codingpit.muviss.feature.settings.domain.TasteRules
import com.codingpit.muviss.models.Genre
import com.codingpit.muviss.models.GenreKey
import com.codingpit.muviss.models.MediaType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update

/** One chip in the editor: the [item], whether it is liked, and whether it is a Muted Genre, which cannot be liked. */
data class TasteOption(val item: TasteItem, val liked: Boolean, val muted: Boolean)

data class TasteProfileUiState(
    val loading: Boolean = true,
    val movieGenres: List<TasteOption> = emptyList(),
    val tvGenres: List<TasteOption> = emptyList(),
    /** TMDB's genre lists could not be fetched (offline). The fixed options still work; genres appear once a connection does. */
    val genresFailed: Boolean = false,
    val formats: List<TasteOption> = emptyList(),
    val services: List<TasteOption> = emptyList(),
    val languages: List<TasteOption> = emptyList(),
    val companies: List<TasteOption> = emptyList(),
)

/**
 * Drives the Taste Profile editor (EPIC 51, #402). The options are fixed except genres, which
 * TMDB supplies; what is liked and what is muted are both read live, so a genre muted in another
 * part of Settings shows as disabled here without a reload.
 */
class TasteProfileViewModel(
    private val api: TasteProfileApi,
    private val observeSettings: ObserveSettingsUseCase,
    private val loadGenres: LoadGenresUseCase,
) : ViewModel() {

    private val _state = MutableStateFlow(TasteProfileUiState())
    val state: StateFlow<TasteProfileUiState> = _state.asStateFlow()

    private var liked: Set<String> = emptySet()
    private var muted: Set<GenreKey> = emptySet()
    private var movieCatalog: List<Genre> = emptyList()
    private var tvCatalog: List<Genre> = emptyList()
    private var genresFailed = false
    private var loaded = false

    init {
        api.observe()
            .onEach { items ->
                liked = items.mapTo(linkedSetOf()) { it.id }
                publish()
            }
            .catch { publish() }
            .launchInReporting(viewModelScope)
        observeSettings()
            .map { settings -> settings.mutedGenres.mapTo(linkedSetOf()) { it.key } }
            .onEach { keys ->
                muted = keys
                publish()
            }
            .launchInReporting(viewModelScope)
        viewModelScope.launchReporting { loadCatalog() }
    }

    /** Liking or unliking one chip. A muted genre is disabled in the UI, so the refusal is only ever a race, and it is ignored. */
    fun onToggle(option: TasteOption) {
        viewModelScope.launchReporting { api.setLiked(option.item, liked = !option.liked) }
    }

    private suspend fun loadCatalog() {
        val movies = loadGenres(MediaType.MOVIE)
        val tv = loadGenres(MediaType.TV)
        movieCatalog = movies.getOrDefault(emptyList())
        tvCatalog = tv.getOrDefault(emptyList())
        genresFailed = movies.isFailure || tv.isFailure
        loaded = true
        publish()
    }

    private fun publish() {
        _state.update {
            TasteProfileUiState(
                loading = !loaded,
                movieGenres = genreOptions(MediaType.MOVIE, movieCatalog),
                tvGenres = genreOptions(MediaType.TV, tvCatalog),
                genresFailed = genresFailed,
                formats = fixedOptions(TasteCatalog.formats),
                services = fixedOptions(TasteCatalog.services),
                languages = fixedOptions(TasteCatalog.languages),
                companies = fixedOptions(TasteCatalog.companies),
            )
        }
    }

    private fun genreOptions(type: MediaType, catalog: List<Genre>): List<TasteOption> = catalog
        .map { TasteRules.genreItem(type, it.id, it.name) }
        .map { item -> option(item) }

    private fun fixedOptions(items: List<TasteItem>): List<TasteOption> = items.map { option(it) }

    private fun option(item: TasteItem): TasteOption {
        val isMuted = item.kind == TasteKind.GENRE && TasteRules.genreKeyOf(item)?.let { it in muted } == true
        return TasteOption(item = item, liked = item.id in liked, muted = isMuted)
    }
}
