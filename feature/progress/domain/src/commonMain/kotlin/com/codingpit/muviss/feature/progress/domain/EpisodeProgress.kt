package com.codingpit.muviss.feature.progress.domain

import com.codingpit.muviss.models.EpisodeId

/**
 * A single episode's watched tick — the source of truth
 * [WatchStatus][com.codingpit.muviss.models.WatchStatus] is derived from
 * (ADR 0005). Movies are ticked through [EpisodeId.forMovie]'s synthetic id.
 */
data class EpisodeProgress(
    val episodeId: EpisodeId,
    val seen: Boolean,
    val updatedAtEpochMs: Long,
)
