package com.codingpit.muviss.feature.triage.domain

import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.core.common.flags.SnoozePeriod
import com.codingpit.muviss.core.common.flags.SnoozePlacement
import com.codingpit.muviss.core.common.localTodayEpochDay
import com.codingpit.muviss.core.common.todayEpochDay
import com.codingpit.muviss.feature.collection.api.CollectionApi
import com.codingpit.muviss.feature.progress.api.ProgressApi
import com.codingpit.muviss.feature.triage.api.SkippedTitle
import com.codingpit.muviss.feature.triage.api.SnoozedTitle
import com.codingpit.muviss.feature.triage.api.TriageVerdict
import com.codingpit.muviss.models.Genre
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.MediaType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Records a verdict and applies what it means.
 *
 * The decision row is written **first and unconditionally**, before any
 * network call. That ordering is the feature: dedupe has to hold even when
 * everything after it fails, otherwise a dropped connection turns into the
 * deck re-asking about a title the user already ruled on. A verdict whose side
 * effects have not landed is left `resolved = false` for [RetryUnresolvedUseCase].
 *
 * No verdict ever writes a `WatchStatus` — [TriageVerdict.WATCHING] and
 * [TriageVerdict.CAUGHT_UP] write real ticks and let `WatchStatusCalculator`
 * derive the rest (ADR 0005).
 */
class RecordDecisionUseCase(
    private val repository: TriageDecisionRepository,
    private val collectionApi: CollectionApi,
    private val progressApi: ProgressApi,
    private val detailsSource: TriageDetailsSource,
    private val clock: AppClock,
) {
    suspend operator fun invoke(summary: MediaSummary, verdict: TriageVerdict): Result<Unit> {
        require(verdict in TriageVerdict.availableFor(summary.type)) {
            "$verdict is not a valid verdict for a ${summary.type}"
        }
        // Read before anything is written, so the claim is the library's state
        // when the person ruled on the card. A title already saved (from Detail,
        // while the deck was open) is not this verdict's to take back (#767).
        val decision = TriageDecision.of(
            summary = summary,
            verdict = verdict,
            nowEpochMs = clock.nowEpochMs(),
            resolved = !verdict.savesToCollection,
            createdEntry = verdict.savesToCollection && !isInLibrary(summary.id),
        )
        repository.record(decision)
        if (!verdict.savesToCollection) return Result.success(Unit)
        return apply(decision)
    }

    private suspend fun isInLibrary(mediaId: MediaId): Boolean = collectionApi.observeMembership(mediaId).first() != null

    /** Re-runs the side effects of a decision that never completed. The claim it stored is kept, never re-read. */
    suspend fun retry(decision: TriageDecision): Result<Unit> = apply(decision)

    private suspend fun apply(decision: TriageDecision): Result<Unit> = runCatching {
        val details = detailsSource.fetch(decision.mediaId).getOrThrow()
        collectionApi.add(details)

        val today = clock.todayEpochDay()
        when (decision.verdict) {
            TriageVerdict.SKIP, TriageVerdict.LATER -> Unit

            // One tick, so the show derives Watching and surfaces on the
            // Progress screen with a genuine next episode. A show with nothing
            // aired yet is simply saved unticked.
            TriageVerdict.WATCHING -> details.firstAiredEpisode(today)?.let { progressApi.setEpisodeSeen(it.id, seen = true) }

            TriageVerdict.CAUGHT_UP -> when (details.type) {
                MediaType.MOVIE -> progressApi.setMovieWatched(decision.mediaId, watched = true)

                // Exactly the aired episodes — see ProgressApi.markAllAiredSeen
                // for why this is not markPreviousSeen.
                MediaType.TV -> progressApi.markAllAiredSeen(details.seasons, today)
            }
        }
        repository.markResolved(decision.mediaId, resolved = true)
    }
}

/**
 * Takes a verdict back.
 *
 * Restoring the decision alone is not enough when the verdict created the
 * library entry: it wrote a `CollectionEntry` and, for Watching/CaughtUp,
 * episode ticks, and undo has to reverse those, or the user is left with a
 * show sitting in their library still reading as fully watched.
 *
 * Only what the verdict created is reversed. When the entry was already in the
 * library before the card was ruled on (the person added it from Detail while
 * the deck was open, #767), the entry and its ticks are the person's, so they
 * are left alone. A verdict's own ticks on such an entry are not reversed
 * either: nothing records which episodes it ticked (issue #790).
 */
class UndoDecisionUseCase(
    private val repository: TriageDecisionRepository,
    private val collectionApi: CollectionApi,
    private val progressApi: ProgressApi,
) {
    suspend operator fun invoke(mediaId: MediaId, verdict: TriageVerdict) {
        // Read before the restore soft-deletes the row. A missing row claims
        // nothing, so nothing is removed.
        val createdEntry = repository.observeDecision(mediaId).first()?.createdEntry ?: false
        repository.restore(mediaId)
        if (!verdict.savesToCollection || !createdEntry) return
        progressApi.clearProgress(mediaId)
        collectionApi.remove(mediaId)
    }
}

/** Genre chips for the deck's filter row. An empty list simply means no chips. */
class LoadDeckGenresUseCase(private val source: DeckSource) {
    suspend operator fun invoke(type: MediaType): List<Genre> = source.genres(type).getOrDefault(emptyList())
}

/** Forgets a decision so the title can come back around. The Skipped screen uses it. */
class RestoreDecisionUseCase(private val repository: TriageDecisionRepository) {
    suspend operator fun invoke(mediaId: MediaId) = repository.restore(mediaId)
}

/** Every id the user has ruled on — half of the deck's exclusion set. */
class ObserveDecidedIdsUseCase(private val repository: TriageDecisionRepository) {
    operator fun invoke(): Flow<Set<MediaId>> = repository.observeDecidedIds()
}

class ObserveSkippedUseCase(private val repository: TriageDecisionRepository) {
    operator fun invoke(): Flow<List<SkippedTitle>> = repository.observeByVerdict(TriageVerdict.SKIP)
        .map { decisions ->
            decisions.map { SkippedTitle(it.mediaId, it.title, it.posterUrl, it.decidedAtEpochMs) }
        }
}

class ObserveDecisionUseCase(private val repository: TriageDecisionRepository) {
    operator fun invoke(mediaId: MediaId): Flow<TriageDecision?> = repository.observeDecision(mediaId)
}

/**
 * Fills the deck.
 *
 * The exclusion set is decided ids **union** collection membership. The union
 * matters: titles saved before triage existed (or added from search since)
 * have no decision row, and showing them would ask the user about something
 * already sitting in their library.
 */
class LoadDeckUseCase(
    private val loader: DeckLoader,
    private val repository: TriageDecisionRepository,
    private val snoozes: TriageSnoozeRepository,
    private val collectionApi: CollectionApi,
    private val clock: AppClock,
    private val resumption: DeckResumption = DeckResumption.NONE,
) {
    suspend operator fun invoke(
        filter: DeckFilter,
        cursor: DeckCursor,
        alreadyShown: Set<MediaId> = emptySet(),
        placement: SnoozePlacement = SnoozePlacement.DEFAULT,
    ): Result<DeckBatch> = open(placement).next(filter, cursor, alreadyShown, DeckLoader.DEFAULT_BATCH_SIZE)

    /**
     * Reads what the deck must not offer and which Snoozes have come due, once, for a whole refill (#722).
     * A refill reads many batches; re-reading the decided and collection sets for each one is what made a
     * heavy user's refill slow.
     */
    suspend fun open(placement: SnoozePlacement = SnoozePlacement.DEFAULT): DeckSession {
        val decided = repository.observeDecidedIds().first()
        val inCollection = collectionApi.observeSummaries().first().map { it.mediaId }.toSet()
        val settled = decided + inCollection

        // A Snooze can be overtaken: the title may have been saved from search
        // or ruled on from the Detail screen while it waited. Such a Snooze has
        // no question left to ask, so it is retired rather than shown — and
        // retired *soft*, so the tombstone reaches the other devices instead of
        // their copy being pushed back.
        val due = snoozes.due(clock.localTodayEpochDay())
        val (overtaken, returning) = due.partition { it.mediaId in settled }
        overtaken.forEach { snoozes.unsnooze(it.mediaId) }

        // Pending Snoozes that are NOT yet due stay excluded; only the due ones
        // are handed to the loader as a second source.
        val pending = snoozes.observeSnoozedIds().first() - returning.map { it.mediaId }.toSet()

        return DeckSession(
            loader = loader,
            excluded = settled + pending,
            returning = returning.map { it.toSummary() },
            placement = placement,
        )
    }

    /** Where the deck stood for [filter] when this device last saved it, rewound per [DeckResume]; page 1 otherwise. */
    suspend fun restoreCursor(filter: DeckFilter): DeckCursor {
        val saved = resumption.cursors.read(DeckResume.key(filter, resumption.language.current()))
        return DeckResume.restore(saved, clock.nowEpochMs())
    }

    /**
     * Saves where the deck stands. [unactedMovie] and [unactedTv] are the earliest pages still holding a card
     * the person has not acted on, or null when none does (see [SavedDeckCursor]).
     */
    suspend fun checkpoint(filter: DeckFilter, cursor: DeckCursor, unactedMovie: Int?, unactedTv: Int?) {
        val saved = DeckResume.checkpoint(cursor, unactedMovie, unactedTv, clock.nowEpochMs())
        resumption.cursors.write(DeckResume.key(filter, resumption.language.current()), saved)
    }
}

/**
 * One refill's view of the catalogue: the exclusions and due Snoozes read once by [LoadDeckUseCase.open].
 * Every batch the refill asks for shares them, and a Snooze retires or re-enters once, not once per batch.
 */
class DeckSession internal constructor(
    private val loader: DeckLoader,
    private val excluded: Set<MediaId>,
    private val returning: List<MediaSummary>,
    private val placement: SnoozePlacement,
) {
    /** [alreadyShown] carries the cards the refill has handed out so far; a returning Snooze already shown is in it. */
    suspend fun next(
        filter: DeckFilter,
        cursor: DeckCursor,
        alreadyShown: Set<MediaId>,
        wanted: Int,
    ): Result<DeckBatch> = loader.load(
        filter = filter,
        cursor = cursor,
        excluded = excluded + alreadyShown,
        dueSnoozes = DueSnoozes(cards = returning, placement = placement),
        wanted = wanted,
    )
}

/**
 * Postpones a title (ADR 0023).
 *
 * Writes no [TriageDecision] — that is the whole distinction. It also clears
 * nothing: a title the deck offers is by construction in neither the decision
 * log nor the collection, so there is no prior state to reconcile.
 */
class SnoozeUseCase(
    private val snoozes: TriageSnoozeRepository,
    private val clock: AppClock,
) {
    suspend operator fun invoke(summary: MediaSummary, dueAtEpochDay: Long) {
        snoozes.snooze(TriageSnooze.of(summary, nowEpochMs = clock.nowEpochMs(), dueAtEpochDay = dueAtEpochDay))
    }

    /** The due date a stored [SnoozePeriod] implies. Null for ASK_EACH_TIME, which has no duration. */
    fun dueDateFor(period: SnoozePeriod): Long? = period.days?.let { clock.localTodayEpochDay() + it }

    /**
     * Today in the person's zone, as an epoch day — the one clock read the custom date picker needs
     * to bound its earliest selectable day. [dueDateFor] keeps the *durations*
     * out of the UI; this keeps "what day is it" out too, so the picker still
     * never reads a clock of its own and cannot offer yesterday. Local, like
     * [dueDateFor]: a due day and the picker's floor must count the same days.
     */
    fun todayEpochDay(): Long = clock.localTodayEpochDay()
}

/** Takes a Snooze back — undo, the Snoozed screen, and the Detail banner all call this. */
class UnsnoozeUseCase(private val snoozes: TriageSnoozeRepository) {
    suspend operator fun invoke(mediaId: MediaId) = snoozes.unsnooze(mediaId)
}

/** Pending Snoozes for the Snoozed screen, soonest to come back first. */
class ObserveSnoozedUseCase(private val snoozes: TriageSnoozeRepository) {
    operator fun invoke(): Flow<List<SnoozedTitle>> = snoozes.observeAll()
        .map { pending ->
            pending.map { SnoozedTitle(it.mediaId, it.title, it.posterUrl, it.snoozedAtEpochMs, it.dueAtEpochDay) }
        }
}

/** Every snoozed id — keeps a pending title out of Discover's "For you". */
class ObserveSnoozedIdsUseCase(private val snoozes: TriageSnoozeRepository) {
    operator fun invoke(): Flow<Set<MediaId>> = snoozes.observeSnoozedIds()
}

/** The standing Snooze for one title, for the Detail screen's banner. */
class ObserveSnoozeUseCase(private val snoozes: TriageSnoozeRepository) {
    operator fun invoke(mediaId: MediaId): Flow<TriageSnooze?> = snoozes.observeSnooze(mediaId)
}

/** Retries any verdict whose save/tick work never landed. Best-effort; failures stay unresolved. */
class RetryUnresolvedUseCase(
    private val repository: TriageDecisionRepository,
    private val record: RecordDecisionUseCase,
) {
    suspend operator fun invoke(): Int = repository.unresolved().count { record.retry(it).isSuccess }
}

class ObserveTutorialSeenUseCase(private val preferences: TriagePreferences) {
    operator fun invoke(): Flow<Boolean> = preferences.observeTutorialSeen()
}

class SetTutorialSeenUseCase(private val preferences: TriagePreferences) {
    suspend operator fun invoke(seen: Boolean) = preferences.setTutorialSeen(seen)
}

class ObserveSnoozeHintSeenUseCase(private val preferences: TriagePreferences) {
    operator fun invoke(): Flow<Boolean> = preferences.observeSnoozeHintSeen()
}

class SetSnoozeHintSeenUseCase(private val preferences: TriagePreferences) {
    suspend operator fun invoke(seen: Boolean) = preferences.setSnoozeHintSeen(seen)
}

/**
 * Bundles the deck's mutators so `TriageViewModel`'s constructor stays inside
 * detekt's `LongParameterList` budget — same trick as collection's
 * `CollectionToggles`.
 */
class TriageActions(
    val record: RecordDecisionUseCase,
    val undo: UndoDecisionUseCase,
    val setTutorialSeen: SetTutorialSeenUseCase,
    val retryUnresolved: RetryUnresolvedUseCase,
    val snoozing: SnoozeActions,
) {
    val snooze: SnoozeUseCase get() = snoozing.snooze
    val unsnooze: UnsnoozeUseCase get() = snoozing.unsnooze
    val setSnoozeHintSeen: SetSnoozeHintSeenUseCase get() = snoozing.setSnoozeHintSeen
}

/** Snooze's own mutators, bundled for the same reason [TriageActions] is. */
class SnoozeActions(
    val snooze: SnoozeUseCase,
    val unsnooze: UnsnoozeUseCase,
    val setSnoozeHintSeen: SetSnoozeHintSeenUseCase,
)

/** The deck's read-side snooze surface, bundled so `DefaultTriageApi` stays inside detekt's budget. */
class SnoozeQueries(
    val observeSnoozedIds: ObserveSnoozedIdsUseCase,
    val observeSnoozed: ObserveSnoozedUseCase,
    val observeSnooze: ObserveSnoozeUseCase,
    val unsnooze: UnsnoozeUseCase,
)

/**
 * The deck's two one-shot onboarding reads. Bundled rather than passed
 * separately because they are one concern — what this device has already been
 * shown — and because a seventh constructor parameter is where detekt draws
 * the line.
 */
class TriageOnboarding(
    val tutorialSeen: ObserveTutorialSeenUseCase,
    val snoozeHintSeen: ObserveSnoozeHintSeenUseCase,
)
