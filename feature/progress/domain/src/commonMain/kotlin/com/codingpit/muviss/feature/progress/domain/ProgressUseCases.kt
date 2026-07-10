package com.codingpit.muviss.feature.progress.domain

import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.Season
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Observes every progress row for a title, e.g. to render per-episode checkmarks. */
class ObserveEpisodeProgressUseCase(private val repository: ProgressRepository) {
    operator fun invoke(mediaId: MediaId): Flow<List<EpisodeProgress>> = repository.observeForMedia(mediaId)
}

/** Observes the set of seen episode ids for a title — the shape watch-next and Detail need. */
class ObserveSeenEpisodesUseCase(private val repository: ProgressRepository) {
    operator fun invoke(mediaId: MediaId): Flow<Set<EpisodeId>> = repository.observeForMedia(mediaId)
        .map { rows -> rows.filter { it.seen }.map { it.episodeId }.toSet() }
}

/** Observes the seen-episode count for a title — feeds [com.codingpit.muviss.core.model.WatchStatusCalculator]. */
class ObserveSeenCountUseCase(private val repository: ProgressRepository) {
    operator fun invoke(mediaId: MediaId): Flow<Int> = repository.observeSeenCount(mediaId)
}

/** Ticks one episode. */
class ToggleEpisodeSeenUseCase(private val repository: ProgressRepository) {
    suspend operator fun invoke(episodeId: EpisodeId, seen: Boolean) = repository.setSeen(episodeId, seen)
}

/** Marks every episode in [season] as seen. */
class MarkSeasonSeenUseCase(private val repository: ProgressRepository) {
    suspend operator fun invoke(season: Season) = repository.setSeenBulk(season.episodes.map { it.id }, seen = true)
}

/**
 * Marks every episode at or before [target] (inclusive, across all of
 * [seasons]) as seen — the "I'm caught up through here" catch-up action.
 */
class MarkPreviousSeenUseCase(private val repository: ProgressRepository) {
    suspend operator fun invoke(seasons: List<Season>, target: EpisodeId) = repository.setSeenBulk(EpisodeOrdering.upToInclusive(seasons, target), seen = true)
}

/** Toggles a movie's single watched tick (see [EpisodeId.forMovie]). */
class SetMovieWatchedUseCase(private val repository: ProgressRepository) {
    suspend operator fun invoke(mediaId: MediaId, watched: Boolean) = repository.setSeen(EpisodeId.forMovie(mediaId), watched)
}

/** Fetches a show's season/episode structure, e.g. to compute watch-next. */
class FetchEpisodeCatalogUseCase(private val source: EpisodeCatalogSource) {
    suspend operator fun invoke(mediaId: MediaId): Result<List<Season>> = source.fetch(mediaId)
}
