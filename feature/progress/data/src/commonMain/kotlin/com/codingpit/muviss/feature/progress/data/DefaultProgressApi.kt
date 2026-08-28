package com.codingpit.muviss.feature.progress.data

import com.codingpit.muviss.feature.progress.api.EpisodePlay
import com.codingpit.muviss.feature.progress.api.ProgressApi
import com.codingpit.muviss.feature.progress.domain.ObservePlayCountsUseCase
import com.codingpit.muviss.feature.progress.domain.ObservePlaysUseCase
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
    private val playObservers: ProgressPlayObservers,
) : ProgressApi {

    override fun observeSeenEpisodes(mediaId: MediaId): Flow<Set<EpisodeId>> = observeSeenEpisodes.invoke(mediaId)

    override fun observeSeenActivityEpochDays(): Flow<Set<Long>> = observeSeenActivityEpochDays.invoke()

    override suspend fun setEpisodeSeen(episodeId: EpisodeId, seen: Boolean) = mutations.toggleEpisodeSeen(episodeId, seen)

    override fun observePlayCounts(mediaId: MediaId): Flow<Map<EpisodeId, Int>> = playObservers.counts(mediaId)

    override fun observePlays(episodeId: EpisodeId): Flow<List<EpisodePlay>> = playObservers.plays(episodeId)

    override suspend fun recordPlay(episodeId: EpisodeId) = mutations.plays.recordPlay(episodeId)

    override suspend fun removeLatestPlay(episodeId: EpisodeId) = mutations.plays.removeLatestPlay(episodeId)

    override suspend fun clearPlays(episodeId: EpisodeId) = mutations.plays.clearPlays(episodeId)

    override suspend fun markSeasonAiredSeen(season: Season, todayEpochDay: Long) = mutations.bulk.markSeasonAiredSeen(season, todayEpochDay)

    override suspend fun markShowAiredSeen(seasons: List<Season>, todayEpochDay: Long) = mutations.bulk.markShowAiredSeen(seasons, todayEpochDay)

    override suspend fun unmarkSeason(season: Season) = mutations.bulk.unmarkSeasons(listOf(season))

    override suspend fun unmarkShow(seasons: List<Season>) = mutations.bulk.unmarkSeasons(seasons)

    override suspend fun undoBulkMark(episodeIds: List<EpisodeId>) = mutations.bulk.undoBulkMark(episodeIds)

    override suspend fun markPreviousSeen(seasons: List<Season>, target: EpisodeId) = mutations.bulk.markPreviousSeen(seasons, target)

    override suspend fun markAllAiredSeen(seasons: List<Season>, todayEpochDay: Long) = mutations.bulk.markAllAiredSeen(seasons, todayEpochDay)

    override suspend fun clearProgress(mediaId: MediaId) = mutations.clearProgress(mediaId)

    override suspend fun setMovieWatched(mediaId: MediaId, watched: Boolean) = mutations.setMovieWatched(mediaId, watched)
}

/**
 * The two play-history read paths, bundled so [DefaultProgressApi]'s
 * constructor stays inside detekt's `LongParameterList` budget — the same
 * trick [ProgressMutations] uses for the write half.
 */
internal class ProgressPlayObservers(
    private val observePlayCounts: ObservePlayCountsUseCase,
    private val observePlays: ObservePlaysUseCase,
) {
    fun counts(mediaId: MediaId): Flow<Map<EpisodeId, Int>> = observePlayCounts(mediaId)
    fun plays(episodeId: EpisodeId): Flow<List<EpisodePlay>> = observePlays(episodeId)
}
