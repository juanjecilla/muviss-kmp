package com.codingpit.muviss.feature.collection.domain

import com.codingpit.muviss.core.common.AppClock

/**
 * Holds off the *automatic* library refresh that
 * [CollectionViewModel][com.codingpit.muviss.feature.collection.ui.CollectionViewModel]
 * fires from its `init`.
 *
 * That refresh re-fetches every saved title, and a TV title costs one request
 * per season on top of the show itself — so running it again every time a
 * fresh back-stack entry builds the Collection screen is a lot of network for
 * data that changes daily at most. A single [claimAutomaticRefresh] gate means
 * it happens once per [minIntervalMs] instead.
 *
 * Deliberately in-memory (a Koin `single`, so process-lifetime) rather than a
 * persisted column, the same trade-off
 * [EpisodeCatalogCache][com.codingpit.muviss.feature.progress.domain.EpisodeCatalogCache]
 * makes: cold on each launch, which is exactly when a refresh is worth doing
 * anyway.
 *
 * An explicit pull-to-refresh never consults this — it calls [recordRefresh]
 * instead, so a manual refresh also restarts the interval.
 */
class CollectionRefreshThrottle(
    private val clock: AppClock,
    private val minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS,
) {
    private var lastRefreshAtEpochMs: Long? = null

    /** True (and starts a new interval) when the automatic refresh is due; false while it is still held off. */
    fun claimAutomaticRefresh(): Boolean {
        val last = lastRefreshAtEpochMs
        val now = clock.nowEpochMs()
        if (last != null && now - last < minIntervalMs) return false
        lastRefreshAtEpochMs = now
        return true
    }

    /** Restarts the interval without asking — what an explicit refresh reports. */
    fun recordRefresh() {
        lastRefreshAtEpochMs = clock.nowEpochMs()
    }

    private companion object {
        const val DEFAULT_MIN_INTERVAL_MS = 12L * 60 * 60 * 1000
    }
}
