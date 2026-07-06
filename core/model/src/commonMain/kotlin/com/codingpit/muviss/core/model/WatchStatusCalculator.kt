package com.codingpit.muviss.core.model

import com.codingpit.muviss.models.MediaType
import com.codingpit.muviss.models.ProductionStatus
import com.codingpit.muviss.models.WatchStatus

/**
 * Derives [WatchStatus] from [WatchProgress]. Pure and total: the same input
 * always yields the same status, and there is no way to represent a
 * contradictory state (e.g. "Watched" with unseen aired episodes).
 *
 * Rules:
 * - Movies: 0 seen -> NOT_STARTED, else WATCHED.
 * - TV: 0 seen -> NOT_STARTED; some but not all aired -> WATCHING;
 *   all aired seen -> FINISHED if production ended, otherwise WATCHED.
 */
object WatchStatusCalculator {
    fun derive(progress: WatchProgress): WatchStatus = with(progress) {
        when (mediaType) {
            MediaType.MOVIE -> if (seenEpisodes == 0) WatchStatus.NOT_STARTED else WatchStatus.WATCHED
            MediaType.TV -> deriveTv(this)
        }
    }

    private fun deriveTv(progress: WatchProgress): WatchStatus = with(progress) {
        when {
            seenEpisodes == 0 -> WatchStatus.NOT_STARTED
            seenEpisodes < airedEpisodes -> WatchStatus.WATCHING
            productionHasEnded(productionStatus) -> WatchStatus.FINISHED
            else -> WatchStatus.WATCHED
        }
    }

    private fun productionHasEnded(status: ProductionStatus): Boolean = status == ProductionStatus.ENDED || status == ProductionStatus.CANCELED
}
