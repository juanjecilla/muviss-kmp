package com.codingpit.muviss.feature.progress.api

import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.Season
import kotlinx.coroutines.flow.Flow

/**
 * Public contract of the progress feature. Peers (search's `DetailScreen`,
 * collection's status derivation) depend on this module only — never
 * progress's domain/data/ui — to tick episodes and read watched state without
 * knowing anything about how progress is stored.
 */
interface ProgressApi {
    /** Seen episode ids for [mediaId] (movies use the single id from [EpisodeId.forMovie]). */
    fun observeSeenEpisodes(mediaId: MediaId): Flow<Set<EpisodeId>>

    /**
     * Every distinct epoch-day (UTC, [com.codingpit.muviss.core.common.todayEpochDay]'s
     * convention) on which at least one episode or movie is currently marked
     * seen, across every title — the raw calendar the profile feature derives
     * its watch-streak stat from. Un-ticking an episode can remove a day if no
     * other seen row shares it (progress has no separate history log, ADR 0005).
     */
    fun observeSeenActivityEpochDays(): Flow<Set<Long>>

    /** Ticks a single episode, e.g. a per-episode checkmark in Detail. */
    suspend fun setEpisodeSeen(episodeId: EpisodeId, seen: Boolean)

    /** Marks every episode in [season] as seen. */
    suspend fun markSeasonSeen(season: Season)

    /** Marks every episode at or before [target] across [seasons] as seen — the "catch me up" action. */
    suspend fun markPreviousSeen(seasons: List<Season>, target: EpisodeId)

    /**
     * Marks every episode that has aired by [todayEpochDay] as seen, and
     * nothing else — the "I am fully up to date" action triage's `CaughtUp`
     * verdict uses. Unlike [markPreviousSeen] this never ticks an unaired or
     * undated episode, which would make seen exceed aired and break status
     * derivation (ADR 0005).
     */
    suspend fun markAllAiredSeen(seasons: List<Season>, todayEpochDay: Long)

    /**
     * Un-ticks every episode (or the movie tick) for [mediaId]. Triage's undo
     * uses it to reverse the progress a verdict wrote; without it, taking back
     * a "caught up" swipe would leave the title reading as fully watched.
     */
    suspend fun clearProgress(mediaId: MediaId)

    /** Toggles a movie's watched flag. */
    suspend fun setMovieWatched(mediaId: MediaId, watched: Boolean)
}
