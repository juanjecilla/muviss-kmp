package com.codingpit.muviss.feature.search.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.codingpit.muviss.feature.collection.api.CollectionApi
import com.codingpit.muviss.feature.search.domain.MediaDetailUseCase
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
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
)

/**
 * Loads a title's detail and mirrors its library membership through
 * [CollectionApi] — the collection feature's public contract, never its
 * domain/data/ui — so this feature can offer add/remove + favorite controls
 * without depending on how the library is implemented.
 */
class DetailViewModel(
    private val mediaId: MediaId,
    private val loadDetail: MediaDetailUseCase,
    private val collectionApi: CollectionApi,
) : ViewModel() {

    private val _state = MutableStateFlow(DetailUiState())
    val state: StateFlow<DetailUiState> = _state.asStateFlow()

    init {
        load()
        collectionApi.observeMembership(mediaId)
            .onEach { membership ->
                _state.update { it.copy(saved = membership != null, favorite = membership?.favorite ?: false) }
            }
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
}
