package com.codingpit.muviss.feature.search.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.codingpit.muviss.core.common.crash.launchInReporting
import com.codingpit.muviss.core.common.crash.launchReporting
import com.codingpit.muviss.core.designsystem.text.UiText
import com.codingpit.muviss.core.designsystem.text.toUiText
import com.codingpit.muviss.feature.progress.api.EpisodePlay
import com.codingpit.muviss.feature.progress.api.ProgressApi
import com.codingpit.muviss.feature.search.domain.EpisodeDetailUseCase
import com.codingpit.muviss.feature.search.ui.generated.resources.Res
import com.codingpit.muviss.feature.search.ui.generated.resources.error_generic
import com.codingpit.muviss.models.EpisodeDetails
import com.codingpit.muviss.models.EpisodeId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update

data class EpisodeDetailUiState(
    val loading: Boolean = true,
    val details: EpisodeDetails? = null,
    val error: UiText? = null,
    /** Every recorded viewing, newest first (ADR 0011). Empty means never watched. */
    val plays: List<EpisodePlay> = emptyList(),
) {
    val playCount: Int get() = plays.size
    val seen: Boolean get() = plays.isNotEmpty()
}

/**
 * One episode's page: the source's own detail, plus the user's watch history
 * of it.
 *
 * The history comes from [ProgressApi] rather than the metadata source — it is
 * the one thing on this screen that belongs to the user rather than to TMDB,
 * and it is why this screen can offer "clear watch history", the deliberate,
 * destructive counterpart to the detail screen's "I ticked it by mistake".
 */
class EpisodeDetailViewModel(
    private val episodeId: EpisodeId,
    private val loadEpisode: EpisodeDetailUseCase,
    private val progressApi: ProgressApi,
) : ViewModel() {

    private val _state = MutableStateFlow(EpisodeDetailUiState())
    val state: StateFlow<EpisodeDetailUiState> = _state.asStateFlow()

    init {
        load()
        progressApi.observePlays(episodeId)
            .onEach { plays -> _state.update { it.copy(plays = plays) } }
            .launchInReporting(viewModelScope)
    }

    fun load() {
        viewModelScope.launchReporting {
            _state.update { it.copy(loading = true, error = null) }
            loadEpisode(episodeId).fold(
                onSuccess = { d -> _state.update { it.copy(loading = false, details = d) } },
                onFailure = { e -> _state.update { it.copy(loading = false, error = e.toUiText(UiText.Resource(Res.string.error_generic))) } },
            )
        }
    }

    /** Records another viewing. */
    fun recordRewatch() {
        viewModelScope.launchReporting { progressApi.recordPlay(episodeId) }
    }

    /** Drops the most recent viewing only — the same "that tick was a mistake" as the detail screen. */
    fun undoLatestPlay() {
        viewModelScope.launchReporting { progressApi.removeLatestPlay(episodeId) }
    }

    /** Forgets every viewing. The destructive one, reachable only from here. */
    fun clearHistory() {
        viewModelScope.launchReporting { progressApi.clearPlays(episodeId) }
    }
}
