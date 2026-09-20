@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.triage.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.codingpit.muviss.core.common.analytics.AnalyticsTracker
import com.codingpit.muviss.core.common.crash.launchInReporting
import com.codingpit.muviss.core.common.crash.launchReporting
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
import com.codingpit.muviss.models.toUserMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.update

/** How many cards are drawn behind the top one. */
const val BACKING_CARD_COUNT = 2

/** A verdict the user can still take back, held only until the snackbar goes. */
data class UndoableDecision(
    val summary: MediaSummary,
    val verdict: TriageVerdict,
)

/**
 * A card undo has just put back on top of the deck, so the screen can play its
 * exit animation backwards.
 *
 * [verdict] is what says *where* the card left from — the deck maps a verdict
 * to a direction and re-enters from there. [token] exists because neither the
 * id nor the verdict is enough to retrigger the effect: decide the same card
 * again and undo it again and both repeat, so a monotonic counter is what makes
 * the second undo a distinct event.
 */
data class RestoredCard(
    val id: MediaId,
    val verdict: TriageVerdict,
    val token: Long,
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
    val restored: RestoredCard? = null,
    /** The AND of the app-wide motion switch and triage's own — see [FeatureFlags]. */
    val deckAnimations: Boolean = true,
) {
    val topCard: MediaSummary? get() = cards.firstOrNull()

    /**
     * Drawn behind the top card so the deck reads as a queue rather than a
     * lone card. Two, because one rim reads as a shadow; a count is not
     * offered because `cards.size` is a paging artifact of `REFILL_THRESHOLD`,
     * not how many titles are actually left.
     */
    val backingCards: List<MediaSummary> get() = cards.drop(1).take(BACKING_CARD_COUNT)

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

    /** Monotonic, so undoing the same card twice reads as two separate events. */
    private var restoreToken = 0L

    init {
        featureFlags.triageControlScheme
            .onEach { scheme -> _state.update { it.copy(controlScheme = scheme) } }
            .launchInReporting(viewModelScope)

        // Combined here rather than in the screen: whether the deck animates is
        // one fact, and the UI should not have to know it is stored as two.
        combine(featureFlags.animationsEnabled, featureFlags.triageDeckAnimations) { app, deck -> app && deck }
            .onEach { enabled -> _state.update { it.copy(deckAnimations = enabled) } }
            .launchInReporting(viewModelScope)

        // Only the first emission matters: dismissing the tutorial must not
        // make it reappear, and re-opening it is an explicit user action.
        observeTutorialSeen()
            .take(1)
            .onEach { seen -> _state.update { it.copy(tutorialVisible = !seen) } }
            .launchInReporting(viewModelScope)

        analytics.track(TriageEvent.DeckOpened)
        viewModelScope.launchReporting { loadGenreChips() }
        refill(reset = true)
        viewModelScope.launchReporting { actions.retryUnresolved() }
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
                // Whatever undo last put back has now been decided again; a
                // stale value here would re-enter the *next* card from the side
                // the previous one came back on.
                restored = null,
            )
        }
        viewModelScope.launchReporting {
            actions.record(summary, verdict).onFailure { error ->
                _state.update { current ->
                    current.copy(failedCommit = FailedCommit(summary, verdict, error.toUserMessage(COMMIT_FAILED)))
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
                restored = RestoredCard(undoable.summary.id, undoable.verdict, ++restoreToken),
            )
        }
        shown -= undoable.summary.id
        viewModelScope.launchReporting { actions.undo(undoable.summary.id, undoable.verdict) }
    }

    fun onUndoDismissed() = _state.update { it.copy(undoable = null) }

    fun onRetryFailedCommit() {
        val failed = _state.value.failedCommit ?: return
        _state.update { it.copy(failedCommit = null) }
        viewModelScope.launchReporting {
            actions.record(failed.summary, failed.verdict).onFailure { error ->
                _state.update { it.copy(failedCommit = failed.copy(message = error.toUserMessage(COMMIT_FAILED))) }
            }
        }
    }

    fun onFailedCommitDismissed() = _state.update { it.copy(failedCommit = null) }

    fun onTutorialDismissed() {
        _state.update { it.copy(tutorialVisible = false) }
        viewModelScope.launchReporting { actions.setTutorialSeen(true) }
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

        loadJob = viewModelScope.launchReporting {
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
                        it.copy(loading = false, refilling = false, error = error.toUserMessage(LOAD_FAILED))
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
