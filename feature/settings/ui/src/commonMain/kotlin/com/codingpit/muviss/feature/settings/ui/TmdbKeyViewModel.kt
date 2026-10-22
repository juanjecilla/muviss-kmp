package com.codingpit.muviss.feature.settings.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.codingpit.muviss.core.common.crash.launchInReporting
import com.codingpit.muviss.core.common.crash.launchReporting
import com.codingpit.muviss.core.common.flags.FeatureFlags
import com.codingpit.muviss.core.designsystem.component.TmdbKeyProblem
import com.codingpit.muviss.feature.settings.api.SaveTmdbKeyResult
import com.codingpit.muviss.feature.settings.api.TmdbDataSource
import com.codingpit.muviss.feature.settings.domain.ClearTmdbKeyUseCase
import com.codingpit.muviss.feature.settings.domain.ObserveTmdbDataSourceUseCase
import com.codingpit.muviss.feature.settings.domain.SaveTmdbKeyUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update

data class TmdbKeyUiState(
    /** Whether Settings shows the row at all: the app offers it (Remote Config), or a key is already in use. */
    val visible: Boolean = false,
    val source: TmdbDataSource = TmdbDataSource.MUVISS,
    /** The key is being checked with TMDB; the dialog stays up and its buttons are inert. */
    val checking: Boolean = false,
    val problem: TmdbKeyProblem? = null,
)

/**
 * Settings > TMDB data source (EPIC 46, ADR 0027). Holds no key: the field's text
 * lives in the dialog and goes straight to [save], so a key is never in state that
 * a rotation, a screenshot of a state dump or a crash report could pick up.
 *
 * The row is offered while `byokEnabled` is on and **stays** while a key is in use
 * after it turns off — the switch stops the offer, it never strands anyone.
 */
class TmdbKeyViewModel(
    observeSource: ObserveTmdbDataSourceUseCase,
    featureFlags: FeatureFlags,
    private val saveKey: SaveTmdbKeyUseCase,
    private val clearKey: ClearTmdbKeyUseCase,
) : ViewModel() {

    private val _state = MutableStateFlow(TmdbKeyUiState())
    val state: StateFlow<TmdbKeyUiState> = _state.asStateFlow()

    init {
        combine(observeSource(), featureFlags.byokEnabled) { source, offered -> source to offered }
            .onEach { (source, offered) ->
                _state.update { it.copy(source = source, visible = offered || source == TmdbDataSource.YOURS) }
            }
            .launchInReporting(viewModelScope)
    }

    fun save(raw: String, onSaved: () -> Unit) {
        if (_state.value.checking) return
        _state.update { it.copy(checking = true, problem = null) }
        viewModelScope.launchReporting {
            // A store failure (Keystore, Keychain) throws out of saveKey; the
            // dialog is locked while `checking`, so it must be released anyway.
            var problem: TmdbKeyProblem? = TmdbKeyProblem.COULD_NOT_CHECK
            try {
                problem = when (saveKey(raw)) {
                    SaveTmdbKeyResult.SAVED -> null
                    SaveTmdbKeyResult.MALFORMED -> TmdbKeyProblem.MALFORMED
                    SaveTmdbKeyResult.REJECTED -> TmdbKeyProblem.REJECTED
                    SaveTmdbKeyResult.COULD_NOT_CHECK -> TmdbKeyProblem.COULD_NOT_CHECK
                }
            } finally {
                _state.update { it.copy(checking = false, problem = problem) }
            }
            if (problem == null) onSaved()
        }
    }

    fun useMuvissKey(onDone: () -> Unit) {
        viewModelScope.launchReporting {
            clearKey()
            _state.update { it.copy(problem = null) }
            onDone()
        }
    }

    /** Dropping the dialog forgets a problem that belonged to it. */
    fun dismissProblem() {
        _state.update { it.copy(problem = null) }
    }
}
