package com.codingpit.muviss.feature.settings.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.codingpit.muviss.core.common.crash.launchInReporting
import com.codingpit.muviss.core.common.crash.launchReporting
import com.codingpit.muviss.feature.settings.domain.LoadGenresUseCase
import com.codingpit.muviss.feature.settings.domain.ObserveSettingsUseCase
import com.codingpit.muviss.feature.settings.domain.SettingsActions
import com.codingpit.muviss.models.Genre
import com.codingpit.muviss.models.GenreKey
import com.codingpit.muviss.models.MediaType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update

/** One row of the picker: a genre of one type, and whether it is muted right now. */
data class GenreOption(val key: GenreKey, val name: String, val muted: Boolean)

data class MutedGenresUiState(
    val loading: Boolean = true,
    /** Every muted genre, always listed first and independent of the network, so the list renders offline. */
    val muted: List<GenreOption> = emptyList(),
    /** TMDB's full lists for the type being browsed, minus nothing: a muted one shows ticked. */
    val available: List<GenreOption> = emptyList(),
    val browsing: MediaType = MediaType.TV,
    val loadingAvailable: Boolean = true,
    /** The genre lists could not be fetched (offline). The muted list above still works. */
    val availableFailed: Boolean = false,
    val inSearch: Boolean = false,
)

/** Drives the Muted genres screen. */
class MutedGenresViewModel(
    private val observeSettings: ObserveSettingsUseCase,
    private val actions: SettingsActions,
    private val loadGenres: LoadGenresUseCase,
) : ViewModel() {

    private val _state = MutableStateFlow(MutedGenresUiState())
    val state: StateFlow<MutedGenresUiState> = _state.asStateFlow()

    private var catalog: List<Genre> = emptyList()

    init {
        observeSettings()
            .onEach { settings ->
                val muted = settings.mutedGenres.map { GenreOption(it.key, it.name, muted = true) }
                _state.update { it.copy(loading = false, muted = muted, inSearch = settings.mutedGenresInSearch, available = optionsFor(it.browsing, muted)) }
            }
            .catch { _state.update { it.copy(loading = false) } }
            .launchInReporting(viewModelScope)
        browse(MediaType.TV)
    }

    fun browse(type: MediaType) {
        _state.update { it.copy(browsing = type, loadingAvailable = true, availableFailed = false, available = emptyList()) }
        viewModelScope.launchReporting {
            val result = loadGenres(type)
            catalog = result.getOrDefault(emptyList())
            _state.update { it.copy(loadingAvailable = false, availableFailed = result.isFailure, available = optionsFor(type, it.muted)) }
        }
    }

    fun retry() = browse(_state.value.browsing)

    fun onToggle(option: GenreOption) {
        viewModelScope.launchReporting { actions.setGenreMuted(option.key, option.name, muted = !option.muted) }
    }

    fun onInSearchToggled(enabled: Boolean) {
        viewModelScope.launchReporting { actions.setMutedGenresInSearch(enabled) }
    }

    private fun optionsFor(type: MediaType, muted: List<GenreOption>): List<GenreOption> {
        val mutedKeys = muted.map { it.key }.toSet()
        return catalog.map { GenreOption(GenreKey(type, it.id), it.name, GenreKey(type, it.id) in mutedKeys) }
    }
}
