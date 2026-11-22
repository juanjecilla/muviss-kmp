package com.codingpit.muviss.feature.triage.domain

import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.MediaId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * What a verdict wrote that Undo has to take back, and the per-title lock that keeps the two from
 * overlapping (#790, #792, #919).
 *
 * One instance is shared by [RecordDecisionUseCase] and [UndoDecisionUseCase] (a Koin `single`), and
 * it lives in memory on purpose. The Undo it serves is a snackbar of the running process; a verdict
 * whose process died has no Undo left to reverse it, so a persisted copy would only be state with no
 * reader.
 */
class TriageUndoLedger {
    private val guard = Mutex()
    private val gates = mutableMapOf<MediaId, Mutex>()
    private val ticks = mutableMapOf<MediaId, List<EpisodeId>>()

    /**
     * Runs [block] while no other [exclusive] block for [mediaId] runs. A verdict's write and its Undo
     * both take it, so an Undo pressed while the details fetch is still in flight waits for the write to
     * land and then reverses it, instead of finding nothing to reverse and leaving the title behind.
     */
    suspend fun <T> exclusive(mediaId: MediaId, block: suspend () -> T): T {
        val gate = guard.withLock { gates.getOrPut(mediaId) { Mutex() } }
        return gate.withLock { block() }
    }

    /** Adds [ids], the episodes a verdict actually ticked, to what Undo will reverse for [mediaId]. */
    suspend fun recordTicks(mediaId: MediaId, ids: List<EpisodeId>) {
        if (ids.isEmpty()) return
        guard.withLock { ticks[mediaId] = (ticks[mediaId].orEmpty() + ids).distinct() }
    }

    /** The episodes the verdict on [mediaId] ticked, without forgetting them: a failed Undo has to be retryable. */
    suspend fun ticksOf(mediaId: MediaId): List<EpisodeId> = guard.withLock { ticks[mediaId].orEmpty() }

    suspend fun forget(mediaId: MediaId) {
        guard.withLock { ticks.remove(mediaId) }
    }
}
