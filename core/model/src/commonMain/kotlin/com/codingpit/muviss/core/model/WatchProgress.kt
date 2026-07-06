package com.codingpit.muviss.core.model

import com.codingpit.muviss.models.MediaType
import com.codingpit.muviss.models.ProductionStatus

/**
 * A snapshot of how far a user has progressed through a title. This is the
 * ground truth from which [WatchStatus][com.codingpit.muviss.models.WatchStatus]
 * is derived.
 *
 * For movies, [airedEpisodes] and [totalEpisodes] are both 1 and [seenEpisodes]
 * is 0 or 1.
 */
data class WatchProgress(
    val mediaType: MediaType,
    val seenEpisodes: Int,
    val airedEpisodes: Int,
    val totalEpisodes: Int,
    val productionStatus: ProductionStatus,
    val favorite: Boolean = false,
) {
    init {
        require(seenEpisodes >= 0) { "seenEpisodes must be >= 0" }
        require(seenEpisodes <= airedEpisodes) { "cannot have seen more than aired" }
    }
}
