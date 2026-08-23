package com.codingpit.muviss.feature.triage.domain

import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.core.common.todayEpochDay
import com.codingpit.muviss.feature.collection.api.CollectionApi
import com.codingpit.muviss.feature.progress.api.ProgressApi
import com.codingpit.muviss.feature.triage.api.SkippedTitle
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
    private val collectionApi: CollectionApi,
) {
    suspend operator fun invoke(filter: DeckFilter, cursor: DeckCursor, alreadyShown: Set<MediaId> = emptySet()): Result<DeckBatch> {
        val decided = repository.observeDecidedIds().first()
        val inCollection = collectionApi.observeSummaries().first().map { it.mediaId }.toSet()
        return loader.load(filter, cursor, excluded = decided + inCollection + alreadyShown)
    }
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
)
