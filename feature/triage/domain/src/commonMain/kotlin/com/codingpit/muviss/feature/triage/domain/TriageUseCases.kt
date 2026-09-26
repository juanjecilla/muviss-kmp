package com.codingpit.muviss.feature.triage.domain

import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.core.common.flags.SnoozePeriod
import com.codingpit.muviss.core.common.flags.SnoozePlacement
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
        val decision = TriageDecision.of(
            summary = summary,
            verdict = verdict,
            nowEpochMs = clock.nowEpochMs(),
            resolved = !verdict.savesToCollection,
        )
        repository.record(decision)
        if (!verdict.savesToCollection) return Result.success(Unit)
        return apply(decision)
    }

    /** Re-runs the side effects of a decision that never completed. */
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
 * Takes a verdict back, completely.
 *
 * Restoring the decision alone is not enough: a saving verdict has already
 * written a `CollectionEntry` and, for Watching/CaughtUp, episode ticks. Undo
 * has to reverse those too, or the user is left with a show sitting in their
 * library still reading as fully watched. The deck only ever offers titles
 * that are in neither the decision log nor the collection, so removing both is
 * always safe here — there is no pre-existing state to clobber.
 */
class UndoDecisionUseCase(
    private val repository: TriageDecisionRepository,
    private val collectionApi: CollectionApi,
    private val progressApi: ProgressApi,
) {
    suspend operator fun invoke(mediaId: MediaId, verdict: TriageVerdict) {
        repository.restore(mediaId)
        if (!verdict.savesToCollection) return
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
) {
    suspend operator fun invoke(
        filter: DeckFilter,
        cursor: DeckCursor,
        alreadyShown: Set<MediaId> = emptySet(),
        placement: SnoozePlacement = SnoozePlacement.DEFAULT,
    ): Result<DeckBatch> {
        val decided = repository.observeDecidedIds().first()
        val inCollection = collectionApi.observeSummaries().first().map { it.mediaId }.toSet()
        val settled = decided + inCollection

        // A Snooze can be overtaken: the title may have been saved from search
        // or ruled on from the Detail screen while it waited. Such a Snooze has
        // no question left to ask, so it is retired rather than shown — and
        // retired *soft*, so the tombstone reaches the other devices instead of
        // their copy being pushed back.
        val due = snoozes.due(clock.todayEpochDay())
        val (overtaken, returning) = due.partition { it.mediaId in settled }
        overtaken.forEach { snoozes.unsnooze(it.mediaId) }

        // Pending Snoozes that are NOT yet due stay excluded; only the due ones
        // are handed to the loader as a second source.
        val pending = snoozes.observeSnoozedIds().first() - returning.map { it.mediaId }.toSet()

        return loader.load(
            filter = filter,
            cursor = cursor,
            excluded = settled + pending + alreadyShown,
            dueSnoozes = DueSnoozes(cards = returning.map { it.toSummary() }, placement = placement),
        )
    }
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
    fun dueDateFor(period: SnoozePeriod): Long? = period.days?.let { clock.todayEpochDay() + it }
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
