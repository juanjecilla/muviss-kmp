package com.codingpit.muviss.feature.triage.api

import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaType
import kotlinx.coroutines.flow.Flow

/**
 * What the user decided about one MediaItem during triage (ADR 0010).
 *
 * A verdict is a record of intent, not a status: [WATCHING] and [CAUGHT_UP]
 * are made true by writing real `WatchProgress` ticks, and the resulting
 * `WatchStatus` is still derived by `WatchStatusCalculator` (ADR 0005). None
 * of these values is ever stored as a status.
 */
enum class TriageVerdict {
    /** Not for me. Saves nothing — this table is the only record it happened. */
    SKIP,

    /** Save it; haven't started. Derives NotStarted. */
    LATER,

    /** Save it; already started. TV only — a movie is never in progress. */
    WATCHING,

    /** Save it; every aired episode seen. Derives Watched, or Finished once production has ended. */
    CAUGHT_UP,
    ;

    /** True for the three verdicts that put the title in the collection. */
    val savesToCollection: Boolean get() = this != SKIP

    companion object {
        /** Tolerates a verdict written by a newer version of the app, or by another device. */
        fun fromStored(raw: String): TriageVerdict? = entries.firstOrNull { it.name == raw }

        /** [WATCHING] is meaningless for a movie, so it is never offered for one. */
        fun availableFor(type: MediaType): List<TriageVerdict> = when (type) {
            MediaType.MOVIE -> listOf(SKIP, LATER, CAUGHT_UP)
            MediaType.TV -> entries
        }
    }
}

/** A title the user skipped, with just enough to render it without a collection row to join. */
data class SkippedTitle(
    val mediaId: MediaId,
    val title: String,
    val posterUrl: String?,
    val decidedAtEpochMs: Long,
)

/**
 * A title whose decision the user postponed, with just enough to render it
 * without a collection row to join (EPIC 42, ADR 0023).
 *
 * [dueAtEpochDay] is an epoch DAY, not an instant: the title comes back for
 * the whole of that date in the device's own reckoning.
 */
data class SnoozedTitle(
    val mediaId: MediaId,
    val title: String,
    val posterUrl: String?,
    val snoozedAtEpochMs: Long,
    val dueAtEpochDay: Long,
)

/** One recorded decision, as peers see it. */
data class TriageDecisionSummary(
    val mediaId: MediaId,
    val verdict: TriageVerdict,
    val decidedAtEpochMs: Long,
    val resolved: Boolean = true,
)

/**
 * Public contract of the triage feature. Peers depend on this module only —
 * never triage's domain/data/ui (ADR 0004).
 *
 * Search uses [observeDecidedIds] and [observeSnoozedIds] to keep skipped and
 * postponed titles out of Discover's "For you" suggestions, and search's
 * Detail screen uses [observeDecision]/[observeSnooze] to offer an undo.
 * Neither reads this to decide what is *saved* — that stays `CollectionApi`'s
 * question.
 *
 * A Snooze is deliberately not a [TriageVerdict] (ADR 0023): the verdicts
 * record what the user decided, a Snooze records that they declined to. They
 * are separate here for the same reason they are separate tables.
 */
interface TriageApi {
    /** Every MediaId the user has ruled on and not restored — the deck's exclusion set. */
    fun observeDecidedIds(): Flow<Set<MediaId>>

    /** Skipped titles, newest decision first. */
    fun observeSkipped(): Flow<List<SkippedTitle>>

    /** The standing decision for one title, or null if it was never triaged or has been restored. */
    fun observeDecision(mediaId: MediaId): Flow<TriageDecisionSummary?>

    /** Forgets the decision so the title can appear in the deck again. Idempotent. */
    suspend fun restore(mediaId: MediaId)

    /**
     * Every MediaId with a Snooze pending, due or not. Kept out of the deck
     * until due, and out of Discover's "For you" throughout.
     */
    fun observeSnoozedIds(): Flow<Set<MediaId>>

    /** Postponed titles, soonest to come back first. */
    fun observeSnoozed(): Flow<List<SnoozedTitle>>

    /** The standing Snooze for one title, or null if it has none. */
    fun observeSnooze(mediaId: MediaId): Flow<SnoozedTitle?>

    /** Drops the Snooze so the title is deck-eligible again now. Idempotent. */
    suspend fun unsnooze(mediaId: MediaId)
}
