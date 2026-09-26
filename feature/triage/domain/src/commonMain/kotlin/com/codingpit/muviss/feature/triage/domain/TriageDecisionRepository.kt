package com.codingpit.muviss.feature.triage.domain

import com.codingpit.muviss.feature.triage.api.TriageVerdict
import com.codingpit.muviss.models.MediaId
import kotlinx.coroutines.flow.Flow

/** Persistence contract for the decision log. Implemented in `:feature:triage:data`. */
interface TriageDecisionRepository {
    fun observeByVerdict(verdict: TriageVerdict): Flow<List<TriageDecision>>

    /** The deck's exclusion set — one query, whatever the row count. */
    fun observeDecidedIds(): Flow<Set<MediaId>>

    fun observeDecision(mediaId: MediaId): Flow<TriageDecision?>

    /** Writes (or overwrites) the standing decision for a title. */
    suspend fun record(decision: TriageDecision)

    suspend fun markResolved(mediaId: MediaId, resolved: Boolean)

    /** Soft-deletes the decision so the title is deck-eligible again. Idempotent. */
    suspend fun restore(mediaId: MediaId)

    /** Decisions whose side effects never completed, oldest first. */
    suspend fun unresolved(): List<TriageDecision>
}

/** One-shot onboarding state for the deck, per device. Backed by `appSettings`. */
interface TriagePreferences {
    fun observeTutorialSeen(): Flow<Boolean>

    suspend fun setTutorialSeen(seen: Boolean)

    /**
     * Whether the snooze hint has been shown (EPIC 42).
     *
     * A second flag rather than a reuse of [observeTutorialSeen], because that
     * one is already true on every install that exists — folding the snooze
     * hint into the first-run dialog would show it to nobody who has the app
     * today, which is precisely the audience that has never seen the gesture.
     */
    fun observeSnoozeHintSeen(): Flow<Boolean>

    suspend fun setSnoozeHintSeen(seen: Boolean)
}
