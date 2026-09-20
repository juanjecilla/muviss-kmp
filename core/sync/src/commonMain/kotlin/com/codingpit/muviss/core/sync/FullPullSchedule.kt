package com.codingpit.muviss.core.sync

/**
 * How often a device pulls from the beginning instead of from its cursors
 * (EPIC 40, ADR 0021). The server hands out sequence numbers at insert time,
 * not commit time, so a transaction that commits late can leave a row behind a
 * cursor another device has already moved past. The incremental pull can never
 * see that row; a weekly full pull bounds how long it stays hidden.
 */
internal object FullPullSchedule {
    const val INTERVAL_MS: Long = 7L * 24 * 60 * 60 * 1000

    /** Due when there is no record of one, or the last was at least [INTERVAL_MS] ago. */
    fun isDue(lastFullPullAtEpochMs: Long?, nowEpochMs: Long): Boolean = lastFullPullAtEpochMs == null || nowEpochMs - lastFullPullAtEpochMs >= INTERVAL_MS
}
