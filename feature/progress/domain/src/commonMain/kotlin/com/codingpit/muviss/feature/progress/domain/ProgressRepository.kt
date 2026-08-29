package com.codingpit.muviss.feature.progress.domain

import com.codingpit.muviss.feature.progress.api.EpisodePlay
import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.MediaId
import kotlinx.coroutines.flow.Flow

/**
 * Domain-owned contract for per-episode watched ticks and their rewatch
 * history. The implementation lives in the data layer over SQLDelight
 * (`EpisodeProgress.sq` + `EpisodePlay.sq`).
 *
 * `seen` and the play rows are two views of the same fact and are always
 * written together here, never by callers separately — that is what keeps
 * them from drifting (ADR 0011).
 */
interface ProgressRepository {
    /** Every ticked-or-unticked row stored for [mediaId]; recomputed whenever it changes. */
    fun observeForMedia(mediaId: MediaId): Flow<List<EpisodeProgress>>

    /** Count of seen episodes for [mediaId] — the `seenEpisodes` input to [com.codingpit.muviss.core.model.WatchStatusCalculator]. */
    fun observeSeenCount(mediaId: MediaId): Flow<Int>

    /** Every distinct epoch-day carrying a recorded viewing, across all media — see [com.codingpit.muviss.feature.progress.api.ProgressApi.observeSeenActivityEpochDays]. */
    fun observeSeenActivityEpochDays(): Flow<Set<Long>>

    /** Play count per episode of [mediaId]; episodes never watched are absent. */
    fun observePlayCounts(mediaId: MediaId): Flow<Map<EpisodeId, Int>>

    /** Every viewing of [episodeId], newest first. */
    fun observePlays(episodeId: EpisodeId): Flow<List<EpisodePlay>>

    /** Rewatches per title since [sinceEpochMs] — see [com.codingpit.muviss.feature.progress.api.ProgressApi.observeRewatchCounts]. */
    fun observeRewatchCounts(sinceEpochMs: Long): Flow<Map<MediaId, Int>>

    /** Every rewatch's timestamp since [sinceEpochMs], across all titles. */
    fun observeRewatchTimestamps(sinceEpochMs: Long): Flow<List<Long>>

    /** Adds a viewing and ticks the episode. */
    suspend fun recordPlay(episodeId: EpisodeId)

    /** Adds one viewing to each id that has none, ticking those; returns exactly the ids it wrote. */
    suspend fun recordPlaysForUnseen(episodeIds: List<EpisodeId>): List<EpisodeId>

    /** Drops [episodeId]'s most recent viewing, un-ticking it only if none remain. */
    suspend fun removeLatestPlay(episodeId: EpisodeId)

    /** [removeLatestPlay] for each of [episodeIds]; ids with no viewings are left alone. */
    suspend fun removeLatestPlays(episodeIds: List<EpisodeId>)

    /** Forgets every viewing of [episodeId] and un-ticks it. */
    suspend fun clearPlays(episodeId: EpisodeId)

    /**
     * Ticks a single episode. `true` records a first viewing and is
     * idempotent; `false` clears the episode's history. Creates the row if it
     * doesn't exist yet.
     */
    suspend fun setSeen(episodeId: EpisodeId, seen: Boolean)

    /** [setSeen] for every id in [episodeIds] as one operation. */
    suspend fun setSeenBulk(episodeIds: List<EpisodeId>, seen: Boolean)

    /** Un-ticks every episode of [mediaId] and forgets its viewings — triage's undo. */
    suspend fun clearForMedia(mediaId: MediaId)
}
