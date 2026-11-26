package com.codingpit.muviss.feature.triage.domain

import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.core.common.flags.SnoozePeriod
import com.codingpit.muviss.core.common.flags.SnoozePlacement
import com.codingpit.muviss.core.common.localTodayEpochDay
import com.codingpit.muviss.core.common.todayEpochDay
import com.codingpit.muviss.feature.collection.api.CollectionApi
import com.codingpit.muviss.feature.progress.api.EpisodeOrdering
import com.codingpit.muviss.feature.progress.api.EpisodePlay
import com.codingpit.muviss.feature.progress.api.ProgressApi
import com.codingpit.muviss.feature.triage.api.SkippedTitle
import com.codingpit.muviss.feature.triage.api.SnoozedTitle
import com.codingpit.muviss.feature.triage.api.TriageVerdict
import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.Genre
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.MediaType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serialises a verdict's side effects with an Undo on the same title (#792).
 *
 * Undo and the verdict's apply both reach the library and the ticks, and the
 * apply is suspended on a network fetch for seconds. Without this, an Undo
 * could run while the fetch is pending, find nothing to reverse, and the apply
 * would then add the title back. With it, Undo waits for an in-flight apply to
 * finish, and an apply that starts after an Undo sees the decision is gone and
 * writes nothing.
 *
 * Process-wide rather than injected: [RecordDecisionUseCase] and
 * [UndoDecisionUseCase] are separate Koin factories, and a per-instance lock
 * would protect nothing. The map keeps one small mutex per title for the life
 * of the process, which is bounded by the titles the person has ruled on.
 */
internal object TriageTitleLocks {
    private val guard = Mutex()
    private val locks = mutableMapOf<MediaId, Mutex>()

    suspend fun <T> withTitle(mediaId: MediaId, block: suspend () -> T): T {
        val lock = guard.withLock { locks.getOrPut(mediaId) { Mutex() } }
        return lock.withLock { block() }
    }
}

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
 *
 * What the verdict *took* is recorded as it happens, so Undo can reverse
 * exactly that and nothing else: whether it created the library entry (#767),
 * the entry's added-at stamp (#902), and the viewings its ticks wrote (#790).
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
        // The claim is not decided here. It is taken in apply, against the library at the moment the add lands.
        val decision = TriageDecision.of(
            summary = summary,
            verdict = verdict,
            nowEpochMs = clock.nowEpochMs(),
            resolved = !verdict.savesToCollection,
        )
        repository.record(decision)
        if (!verdict.savesToCollection) return Result.success(Unit)
        return apply(decision.mediaId)
    }

    /**
     * Re-runs the side effects of a decision that never completed. A stamped claim an earlier attempt recorded is kept;
     * a pre-stamp claim is retired (#1058), since it cannot say which entry it is about.
     */
    suspend fun retry(decision: TriageDecision): Result<Unit> = apply(decision.mediaId)

    private suspend fun apply(mediaId: MediaId): Result<Unit> = runCatching {
        // The fetch runs outside the lock, so an Undo that arrives while it is pending is not held up by it. That Undo
        // restores the decision at once, and the critical section below sees the decision gone and writes nothing (#792).
        val details = detailsSource.fetch(mediaId).getOrThrow()
        TriageTitleLocks.withTitle(mediaId) { writeVerdict(mediaId, details) }
    }

    private suspend fun writeVerdict(mediaId: MediaId, details: MediaDetails) {
        // Read live, under the lock. Gone means an Undo took the decision back, and writing anything would put the title back.
        val stored = repository.observeDecision(mediaId).first() ?: return
        val today = clock.todayEpochDay()

        // #791: the claim is the library's state immediately before the add that makes the entry. A stamped claim an
        // earlier attempt recorded is kept, because that attempt's add made the entry and the entry being there now
        // says nothing about who added it. Otherwise the library is read now. That covers a first attempt, and a retry
        // whose earlier attempt failed before its add: it recorded no claim, and its add is still the one that counts.
        val absentBeforeAdd = collectionApi.observeMembership(mediaId).first() == null
        // #1058: a claim recorded before the stamp column (createdEntry with no stamp) cannot say which entry it is
        // about. If an entry is present now it may be a replacement the person added, so the claim is retired rather
        // than transferred to it. Undo then leaves whatever is in the library alone. The cost, an entry this verdict
        // created surviving undo, is tracked in #1064.
        val legacyClaim = stored.createdEntry && stored.entryAddedAtEpochMs == null
        val claimed = absentBeforeAdd || (stored.createdEntry && !legacyClaim)

        collectionApi.add(details)
        // #902: the stamp belongs to the entry the claim is about. This attempt's add made a new entry, so that entry's
        // stamp is read. Otherwise the add found an entry already there, which may be a replacement the person added
        // after an earlier attempt's entry was removed; a retry keeps the stamp the claim recorded, never the
        // replacement's (undo then sees a different stamp and leaves the replacement alone).
        val stamp = when {
            absentBeforeAdd -> addedAtOf(mediaId)
            claimed -> stored.entryAddedAtEpochMs
            else -> null
        }

        // Recorded once the add has landed and before the ticks run, so a tick that fails midway still leaves undo the
        // full list. Only episodes not already seen are planned, so undo can never take back a viewing the person made.
        // Each planned tick's viewing is named now, at the moment it will be written (EpisodePlay.idOf), so the record
        // holds the ids the ticks will carry.
        val now = clock.nowEpochMs()
        val seenBefore = progressApi.observeSeenEpisodes(mediaId).first()
        val planned = plannedTicks(details, stored.verdict, seenBefore, today)
        repository.record(
            stored.copy(
                createdEntry = claimed,
                entryAddedAtEpochMs = stamp,
                tickedPlayIds = (stored.tickedPlayIds + planned.map { EpisodePlay.idOf(it, now) }).distinct(),
            ),
        )

        // One write for every planned tick: a whole show's Caught up is one transaction, and the viewings it writes
        // carry the ids recorded above. SKIP and LATER plan nothing; WATCHING ticks the first aired episode, so the show
        // derives Watching; a movie's Caught up ticks the film; a show's Caught up ticks every aired episode
        // (ProgressApi.markAllAiredSeen's set, not markPreviousSeen's).
        if (planned.isNotEmpty()) progressApi.recordFirstPlays(planned, watchedAtEpochMs = now)
        repository.markResolved(mediaId, resolved = true)
    }

    /** The added-at stamp of [mediaId]'s live library entry, or null when it is not saved. */
    private suspend fun addedAtOf(mediaId: MediaId): Long? = collectionApi.observeSummaries().first()
        .firstOrNull { it.mediaId == mediaId }
        ?.addedAtEpochMs

    /**
     * The episodes a verdict is about to tick, minus those already seen. Mirrors what [apply] writes: the first
     * aired episode for Watching, the movie tick, or every aired episode for a show's Caught up.
     */
    private fun plannedTicks(details: MediaDetails, verdict: TriageVerdict, seenBefore: Set<EpisodeId>, today: Long): List<EpisodeId> {
        val candidates = when (verdict) {
            TriageVerdict.SKIP, TriageVerdict.LATER -> emptyList()

            TriageVerdict.WATCHING -> listOfNotNull(details.firstAiredEpisode(today)?.id)

            TriageVerdict.CAUGHT_UP -> when (details.type) {
                MediaType.MOVIE -> listOf(EpisodeId.forMovie(details.id))
                MediaType.TV -> EpisodeOrdering.airedBy(details.seasons, today)
            }
        }
        return candidates.filterNot { it in seenBefore }
    }
}

/**
 * Takes a verdict back, completely, and only what it took.
 *
 * The library entry is removed only when the verdict created it (#767) **and**
 * the entry is still the one it created: its added-at stamp must match the one
 * recorded at decision time (#902). A title the person removed and added again
 * while the undo was pending has a new stamp, so it stays.
 *
 * Ticks are reversed by viewing, not by episode (#790), on every path. Undo removes
 * exactly the viewings the verdict wrote ([TriageDecision.tickedPlayIds]) through
 * [ProgressApi.removePlay]. An episode the person has since rewatched keeps that
 * rewatch and stays seen; an episode with no viewings left is un-ticked.
 *
 * This holds for an entry the verdict created too. Progress that was on the title
 * before the verdict is kept, because the verdict did not write it: it is what a
 * title removed earlier, with its ticks, comes back with. The entry is removed only
 * when no viewing of the title was made at or after the decision. A viewing made
 * since then means the person has engaged with the title, so the entry stays with
 * it, and nothing they did is removed.
 *
 * Residual: the sync merge of near-duplicate plays (`RemoteApplier.mergeDuplicatePlays`,
 * five minutes) can tombstone the verdict's viewing on another device before undo
 * runs. The undo is then a no-op for that viewing and the tick stays. That is
 * fail-safe: nothing the person made is removed.
 *
 * The cleanup runs before the decision is tombstoned, so a cleanup that fails
 * leaves the decision readable and a retried undo finishes the job.
 */
class UndoDecisionUseCase(
    private val repository: TriageDecisionRepository,
    private val collectionApi: CollectionApi,
    private val progressApi: ProgressApi,
) {
    suspend operator fun invoke(mediaId: MediaId, verdict: TriageVerdict) = TriageTitleLocks.withTitle(mediaId) {
        // Read before the restore soft-deletes the row. A missing row claims nothing, so nothing is removed.
        val stored = repository.observeDecision(mediaId).first()
        if (verdict.savesToCollection && stored != null) {
            val ownsEntry = stored.createdEntry &&
                stored.entryAddedAtEpochMs != null &&
                currentAddedAt(mediaId) == stored.entryAddedAtEpochMs
            // Every path takes back the verdict's viewings and only those. The owned path used to clear the whole
            // title here, which also erased a rewatch the person made after the verdict.
            stored.tickedPlayIds.forEach { progressApi.removePlay(it) }
            // Removed after the viewings, and only when the person has not watched the title since the decision.
            if (ownsEntry && !watchedSince(mediaId, stored.decidedAtEpochMs)) collectionApi.remove(mediaId)
        }
        // Tombstoning last: once soft-deleted, observeDecision reads null and the claim can no longer be read.
        repository.restore(mediaId)
    }

    private suspend fun currentAddedAt(mediaId: MediaId): Long? = collectionApi.observeSummaries().first()
        .firstOrNull { it.mediaId == mediaId }
        ?.addedAtEpochMs

    /** Whether any live viewing of [mediaId] was made at or after [sinceEpochMs]. Read after the verdict's viewings are gone. */
    private suspend fun watchedSince(mediaId: MediaId, sinceEpochMs: Long): Boolean = progressApi.observePlayCounts(mediaId).first().keys.any { episodeId ->
        progressApi.observePlays(episodeId).first().any { it.watchedAtEpochMs >= sinceEpochMs }
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
) : DeckFeed {
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
    override suspend fun open(placement: SnoozePlacement): DeckSession {
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

        val excludedNow = settled + pending
        val dueReturning = returning.map { it.toSummary() }
        return DeckSession { filter, cursor, alreadyShown, wanted ->
            loader.load(
                filter = filter,
                cursor = cursor,
                excluded = excludedNow + alreadyShown,
                dueSnoozes = DueSnoozes(cards = dueReturning, placement = placement),
                wanted = wanted,
            )
        }
    }

    /** Where the deck stood for [filter] when this device last saved it, rewound per [DeckResume]; page 1 otherwise. */
    override suspend fun restoreCursor(filter: DeckFilter): DeckCursor {
        val saved = resumption.cursors.read(DeckResume.key(filter, resumption.language.current()))
        return DeckResume.restore(saved, clock.nowEpochMs())
    }

    /**
     * Saves where the deck stands. [unactedMovie] and [unactedTv] are the earliest pages still holding a card
     * the person has not acted on, or null when none does (see [SavedDeckCursor]).
     */
    override suspend fun checkpoint(filter: DeckFilter, cursor: DeckCursor, unactedMovie: Int?, unactedTv: Int?) {
        val saved = DeckResume.checkpoint(cursor, unactedMovie, unactedTv, clock.nowEpochMs())
        resumption.cursors.write(DeckResume.key(filter, resumption.language.current()), saved)
    }
}

/**
 * One refill's view of the deck: what it must not offer and which Snoozes have come due, read once by [DeckFeed.open].
 * Every batch the refill asks for shares them, and a Snooze retires or re-enters once, not once per batch. [reader]
 * is what a batch is read with; the catalogue and a seeded deck each supply their own.
 */
class DeckSession internal constructor(
    private val reader: suspend (DeckFilter, DeckCursor, Set<MediaId>, Int) -> Result<DeckBatch>,
) {
    /** [alreadyShown] carries the cards the refill has handed out so far; a returning Snooze already shown is in it. */
    suspend fun next(
        filter: DeckFilter,
        cursor: DeckCursor,
        alreadyShown: Set<MediaId>,
        wanted: Int,
    ): Result<DeckBatch> = reader(filter, cursor, alreadyShown, wanted)
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
