package com.codingpit.muviss.core.common.concurrency

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * How many titles a bulk metadata refresh works on at once. Small on purpose:
 * the metadata source rate-limits, and the point of overlapping is to hide
 * latency, not to flood it. The foreground library refresh, the background
 * new-episode worker and the episode-catalog refresh all use this one number.
 */
const val REFRESH_CONCURRENCY: Int = 4

/**
 * [transform] applied to every element with at most [limit] running at once,
 * results in the order of the receiver. Unlike a serial `map` the wall-clock
 * cost of N slow calls is about N / [limit] of one; unlike an unbounded
 * `async` fan-out it never has more than [limit] in flight.
 *
 * An exception thrown by [transform] cancels the siblings and propagates, as
 * with any structured `coroutineScope`; callers that want best-effort per
 * element catch inside [transform].
 */
suspend fun <T, R> Iterable<T>.mapBounded(limit: Int = REFRESH_CONCURRENCY, transform: suspend (T) -> R): List<R> {
    require(limit > 0) { "limit must be positive, was $limit" }
    return coroutineScope {
        val inFlight = Semaphore(limit)
        map { element -> async { inFlight.withPermit { transform(element) } } }.awaitAll()
    }
}
