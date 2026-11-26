package com.codingpit.muviss.core.common.erase

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile

/**
 * Keeps data fetched before Delete all data from being written after it (#965).
 *
 * A request for local data (a For you refresh, say) asks TMDB, and TMDB answers after the erase may have
 * finished. A lock alone would not help: the late write would take it afterwards and still store the answer
 * for the library that was just deleted. So a write remembers which erase count it was requested under
 * ([request], taken when the request starts), and runs only if no erase has happened since. The check and the
 * write run under the same lock the erase takes, so neither interleaves with the other.
 *
 * Shaped like [com.codingpit.muviss.feature.settings.data.DeviceBackupWrites], which orders a toggle the same way.
 * Shared through Koin: the eraser and every writer must see the same instance.
 */
class DataEraseFence {
    private val lock = Mutex()

    @Volatile
    private var erasures = 0L

    /** Taken when the request starts, before any suspension: the erase count it was made under. */
    fun request(): Long = erasures

    /** Runs [block] unless Delete all data has run since [requestedAt]; reports whether it ran. */
    suspend fun write(requestedAt: Long, block: suspend () -> Unit): Boolean = lock.withLock {
        if (requestedAt != erasures) return@withLock false
        block()
        true
    }

    /**
     * Runs the erase. The count moves once it has finished, and in a `finally`, so a partial erase still
     * invalidates every write that was requested before it.
     */
    suspend fun <T> erase(block: suspend () -> T): T = lock.withLock {
        try {
            block()
        } finally {
            erasures++
        }
    }
}
