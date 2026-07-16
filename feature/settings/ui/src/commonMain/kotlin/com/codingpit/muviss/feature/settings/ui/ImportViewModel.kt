package com.codingpit.muviss.feature.settings.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.codingpit.muviss.feature.settings.domain.ImportActions
import com.codingpit.muviss.feature.settings.domain.ImportApplyResult
import com.codingpit.muviss.feature.settings.domain.ImportPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One step of the import flow — [ImportScreen] renders a different layout per step. */
sealed interface ImportStep {
    data object PickFile : ImportStep
    data class Resolving(val fileName: String, val done: Int, val total: Int) : ImportStep
    data class Preview(val fileName: String, val preview: ImportPreview) : ImportStep
    data class Applying(val done: Int, val total: Int) : ImportStep
    data class Summary(val result: ImportApplyResult) : ImportStep
}

data class ImportUiState(
    val step: ImportStep = ImportStep.PickFile,
    val error: String? = null,
)

/**
 * Drives the Settings screen's import flow (EPIC 18): pick a file, preview
 * (parse + id-resolve, reporting `n of m` as each title resolves), confirm,
 * apply (write to collection/progress/ratings, again reporting `n of m`),
 * then a result summary. [ImportActions] groups the two suspend entry points
 * (`PreviewImportUseCase`/`ApplyImportUseCase`) the same way `SettingsActions`
 * groups settings' own mutators.
 *
 * Picking the file itself is *not* driven from here: [rememberFileImporter]
 * is a `@Composable` factory (Android's impl needs an activity-result
 * launcher from the composition), so [ImportScreen] calls it directly and
 * hands the result to [onFilePicked] — the same split `SettingsScreen`/
 * `rememberDataExporter` already use for export, just inverted (there the
 * view model produces data for the platform seam to consume; here the
 * platform seam produces data for the view model to consume).
 */
class ImportViewModel(private val actions: ImportActions) : ViewModel() {

    private val _state = MutableStateFlow(ImportUiState())
    val state: StateFlow<ImportUiState> = _state.asStateFlow()

    private var lastPreview: ImportPreview? = null

    fun onFilePicked(fileName: String, content: String) {
        _state.update { ImportUiState(step = ImportStep.Resolving(fileName, 0, 0)) }
        viewModelScope.launch {
            runCatching {
                actions.preview(content) { done, total ->
                    _state.update { it.copy(step = ImportStep.Resolving(fileName, done, total)) }
                }
            }.fold(
                onSuccess = { preview ->
                    lastPreview = preview
                    _state.update { it.copy(step = ImportStep.Preview(fileName, preview), error = null) }
                },
                onFailure = { e -> _state.update { ImportUiState(error = e.message ?: DEFAULT_ERROR) } },
            )
        }
    }

    fun confirmApply() {
        val preview = lastPreview ?: return
        _state.update { it.copy(step = ImportStep.Applying(0, preview.resolved.size)) }
        viewModelScope.launch {
            val result = actions.apply(preview) { done, total ->
                _state.update { it.copy(step = ImportStep.Applying(done, total)) }
            }
            lastPreview = null
            _state.update { it.copy(step = ImportStep.Summary(result), error = null) }
        }
    }

    /** Discards the current preview and returns to the file-pick step, e.g. the user backs out before confirming. */
    fun cancelPreview() {
        lastPreview = null
        _state.update { ImportUiState() }
    }

    /** From the summary step, starts a fresh import. */
    fun startOver() {
        lastPreview = null
        _state.update { ImportUiState() }
    }

    private companion object {
        const val DEFAULT_ERROR = "Something went wrong reading that file"
    }
}
