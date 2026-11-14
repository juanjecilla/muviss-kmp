package com.codingpit.muviss.core.network

import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.models.MediaId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Titles TMDB answered "not found" for, so a pick does not spend its request budget on them again (#762). In
 * memory for the life of the process: a restart forgets them, which costs one more round of requests and is
 * acceptable. An entry expires after [ttlMs], so a title TMDB later gains is asked about again.
 */
class NotFoundTitles(private val clock: AppClock, private val ttlMs: Long = TTL_MS) {
    private val lock = Mutex()
    private val recordedAt = mutableMapOf<MediaId, Long>()

    /** Records [mediaId] as missing now, and drops entries that have already expired. */
    suspend fun record(mediaId: MediaId) = lock.withLock {
        val now = clock.nowEpochMs()
        recordedAt.entries.removeAll { now - it.value >= ttlMs }
        recordedAt[mediaId] = now
    }

    /** The subset of [mediaIds] still recorded as missing and not yet expired. */
    suspend fun known(mediaIds: Set<MediaId>): Set<MediaId> = lock.withLock {
        val now = clock.nowEpochMs()
        mediaIds.filterTo(hashSetOf()) { id -> recordedAt[id]?.let { now - it < ttlMs } == true }
    }

    companion object {
        /** A day: long enough that a pick does not re-ask the same titles, short enough that a new title is found. */
        const val TTL_MS: Long = 24L * 60 * 60 * 1000
    }
}
