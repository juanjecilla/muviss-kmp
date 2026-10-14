@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.triage.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.codingpit.muviss.core.common.analytics.AnalyticsTracker
import com.codingpit.muviss.core.common.crash.launchInReporting
import com.codingpit.muviss.core.common.crash.launchReporting
import com.codingpit.muviss.core.common.flags.FeatureFlags
import com.codingpit.muviss.core.common.flags.SnoozePeriod
import com.codingpit.muviss.core.common.flags.SnoozePlacement
import com.codingpit.muviss.core.common.flags.TriageControlScheme
import com.codingpit.muviss.core.designsystem.text.UiText
import com.codingpit.muviss.core.designsystem.text.toUiText
import com.codingpit.muviss.feature.triage.api.TriageVerdict
import com.codingpit.muviss.feature.triage.domain.DeckCursor
import com.codingpit.muviss.feature.triage.domain.DeckFilter
import com.codingpit.muviss.feature.triage.domain.LoadDeckGenresUseCase
import com.codingpit.muviss.feature.triage.domain.LoadDeckUseCase
import com.codingpit.muviss.feature.triage.domain.TriageActions
import com.codingpit.muviss.feature.triage.domain.TriageEvent
import com.codingpit.muviss.feature.triage.domain.TriageOnboarding
import com.codingpit.muviss.feature.triage.ui.generated.resources.Res
import com.codingpit.muviss.feature.triage.ui.generated.resources.error_commit
import com.codingpit.muviss.feature.triage.ui.generated.resources.error_load_more
import com.codingpit.muviss.models.Genre
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.MediaType
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

/**
 * Something the user can still take back, held only until the snackbar goes.
 *
 * Sealed rather than two fields on the state: only one snackbar exists, so
 * only one thing can be undoable at a time, and a type that cannot represent
 * "a decision and a snooze at once" is the cheapest way to keep that true.
 */
sealed interface UndoableAction {
    val summary: MediaSummary

    data class Decision(override val summary: MediaSummary, val verdict: TriageVerdict) : UndoableAction

    /** A Snooze is not a verdict (ADR 0023), so it carries a due date instead of one. */
    data class Snooze(override val summary: MediaSummary, val dueAtEpochDay: Long) : UndoableAction
}

/**
 * A card undo has just put back on top of the deck, so the screen can play its
 * exit animation backwards.
 *
 * [verdict] is what says *where* the card left from — the deck maps a verdict
 * to a direction and re-enters from there. It is **null for an undone Snooze**,
 * which never flew anywhere: a Snooze is not a verdict and deliberately does
 * not borrow the verdicts' motion, so the card simply reappears in place.
 * [token] exists because neither the id nor the verdict is enough to retrigger
 * the effect: decide the same card again and undo it again and both repeat, so
 * a monotonic counter is what makes the second undo a distinct event.
 */
data class RestoredCard(
    val id: MediaId,
    val verdict: TriageVerdict?,
    val token: Long,
)

/** A verdict whose save/tick work failed after the decision itself was recorded. */
data class FailedCommit(
    val summary: MediaSummary,
    val verdict: TriageVerdict,
    val message: UiText,
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
    /**
     * A refill has come back empty at least once and is reading further into
     * the catalogue (#183). The screen says so instead of a bare spinner.
     */
    val searchingDeeper: Boolean = false,
    /**
     * The last refill read [TriageViewModel.MAX_EMPTY_BATCHES_PER_REFILL]
     * batches without a single new title and stopped, though the catalogue is
     * not spent. Offered as "Keep looking", which continues from the cursor —
     * never as "All caught up", which would be false (#183).
     */
    val keepLookingAvailable: Boolean = false,
    val error: UiText? = null,
    val undoable: UndoableAction? = null,
    val failedCommit: FailedCommit? = null,
    val restored: RestoredCard? = null,
    /** The AND of the app-wide motion switch and triage's own — see [FeatureFlags]. */
    val deckAnimations: Boolean = true,
    val snoozePeriod: SnoozePeriod = SnoozePeriod.DEFAULT,
    val snoozePlacement: SnoozePlacement = SnoozePlacement.DEFAULT,
    /**
     * The card the "ask each time" sheet is open for, or null when it is shut.
     * Only reachable under [SnoozePeriod.ASK_EACH_TIME].
     */
    val snoozeChoiceFor: MediaSummary? = null,
    /**
     * The card the free-form date picker is open for (issue #137's "Pick a
     * date…"), or null when it is shut. Reached from [snoozeChoiceFor]'s sheet,
     * never directly — it replaces that sheet rather than nesting under it.
     */
    val snoozeDatePickerFor: MediaSummary? = null,
    /**
     * The earliest day [snoozeDatePickerFor]'s picker allows choosing, read
     * once from the clock when the picker opens — see
     * [com.codingpit.muviss.feature.triage.domain.SnoozeUseCase.todayEpochDay].
     * Meaningless while [snoozeDatePickerFor] is null.
     */
    val snoozeDatePickerMinEpochDay: Long = 0L,
    /** The one-time callout pointing at the snooze button. */
    val snoozeHintVisible: Boolean = false,
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
    onboarding: TriageOnboarding,
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

        featureFlags.triageSnoozePeriod
            .onEach { period -> _state.update { it.copy(snoozePeriod = period) } }
            .launchInReporting(viewModelScope)

        featureFlags.triageSnoozePlacement
            .onEach { placement -> _state.update { it.copy(snoozePlacement = placement) } }
            .launchInReporting(viewModelScope)

        // Only the first emission matters: dismissing the tutorial must not
        // make it reappear, and re-opening it is an explicit user action.
        onboarding.tutorialSeen()
            .take(1)
            .onEach { seen -> _state.update { it.copy(tutorialVisible = !seen) } }
            .launchInReporting(viewModelScope)

        // Same first-emission rule, and a separate flag on purpose: every
        // install that exists already has `triageTutorialSeen` true, so riding
        // in that dialog would show this to nobody who has the app today.
        onboarding.snoozeHintSeen()
            .take(1)
            .onEach { seen -> _state.update { it.copy(snoozeHintVisible = !seen) } }
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
                undoable = UndoableAction.Decision(summary, verdict),
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
                    current.copy(failedCommit = FailedCommit(summary, verdict, error.toUiText(UiText.Resource(Res.string.error_commit))))
                }
            }
        }
        refillIfRunningLow()
    }

    fun onUndo() {
        val undoable = _state.value.undoable ?: return
        if (undoable is UndoableAction.Decision) analytics.track(TriageEvent.DecisionUndone(undoable.verdict))
        _state.update {
            it.copy(
                // Back to the front of the deck, exactly where it was.
                cards = listOf(undoable.summary) + it.cards,
                undoable = null,
                failedCommit = null,
                exhausted = false,
                restored = RestoredCard(
                    id = undoable.summary.id,
                    // Null for a Snooze: nothing flew out, so nothing flies back.
                    verdict = (undoable as? UndoableAction.Decision)?.verdict,
                    token = ++restoreToken,
                ),
            )
        }
        shown -= undoable.summary.id
        viewModelScope.launchReporting {
            when (undoable) {
                is UndoableAction.Decision -> actions.undo(undoable.summary.id, undoable.verdict)
                is UndoableAction.Snooze -> actions.unsnooze(undoable.summary.id)
            }
        }
    }

    /**
     * Postpones the top card.
     *
     * Under [SnoozePeriod.ASK_EACH_TIME] this opens the sheet instead of
     * writing anything — the card stays put until a period is chosen, because
     * a card that vanished before the user picked would leave them choosing a
     * date for something they can no longer see.
     */
    fun onSnooze() {
        val summary = _state.value.topCard ?: return
        val due = actions.snooze.dueDateFor(_state.value.snoozePeriod)
        if (due == null) {
            _state.update { it.copy(snoozeChoiceFor = summary) }
            return
        }
        commitSnooze(summary, due)
    }

    /**
     * A period chosen in the sheet. Takes the period rather than a date so the
     * clock stays out of the UI — the screen has no business knowing what day
     * it is.
     */
    fun onSnoozePeriodChosen(period: SnoozePeriod) {
        val summary = _state.value.snoozeChoiceFor ?: return
        val due = actions.snooze.dueDateFor(period) ?: return
        _state.update { it.copy(snoozeChoiceFor = null) }
        commitSnooze(summary, due)
    }

    fun onSnoozeSheetDismissed() = _state.update { it.copy(snoozeChoiceFor = null) }

    /**
     * "Pick a date…" inside the sheet: swaps it for the custom picker rather
     * than opening on top of it, and reads the clock exactly once — the
     * picker itself never does (issue #137).
     */
    fun onPickCustomSnoozeDate() {
        val summary = _state.value.snoozeChoiceFor ?: return
        _state.update {
            it.copy(
                snoozeChoiceFor = null,
                snoozeDatePickerFor = summary,
                snoozeDatePickerMinEpochDay = actions.snooze.todayEpochDay() + 1,
            )
        }
    }

    /** A day chosen in the custom picker. Takes the epoch day directly — there is no [SnoozePeriod] to convert it from. */
    fun onSnoozeDateChosen(epochDay: Long) {
        val summary = _state.value.snoozeDatePickerFor ?: return
        _state.update { it.copy(snoozeDatePickerFor = null) }
        commitSnooze(summary, epochDay)
    }

    fun onSnoozeDatePickerDismissed() = _state.update { it.copy(snoozeDatePickerFor = null) }

    fun onSnoozeHintDismissed() {
        _state.update { it.copy(snoozeHintVisible = false) }
        viewModelScope.launchReporting { actions.setSnoozeHintSeen(true) }
    }

    private fun commitSnooze(summary: MediaSummary, dueAtEpochDay: Long) {
        // The hint has served its purpose the moment the gesture is used once.
        if (_state.value.snoozeHintVisible) onSnoozeHintDismissed()
        _state.update {
            it.copy(
                cards = it.cards.filterNot { card -> card.id == summary.id },
                undoable = UndoableAction.Snooze(summary, dueAtEpochDay),
                failedCommit = null,
                restored = null,
            )
        }
        viewModelScope.launchReporting { actions.snooze(summary, dueAtEpochDay) }
        refillIfRunningLow()
    }

    fun onUndoDismissed() = _state.update { it.copy(undoable = null) }

    fun onRetryFailedCommit() {
        val failed = _state.value.failedCommit ?: return
        _state.update { it.copy(failedCommit = null) }
        viewModelScope.launchReporting {
            actions.record(failed.summary, failed.verdict).onFailure { error ->
                _state.update { it.copy(failedCommit = failed.copy(message = error.toUiText(UiText.Resource(Res.string.error_commit)))) }
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

    /** Reads on from where the last refill stopped (#183); unlike [retry], it never goes back to page 1. */
    fun onKeepLooking() = refill(reset = false)

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
            _state.update {
                it.copy(cards = emptyList(), loading = true, error = null, exhausted = false, keepLookingAvailable = false)
            }
        } else {
            _state.update { it.copy(refilling = true, keepLookingAvailable = false) }
        }

        loadJob = viewModelScope.launchReporting {
            val filter = _state.value.filter
            var batchCards: List<MediaSummary> = emptyList()
            var trulyExhausted: Boolean

            // DeckLoader bounds a single load() call to a handful of
            // catalogue pages (MAX_PAGES_PER_BATCH), so deep into a large,
            // heavily-triaged library one batch can come back with nothing
            // new while the catalogue (cursor.exhaustedFor) is nowhere near
            // spent — TMDB's popularity order front-loads exactly the titles
            // a long-time user already has an opinion about. An empty batch
            // alone is never the answer, so keep reading — but only for
            // MAX_EMPTY_BATCHES_PER_REFILL batches (#183). Unbounded, the
            // worst case was ~1000 sequential requests behind a bare spinner.
            // Stopping is safe because the cursor survives: "Keep looking"
            // (onKeepLooking) and the next refillIfRunningLow both continue
            // from it, whereas only retry() and a filter change reset it.
            var emptyBatches = 0
            do {
                if (emptyBatches > 0) _state.update { it.copy(searchingDeeper = true) }
                val batch = loadDeck(filter, cursor, alreadyShown = shown, placement = _state.value.snoozePlacement)
                    .getOrElse { error ->
                        _state.update {
                            it.copy(loading = false, refilling = false, searchingDeeper = false, error = error.toUiText(UiText.Resource(Res.string.error_load_more)))
                        }
                        return@launchReporting
                    }
                cursor = batch.cursor
                shown += batch.cards.map { it.id }
                batchCards = batch.cards
                trulyExhausted = cursor.exhaustedFor(filter)
                val nothingNew = batchCards.isEmpty() && !trulyExhausted
                if (nothingNew) emptyBatches++
            } while (nothingNew && emptyBatches < MAX_EMPTY_BATCHES_PER_REFILL)

            _state.update { current ->
                val cards = current.cards + batchCards
                current.copy(
                    cards = cards,
                    loading = false,
                    refilling = false,
                    searchingDeeper = false,
                    error = null,
                    exhausted = cards.isEmpty() && trulyExhausted,
                    keepLookingAvailable = cards.isEmpty() && !trulyExhausted,
                )
            }
            if (trulyExhausted && _state.value.cards.isEmpty()) analytics.track(TriageEvent.DeckExhausted)
        }
    }

    internal companion object {
        /**
         * How many batches in a row may come back with nothing new before a
         * refill stops and offers "Keep looking" (#183). Each batch is up to
         * `MAX_PAGES_PER_BATCH` catalogue pages, so this bounds one refill to
         * a few dozen requests instead of the whole 500-page catalogue.
         */
        const val MAX_EMPTY_BATCHES_PER_REFILL = 4

        /** Refill while there are still cards left to swipe, so the deck never visibly stalls. */
        const val REFILL_THRESHOLD = 3
    }
}
