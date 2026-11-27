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
 *
 * **Not reentrant.** [write] and [erase] hold one [Mutex], so a block passed to either must not call [write] or
 * [erase] again: the inner call would wait on the lock its own outer call holds, and nothing would ever resume. No
 * writer does this today. Keep the work that needs the fence inside one block.
 */
class DataEraseFence {
    private val lock = Mutex()

    @Volatile
    private var erasures = 0L

    /** Taken when the request starts, before any suspension: the erase count it was made under. */
    fun request(): Long = erasures

    /**
     * Takes the request count, then runs [fetch]. The returned [Guarded] writes the fetched data only if no erase
     * has run since the count was taken. Every TMDB writer of local data is this shape (#1056); this is the one
     * place the count and the check are paired, so a call site cannot take the count after its fetch.
     */
    suspend fun <T> guardedFetch(fetch: suspend () -> T): Guarded<T> = batch().guardedFetch(fetch)

    /**
     * Takes the request count once, for a whole batch of fetches, before the first of them is requested. Every
     * result of the batch is written against that one count, so a title asked after Delete all data in the middle
     * of the batch is dropped with the rest (#1090). A writer that takes a count per title defeats this: each title
     * would pass its own check.
     */
    fun batch(): Batch = Batch(request())

    /** The count a batch was requested under; see [batch]. */
    inner class Batch internal constructor(private val requestedAt: Long) {
        /** Runs [fetch] and pairs its result with the count [batch] took before any fetch of the batch started. */
        suspend fun <T> guardedFetch(fetch: suspend () -> T): Guarded<T> = Guarded(fetch(), this@DataEraseFence, requestedAt)
    }

    /** A fetched value and the count it was requested under; see [guardedFetch]. */
    class Guarded<T> internal constructor(
        val value: T,
        private val fence: DataEraseFence,
        private val requestedAt: Long,
    ) {
        /** Runs [block] unless an erase has run since the fetch began; reports whether it ran. */
        suspend fun write(block: suspend () -> Unit): Boolean = fence.write(requestedAt, block)
    }

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
