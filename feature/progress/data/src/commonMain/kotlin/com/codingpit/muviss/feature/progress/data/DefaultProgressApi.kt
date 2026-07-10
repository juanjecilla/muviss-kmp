package com.codingpit.muviss.feature.progress.data

import com.codingpit.muviss.feature.progress.api.ProgressApi
import com.codingpit.muviss.feature.progress.domain.MarkPreviousSeenUseCase
import com.codingpit.muviss.feature.progress.domain.MarkSeasonSeenUseCase
import com.codingpit.muviss.feature.progress.domain.ObserveSeenActivityEpochDaysUseCase
import com.codingpit.muviss.feature.progress.domain.ObserveSeenEpisodesUseCase
import com.codingpit.muviss.feature.progress.domain.SetMovieWatchedUseCase
import com.codingpit.muviss.feature.progress.domain.ToggleEpisodeSeenUseCase
import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.Season
import kotlinx.coroutines.flow.Flow

/** Bridges the progress feature's use cases to its public [ProgressApi]. */
internal class DefaultProgressApi(
    private val observeSeenEpisodes: ObserveSeenEpisodesUseCase,
    private val toggleEpisodeSeen: ToggleEpisodeSeenUseCase,
    private val markSeasonSeen: MarkSeasonSeenUseCase,
    private val markPreviousSeen: MarkPreviousSeenUseCase,
    private val setMovieWatched: SetMovieWatchedUseCase,
    private val observeSeenActivityEpochDays: ObserveSeenActivityEpochDaysUseCase,
) : ProgressApi {

    override fun observeSeenEpisodes(mediaId: MediaId): Flow<Set<EpisodeId>> = observeSeenEpisodes.invoke(mediaId)

    override fun observeSeenActivityEpochDays(): Flow<Set<Long>> = observeSeenActivityEpochDays.invoke()

    override suspend fun setEpisodeSeen(episodeId: EpisodeId, seen: Boolean) = toggleEpisodeSeen(episodeId, seen)

    override suspend fun markSeasonSeen(season: Season) = markSeasonSeen.invoke(season)

    override suspend fun markPreviousSeen(seasons: List<Season>, target: EpisodeId) = markPreviousSeen.invoke(seasons, target)

    override suspend fun setMovieWatched(mediaId: MediaId, watched: Boolean) = setMovieWatched.invoke(mediaId, watched)
}
