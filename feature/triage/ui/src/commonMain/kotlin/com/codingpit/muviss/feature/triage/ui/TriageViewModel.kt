@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.triage.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.codingpit.muviss.core.common.analytics.AnalyticsTracker
import com.codingpit.muviss.core.common.crash.CrashReporter
import com.codingpit.muviss.core.common.crash.launchInReporting
import com.codingpit.muviss.core.common.crash.launchReporting
import com.codingpit.muviss.core.common.flags.FeatureFlags
import com.codingpit.muviss.core.common.flags.SnoozePeriod
import com.codingpit.muviss.core.common.flags.SnoozePlacement
import com.codingpit.muviss.core.common.flags.TriageControlScheme
import com.codingpit.muviss.core.designsystem.text.UiText
import com.codingpit.muviss.core.designsystem.text.toUiText
import com.codingpit.muviss.feature.settings.api.SettingsApi
import com.codingpit.muviss.feature.triage.api.TriageVerdict
import com.codingpit.muviss.feature.triage.domain.DeckCursor
import com.codingpit.muviss.feature.triage.domain.DeckFeed
import com.codingpit.muviss.feature.triage.domain.DeckFilter
import com.codingpit.muviss.feature.triage.domain.LoadDeckGenresUseCase
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
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
    /** The deck was handed titles by Next Pick (#554): no filter bar, and the picks-done copy when they run out. */
    val seeded: Boolean = false,
    val loading: Boolean = true,
    val refilling: Boolean = false,
    val cards: List<MediaSummary> = emptyList(),
    val filter: DeckFilter = DeckFilter(),
    /** The "show muted" chip is on: muted genres are back in the deck for this session (ADR 0031). */
    val showMuted: Boolean = false,
    /** Something is muted in Settings, so the chip has something to toggle. */
    val hasMuted: Boolean = false,
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
     * The last refill read its page budget ([TriageViewModel.MAX_PAGES_PER_REFILL])
     * without a single new title and stopped, though the catalogue is not spent. Offered as "Keep looking", which continues from the cursor —
     * never as "All caught up", which would be false (#183).
     */
    val keepLookingAvailable: Boolean = false,
    /** A screen-level error: only when the deck is empty, so there is nothing else to show. */
    val error: UiText? = null,
    /**
     * A refill failed with cards still on the deck. The deck stays; the screen reports this once as a snackbar,
     * whose action resumes the refill from the cursor (#722).
     */
    val refillFailure: UiText? = null,
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
@Suppress("LongParameterList") // the seventh is Settings, for Muted Genres (ADR 0031); each is a distinct peer seam
class TriageViewModel(
    private val loadDeck: DeckFeed,
    private val loadGenres: LoadDeckGenresUseCase,
    private val actions: TriageActions,
    onboarding: TriageOnboarding,
    featureFlags: FeatureFlags,
    private val analytics: AnalyticsTracker,
    private val settings: SettingsApi,
) : ViewModel() {

    private val _state = MutableStateFlow(TriageUiState(seeded = loadDeck.seeded))
    val state: StateFlow<TriageUiState> = _state.asStateFlow()

    private var cursor = DeckCursor()

    /** The filter [cursor] was read for. Null while a reset has not restored one yet, so nothing is saved against a stale position. */
    private var cursorFilter: DeckFilter? = null

    /** Everything already handed to the deck this session, so a refill never repeats a card. */
    private val shown = mutableSetOf<MediaId>()

    /**
     * Cards a batch read past what the deck needed (#722). The cursor has moved past their pages, so they are
     * kept here and drained into [TriageUiState.cards] before any network call.
     */
    private val overflow = ArrayDeque<MediaSummary>()

    /** The catalogue page each card came from, so a checkpoint can name the earliest page still unacted on. */
    private val pageOf = mutableMapOf<MediaId, Int>()

    /** The one refill in flight, if any. Swipes never cancel it; only a reset does (#722). */
    private var refillJob: Job? = null

    /** Bumped by every reset. A refill that finds it changed has been superseded and drops its result. */
    private var generation = 0

    /** A checkpoint taken at [epoch], the [generation] it was taken in. A reset makes it stale (#722). */
    private class PendingCheckpoint(
        val epoch: Int,
        val filter: DeckFilter,
        val cursor: DeckCursor,
        val unactedMovie: Int?,
        val unactedTv: Int?,
    )

    /**
     * Every checkpoint, in the order it was taken. One consumer writes them one at a time, so an older
     * snapshot can never land after a newer one, and a snapshot from before a reset is skipped rather than
     * overwriting the reset's own position.
     */
    private val checkpoints = Channel<PendingCheckpoint>(Channel.UNLIMITED)

    /**
     * An action the store has not confirmed yet (a decision whose record is in flight or failed, or a Snooze
     * not yet written), keyed by card. Its page stays in every checkpoint until the action persists, so a
     * restart cannot resume past a card the person acted on but nothing has recorded (#722). [token] tells
     * a superseded action from the one that is still outstanding.
     */
    private data class Unpersisted(val summary: MediaSummary, val page: Int?, val filter: DeckFilter, val token: Long)

    private val unpersisted = mutableMapOf<MediaId, Unpersisted>()

    private var unpersistedToken = 0L

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
        viewModelScope.launchReporting {
            for (pending in checkpoints) {
                if (pending.epoch != generation) continue
                // One failed save costs one report; the next checkpoint still gets written.
                runCatching { loadDeck.checkpoint(pending.filter, pending.cursor, pending.unactedMovie, pending.unactedTv) }
                    .onFailure { error ->
                        if (error is CancellationException) throw error
                        CrashReporter.recordException(error)
                    }
            }
        }
        if (loadDeck.seeded) {
            // A seeded deck is the engine's ranked list: it has no genre chips and no mutes of its own (the engine
            // already applied them), so nothing is read from settings and the deck starts at once.
            refill(reset = true, restore = true)
        } else {
            viewModelScope.launchReporting { loadGenreChips() }
            // The first deck waits for the first muting: filling it unmuted and then reloading would flash
            // exactly the shows the user muted. A later change (Settings, the chip) reloads from page 1.
            var firstMuting = true
            settings.observeMuting()
                .onEach { muting ->
                    val changed = _state.value.filter.muted != muting.effective
                    _state.update { it.copy(filter = it.filter.copy(muted = muting.effective), showMuted = muting.showMuted, hasMuted = muting.hasMuted) }
                    if (firstMuting || changed) refill(reset = true, restore = true)
                    firstMuting = false
                }
                .launchInReporting(viewModelScope)
        }
        viewModelScope.launchReporting { actions.retryUnresolved() }
    }

    fun onTypeFilterChange(type: MediaType?) {
        if (_state.value.filter.type == type) return
        // Genre ids are per-catalogue, so switching type drops the genre.
        _state.update { it.copy(filter = it.filter.copy(type = type, genreId = null)) }
        refill(reset = true, restore = true)
    }

    fun onGenreFilterChange(genreId: String?) {
        if (_state.value.filter.genreId == genreId) return
        _state.update { it.copy(filter = it.filter.copy(genreId = genreId)) }
        refill(reset = true, restore = true)
    }

    fun onClearFilters() {
        if (!_state.value.filter.isNarrowed) return
        _state.update { it.copy(filter = it.filter.copy(type = null, genreId = null)) }
        refill(reset = true, restore = true)
    }

    /** The chip: lifts muting for this session, or puts it back. Reloads through [SettingsApi.observeMuting]. */
    fun onShowMutedToggled() = settings.setShowMutedThisSession(!_state.value.showMuted)

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
        // The saved position moves only once the decision persists; until then its page is held (see [hold]).
        val token = hold(summary)
        viewModelScope.launchReporting {
            actions.record(summary, verdict)
                .onSuccess {
                    settle(summary.id, token)
                    checkpoint()
                }
                .onFailure { error ->
                    _state.update { current ->
                        current.copy(
                            // The failure replaces the Undo (#159). Clearing undoable here
                            // cancels the Undo snackbar's effect, so the failure takes the
                            // snackbar slot at once instead of racing it (#569).
                            undoable = null,
                            failedCommit = FailedCommit(summary, verdict, error.toUiText(UiText.Resource(Res.string.error_commit))),
                        )
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
        // The card is back on the deck, so its page is held by the deck itself, not by this hold.
        unpersisted.remove(undoable.summary.id)
        checkpoint()
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
        // A failed write throws, so the report is made and the hold stays: the card's page is kept, not skipped.
        val token = hold(summary)
        viewModelScope.launchReporting {
            actions.snooze(summary, dueAtEpochDay)
            settle(summary.id, token)
            checkpoint()
        }
        refillIfRunningLow()
    }

    fun onUndoDismissed() = _state.update { it.copy(undoable = null) }

    fun onRetryFailedCommit() {
        val failed = _state.value.failedCommit ?: return
        _state.update { it.copy(failedCommit = null) }
        val token = unpersisted[failed.summary.id]?.token ?: hold(failed.summary)
        viewModelScope.launchReporting {
            actions.record(failed.summary, failed.verdict)
                .onSuccess {
                    settle(failed.summary.id, token)
                    checkpoint()
                }
                .onFailure { error ->
                    _state.update { it.copy(failedCommit = failed.copy(message = error.toUiText(UiText.Resource(Res.string.error_commit)))) }
                }
        }
    }

    fun onFailedCommitDismissed() = _state.update { it.copy(failedCommit = null) }

    fun onRefillFailureDismissed() = _state.update { it.copy(refillFailure = null) }

    fun onTutorialDismissed() {
        _state.update { it.copy(tutorialVisible = false) }
        viewModelScope.launchReporting { actions.setTutorialSeen(true) }
    }

    fun onShowTutorial() = _state.update { it.copy(tutorialVisible = true) }

    /** Back to page 1: an explicit start over, so the saved position is not restored (#722). */
    fun retry() = refill(reset = true, restore = false)

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

    /**
     * Starts a refill, or does nothing when one is already in flight: a swipe during a refill must not
     * restart it, because the cursor only moves when a batch lands (#722). A reset cancels the refill in
     * flight and starts from [restore]d or page-1 position.
     */
    private fun refill(reset: Boolean, restore: Boolean = false) {
        if (!reset && refillJob?.isActive == true) return
        if (reset) {
            refillJob?.cancel()
            generation++
            cursor = DeckCursor()
            cursorFilter = null
            shown.clear()
            overflow.clear()
            pageOf.clear()
            _state.update {
                it.copy(cards = emptyList(), loading = true, error = null, refillFailure = null, exhausted = false, keepLookingAvailable = false)
            }
        } else {
            _state.update { it.copy(refilling = true, keepLookingAvailable = false, refillFailure = null) }
        }
        val run = generation
        refillJob = viewModelScope.launchReporting { fillDeck(run, restore) }
    }

    /**
     * Fills the deck to [BUFFER_TARGET] cards, drawing on the overflow first and then the catalogue. The page
     * budget is per refill, so a deck of already-decided titles stops and offers "Keep looking" instead of
     * walking the catalogue (#183). Stops early when the run is superseded by a reset.
     */
    private suspend fun fillDeck(run: Int, restore: Boolean) {
        val filter = _state.value.filter
        if (restore) cursor = loadDeck.restoreCursor(filter)
        cursorFilter = filter
        takeFromOverflow()
        val session = loadDeck.open(_state.value.snoozePlacement)

        var pagesRead = 0
        var emptyBatch = false
        while (bufferedCount() < BUFFER_TARGET && !cursor.exhaustedFor(filter) && pagesRead < MAX_PAGES_PER_REFILL) {
            if (run != generation) return
            if (emptyBatch) _state.update { it.copy(searchingDeeper = true) }
            val batch = session.next(filter, cursor, shown, wanted = BUFFER_TARGET - bufferedCount())
                .getOrElse { error ->
                    if (run == generation) {
                        val message = error.toUiText(UiText.Resource(Res.string.error_load_more))
                        _state.update {
                            // A failed refill never hides the cards it already has: with a deck on screen it is a
                            // snackbar, and only an empty deck gets the full error and its Retry.
                            if (it.cards.isEmpty() && overflow.isEmpty()) {
                                it.copy(loading = false, refilling = false, searchingDeeper = false, error = message)
                            } else {
                                it.copy(loading = false, refilling = false, searchingDeeper = false, refillFailure = message)
                            }
                        }
                    }
                    return
                }
            if (run != generation) return
            pagesRead += batch.pagesRead
            cursor = batch.cursor
            pageOf += batch.pageOf
            shown += batch.cards.map { it.id }
            shown += batch.overflow.map { it.id }
            overflow.addAll(batch.overflow)
            _state.update { it.copy(cards = it.cards + batch.cards) }
            takeFromOverflow()
            emptyBatch = batch.cards.isEmpty() && batch.overflow.isEmpty()
            checkpoint()
        }
        if (run != generation) return

        val trulyExhausted = cursor.exhaustedFor(filter)
        _state.update { current ->
            current.copy(
                loading = false,
                refilling = false,
                searchingDeeper = false,
                error = null,
                exhausted = current.cards.isEmpty() && trulyExhausted,
                keepLookingAvailable = current.cards.isEmpty() && !trulyExhausted,
            )
        }
        if (trulyExhausted && _state.value.cards.isEmpty()) analytics.track(TriageEvent.DeckExhausted)
        checkpoint()
    }

    /** Cards on the deck plus those held back for it. */
    private fun bufferedCount(): Int = _state.value.cards.size + overflow.size

    /** Moves held-back cards onto the deck until it is full. Runs before any network call. */
    private fun takeFromOverflow() {
        val room = BUFFER_TARGET - _state.value.cards.size
        if (room <= 0 || overflow.isEmpty()) return
        val moved = List(minOf(room, overflow.size)) { overflow.removeFirst() }
        _state.update { it.copy(cards = it.cards + moved) }
    }

    /**
     * Saves where the deck stands for its filter, so a fresh visit (or process) resumes near it (#722). The
     * saved page is the earliest one still holding a card the person has not acted on, so nothing they have
     * not seen is skipped. Saves nothing while a reset has not restored a position yet.
     */
    private fun checkpoint() {
        val filter = cursorFilter ?: return
        val unacted = _state.value.cards + overflow
        // Only holds taken under this filter: another filter's page numbers say nothing about this one's cursor.
        val held = unpersisted.values.filter { it.filter == filter }
        val pages = unacted.map { it.type to pageOf[it.id] } + held.map { it.summary.type to it.page }
        val unactedMovie = pages.filter { it.first == MediaType.MOVIE }.mapNotNull { it.second }.minOrNull()
        val unactedTv = pages.filter { it.first == MediaType.TV }.mapNotNull { it.second }.minOrNull()
        checkpoints.trySend(PendingCheckpoint(generation, filter, cursor, unactedMovie, unactedTv))
    }

    /** Holds [summary]'s page in every checkpoint until its action persists. Returns the token [settle] needs. */
    private fun hold(summary: MediaSummary): Long {
        val token = ++unpersistedToken
        unpersisted[summary.id] = Unpersisted(summary, pageOf[summary.id], cursorFilter ?: _state.value.filter, token)
        return token
    }

    /** Releases the hold once the action persisted, unless a newer action on the same card has taken it over. */
    private fun settle(id: MediaId, token: Long) {
        if (unpersisted[id]?.token == token) unpersisted.remove(id)
    }

    internal companion object {
        /**
         * Catalogue pages one refill may read before it stops and offers "Keep looking" (#183). Counted in
         * pages, not in empty batches: a batch can carry one new title from five pages, and that is progress.
         */
        const val MAX_PAGES_PER_REFILL = 20

        /** Refill when the deck falls to this many cards (the low-water mark, #722). */
        const val REFILL_THRESHOLD = 5

        /** The deck is filled up to this many cards, the target a refill aims for. */
        const val BUFFER_TARGET = 10
    }
}
