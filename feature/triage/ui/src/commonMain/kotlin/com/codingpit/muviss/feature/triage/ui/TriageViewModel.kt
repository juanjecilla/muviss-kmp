@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.triage.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.codingpit.muviss.core.common.analytics.AnalyticsTracker
import com.codingpit.muviss.core.common.flags.FeatureFlags
import com.codingpit.muviss.core.common.flags.TriageControlScheme
import com.codingpit.muviss.feature.triage.api.TriageVerdict
import com.codingpit.muviss.feature.triage.domain.DeckCursor
import com.codingpit.muviss.feature.triage.domain.DeckFilter
import com.codingpit.muviss.feature.triage.domain.LoadDeckGenresUseCase
import com.codingpit.muviss.feature.triage.domain.LoadDeckUseCase
import com.codingpit.muviss.feature.triage.domain.ObserveTutorialSeenUseCase
import com.codingpit.muviss.feature.triage.domain.TriageActions
import com.codingpit.muviss.feature.triage.domain.TriageEvent
import com.codingpit.muviss.models.Genre
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.MediaType
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A verdict the user can still take back, held only until the snackbar goes. */
data class UndoableDecision(
    val summary: MediaSummary,
    val verdict: TriageVerdict,
)

/** A verdict whose save/tick work failed after the decision itself was recorded. */
data class FailedCommit(
    val summary: MediaSummary,
    val verdict: TriageVerdict,
    val message: String,
)

data class TriageUiState(
    val loading: Boolean = true,
    val refilling: Boolean = false,
    val cards: List<MediaSummary> = emptyList(),
    val filter: DeckFilter = DeckFilter(),
    val movieGenres: List<Genre> = emptyList(),
    val tvGenres: List<Genre> = emptyList(),
    val controlScheme: TriageControlScheme = TriageControlScheme.DEFAULT,
    val tutorialVisible: Boolean = false,
    val exhausted: Boolean = false,
    val error: String? = null,
    val undoable: UndoableDecision? = null,
    val failedCommit: FailedCommit? = null,
) {
    val topCard: MediaSummary? get() = cards.firstOrNull()

    /** Drawn behind the top card so the deck reads as a stack. */
    val peekedCard: MediaSummary? get() = cards.getOrNull(1)

    /** Watching is never offered for a movie — a film is not something you are partway through. */
    val verdictsForTopCard: List<TriageVerdict>
        get() = topCard?.let { TriageVerdict.availableFor(it.type) } ?: emptyList()

    /** Genre chips follow the type filter; "all" shows none, because the two catalogues' genre ids differ. */
    val genresForFilter: List<Genre>
        get() = when (filter.type) {
            MediaType.MOVIE -> movieGenres
            MediaType.TV -> tvGenres
            null -> emptyList()
        }
}

/**
 * Drives the deck.
 *
 * Swipes commit **optimistically**: the card leaves immediately and the
 * verdict's side effects (a `details()` fetch, which for TV is several
 * sequential requests, then the collection save and the ticks) run off the
 * critical path. The decision row itself is written synchronously inside
 * `RecordDecisionUseCase` before any of that, so dedupe holds even when the
 * network work fails — the failure surfaces as a retryable banner instead.
 */
class TriageViewModel(
    private val loadDeck: LoadDeckUseCase,
    private val loadGenres: LoadDeckGenresUseCase,
    private val actions: TriageActions,
    observeTutorialSeen: ObserveTutorialSeenUseCase,
    featureFlags: FeatureFlags,
    private val analytics: AnalyticsTracker,
) : ViewModel() {

    private val _state = MutableStateFlow(TriageUiState())
    val state: StateFlow<TriageUiState> = _state.asStateFlow()

    private var cursor = DeckCursor()

    /** Everything already handed to the deck this session, so a refill never repeats a card. */
    private val shown = mutableSetOf<MediaId>()

    private var loadJob: Job? = null

    init {
        featureFlags.triageControlScheme
            .onEach { scheme -> _state.update { it.copy(controlScheme = scheme) } }
            .launchIn(viewModelScope)

        // Only the first emission matters: dismissing the tutorial must not
        // make it reappear, and re-opening it is an explicit user action.
        observeTutorialSeen()
            .take(1)
            .onEach { seen -> _state.update { it.copy(tutorialVisible = !seen) } }
            .launchIn(viewModelScope)

        analytics.track(TriageEvent.DeckOpened)
        viewModelScope.launch { loadGenreChips() }
        refill(reset = true)
        viewModelScope.launch { actions.retryUnresolved() }
    }

    fun onTypeFilterChange(type: MediaType?) {
        if (_state.value.filter.type == type) return
        // Genre ids are per-catalogue, so switching type drops the genre.
        _state.update { it.copy(filter = DeckFilter(type = type, genreId = null)) }
        refill(reset = true)
    }

    fun onGenreFilterChange(genreId: String?) {
        if (_state.value.filter.genreId == genreId) return
        _state.update { it.copy(filter = it.filter.copy(genreId = genreId)) }
        refill(reset = true)
    }

    fun onClearFilters() {
        if (_state.value.filter == DeckFilter()) return
        _state.update { it.copy(filter = DeckFilter()) }
        refill(reset = true)
    }

    fun onDecide(verdict: TriageVerdict, viaGesture: Boolean) {
        val summary = _state.value.topCard ?: return
        if (verdict !in TriageVerdict.availableFor(summary.type)) return

        analytics.track(
            TriageEvent.DecisionRecorded(verdict, viaGesture, _state.value.controlScheme.name),
        )
        // The card goes now; the network work follows.
        _state.update {
            it.copy(
                cards = it.cards.drop(1),
                undoable = UndoableDecision(summary, verdict),
                failedCommit = null,
            )
        }
        viewModelScope.launch {
            actions.record(summary, verdict).onFailure { error ->
                _state.update { current ->
                    current.copy(failedCommit = FailedCommit(summary, verdict, error.message ?: COMMIT_FAILED))
                }
            }
        }
        refillIfRunningLow()
    }

    fun onUndo() {
        val undoable = _state.value.undoable ?: return
        analytics.track(TriageEvent.DecisionUndone(undoable.verdict))
        _state.update {
            it.copy(
                // Back to the front of the deck, exactly where it was.
                cards = listOf(undoable.summary) + it.cards,
                undoable = null,
                failedCommit = null,
                exhausted = false,
            )
        }
        shown -= undoable.summary.id
        viewModelScope.launch { actions.undo(undoable.summary.id, undoable.verdict) }
    }

    fun onUndoDismissed() = _state.update { it.copy(undoable = null) }

    fun onRetryFailedCommit() {
        val failed = _state.value.failedCommit ?: return
        _state.update { it.copy(failedCommit = null) }
        viewModelScope.launch {
            actions.record(failed.summary, failed.verdict).onFailure { error ->
                _state.update { it.copy(failedCommit = failed.copy(message = error.message ?: COMMIT_FAILED)) }
            }
        }
    }

    fun onFailedCommitDismissed() = _state.update { it.copy(failedCommit = null) }

    fun onTutorialDismissed() {
        _state.update { it.copy(tutorialVisible = false) }
        viewModelScope.launch { actions.setTutorialSeen(true) }
    }

    fun onShowTutorial() = _state.update { it.copy(tutorialVisible = true) }

    fun retry() = refill(reset = true)

    private suspend fun loadGenreChips() {
        val movies = loadGenres(MediaType.MOVIE)
        val tv = loadGenres(MediaType.TV)
        _state.update { it.copy(movieGenres = movies, tvGenres = tv) }
    }

    private fun refillIfRunningLow() {
        if (_state.value.cards.size <= REFILL_THRESHOLD) refill(reset = false)
    }

    private fun refill(reset: Boolean) {
        loadJob?.cancel()
        if (reset) {
            cursor = DeckCursor()
            shown.clear()
            _state.update { it.copy(cards = emptyList(), loading = true, error = null, exhausted = false) }
        } else {
            _state.update { it.copy(refilling = true) }
        }

        loadJob = viewModelScope.launch {
            val filter = _state.value.filter
            loadDeck(filter, cursor, alreadyShown = shown).fold(
                onSuccess = { batch ->
                    cursor = batch.cursor
                    shown += batch.cards.map { it.id }
                    _state.update { current ->
                        val cards = current.cards + batch.cards
                        current.copy(
                            cards = cards,
                            loading = false,
                            refilling = false,
                            error = null,
                            exhausted = cards.isEmpty() && batch.exhausted,
                        )
                    }
                    if (batch.exhausted && _state.value.cards.isEmpty()) analytics.track(TriageEvent.DeckExhausted)
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(loading = false, refilling = false, error = error.message ?: LOAD_FAILED)
                    }
                },
            )
        }
    }

    private companion object {
        /** Refill while there are still cards left to swipe, so the deck never visibly stalls. */
        const val REFILL_THRESHOLD = 3
        const val LOAD_FAILED = "Couldn't load more titles."
        const val COMMIT_FAILED = "Couldn't save that one."
    }
}
