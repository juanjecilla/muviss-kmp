package com.codingpit.muviss.feature.progress.data

import com.codingpit.muviss.feature.progress.api.ProgressApi
import com.codingpit.muviss.feature.progress.domain.ObserveSeenActivityEpochDaysUseCase
import com.codingpit.muviss.feature.progress.domain.ObserveSeenEpisodesUseCase
import com.codingpit.muviss.feature.progress.domain.ProgressMutations
import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.Season
import kotlinx.coroutines.flow.Flow

/** Bridges the progress feature's use cases to its public [ProgressApi]. */
internal class DefaultProgressApi(
    private val observeSeenEpisodes: ObserveSeenEpisodesUseCase,
    private val mutations: ProgressMutations,
    private val observeSeenActivityEpochDays: ObserveSeenActivityEpochDaysUseCase,
) : ProgressApi {

    override fun observeSeenEpisodes(mediaId: MediaId): Flow<Set<EpisodeId>> = observeSeenEpisodes.invoke(mediaId)

    override fun observeSeenActivityEpochDays(): Flow<Set<Long>> = observeSeenActivityEpochDays.invoke()

    override suspend fun setEpisodeSeen(episodeId: EpisodeId, seen: Boolean) = mutations.toggleEpisodeSeen(episodeId, seen)

    override suspend fun markSeasonSeen(season: Season) = mutations.markSeasonSeen(season)

    override suspend fun markPreviousSeen(seasons: List<Season>, target: EpisodeId) = mutations.markPreviousSeen(seasons, target)

    override suspend fun markAllAiredSeen(seasons: List<Season>, todayEpochDay: Long) = mutations.markAllAiredSeen(seasons, todayEpochDay)

    override suspend fun clearProgress(mediaId: MediaId) = mutations.clearProgress(mediaId)

    override suspend fun setMovieWatched(mediaId: MediaId, watched: Boolean) = mutations.setMovieWatched(mediaId, watched)
}
