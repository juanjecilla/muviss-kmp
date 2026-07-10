package com.codingpit.muviss.feature.progress.domain

import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.MediaId
import kotlinx.coroutines.flow.Flow

/**
 * Domain-owned contract for per-episode watched ticks. The implementation
 * lives in the data layer over SQLDelight (`EpisodeProgress.sq`).
 */
interface ProgressRepository {
    /** Every ticked-or-unticked row stored for [mediaId]; recomputed whenever it changes. */
    fun observeForMedia(mediaId: MediaId): Flow<List<EpisodeProgress>>

    /** Count of seen episodes for [mediaId] — the `seenEpisodes` input to [com.codingpit.muviss.core.model.WatchStatusCalculator]. */
    fun observeSeenCount(mediaId: MediaId): Flow<Int>

    /** Ticks a single episode. Creates the row if it doesn't exist yet. */
    suspend fun setSeen(episodeId: EpisodeId, seen: Boolean)

    /** Ticks every id in [episodeIds] to [seen] as one operation. */
    suspend fun setSeenBulk(episodeIds: List<EpisodeId>, seen: Boolean)
}
