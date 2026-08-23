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
 * Search uses [observeDecidedIds] to keep skipped titles out of Discover's
 * "For you" suggestions, and search's Detail screen uses [observeDecision] to
 * offer an undo. Neither reads this to decide what is *saved* — that stays
 * `CollectionApi`'s question.
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
}
