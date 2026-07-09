package com.codingpit.muviss.feature.collection.domain

import com.codingpit.muviss.core.model.WatchProgress
import com.codingpit.muviss.core.model.WatchStatusCalculator
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaType
import com.codingpit.muviss.models.ProductionStatus
import com.codingpit.muviss.models.WatchStatus

/**
 * A title the user has saved, together with the denormalized snapshot taken
 * at save time (see CONTEXT.md "Snapshot"). [mediaType] is read off [mediaId]
 * rather than duplicated.
 *
 * [status] is always derived via [WatchStatusCalculator] — this feature never
 * stores it (ADR 0005). Until the progress feature lands there is no
 * per-episode data, so [seenEpisodes] defaults to 0 and every entry derives
 * [WatchStatus.NOT_STARTED].
 */
data class CollectionEntry(
    val mediaId: MediaId,
    val title: String,
    val posterUrl: String?,
    val releaseYear: Int?,
    val productionStatus: ProductionStatus,
    val totalEpisodes: Int,
    val airedEpisodes: Int,
    val favorite: Boolean,
    val addedAtEpochMs: Long,
    val seenEpisodes: Int = 0,
) {
    val mediaType: MediaType get() = mediaId.type

    val status: WatchStatus
        get() = WatchStatusCalculator.derive(
            WatchProgress(
                mediaType = mediaType,
                seenEpisodes = seenEpisodes,
                airedEpisodes = airedEpisodes,
                totalEpisodes = totalEpisodes,
                productionStatus = productionStatus,
                favorite = favorite,
            ),
        )
}
