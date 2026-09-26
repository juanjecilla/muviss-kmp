package com.codingpit.muviss.feature.triage.domain

import com.codingpit.muviss.models.MediaId
import kotlinx.coroutines.flow.Flow

/**
 * Persistence contract for the snooze log (ADR 0023). Implemented in
 * `:feature:triage:data`.
 *
 * Deletes are soft throughout, like `episodePlay` (ADR 0013): last-write-wins
 * cannot express a hard delete, so an unsnooze that physically removed the row
 * would simply be pushed back by the other device.
 */
interface TriageSnoozeRepository {
    /** Every pending Snooze, soonest to come back first. */
    fun observeAll(): Flow<List<TriageSnooze>>

    /**
     * Every snoozed MediaId. Keeps a pending title out of the deck and out of
     * Discover's "For you" — one query, whatever the row count.
     */
    fun observeSnoozedIds(): Flow<Set<MediaId>>

    fun observeSnooze(mediaId: MediaId): Flow<TriageSnooze?>

    /** Writes (or overwrites) the standing Snooze for a title, reviving a tombstone. */
    suspend fun snooze(snooze: TriageSnooze)

    /** Soft-deletes the Snooze. Idempotent, and what undo and "Unsnooze" both call. */
    suspend fun unsnooze(mediaId: MediaId)

    /** Snoozes that have come due, oldest due date first. */
    suspend fun due(today: Long): List<TriageSnooze>
}
