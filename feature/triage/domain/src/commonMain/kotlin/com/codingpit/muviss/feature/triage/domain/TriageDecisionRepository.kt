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

/** Whether the triage tutorial has been shown on this device. Backed by `appSettings`. */
interface TriagePreferences {
    fun observeTutorialSeen(): Flow<Boolean>

    suspend fun setTutorialSeen(seen: Boolean)
}
