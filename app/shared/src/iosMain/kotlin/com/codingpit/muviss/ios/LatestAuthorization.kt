package com.codingpit.muviss.ios

import kotlin.concurrent.AtomicLong

/**
 * The last notification authorization iOS answered, kept with the number of the read that asked for it.
 *
 * iOS answers `getNotificationSettingsWithCompletionHandler` in whatever order it likes, so an answer to an older
 * read can arrive after a newer one. [publish] keeps only the newest read's answer, which is what the synchronous
 * `blocked()` / `canAskAgain()` mappings read (#846).
 */
internal class LatestAuthorization(initial: Long) {

    private val reads = AtomicLong(0L)
    private val latest = AtomicLong(pack(0L, initial))

    /** A number for a read about to be asked of iOS. Numbers are never reused. */
    fun nextRead(): Long = reads.incrementAndGet()

    /** The authorization status of the newest answer published so far. */
    val status: Long get() = latest.value and STATUS_MASK

    /** Keeps [status] unless the answer to [read] is newer than the one already kept. */
    fun publish(read: Long, status: Long) {
        while (true) {
            val current = latest.value
            if ((current ushr SEQUENCE_SHIFT) >= read) return
            if (latest.compareAndSet(current, pack(read, status))) return
        }
    }

    private fun pack(read: Long, status: Long): Long = (read shl SEQUENCE_SHIFT) or status

    private companion object {
        /** UNAuthorizationStatus has five values, all below this. */
        const val SEQUENCE_SHIFT = 8
        const val STATUS_MASK = 0xFFL
    }
}
