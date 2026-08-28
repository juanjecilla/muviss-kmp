package com.codingpit.muviss.feature.progress.domain

import com.codingpit.muviss.feature.progress.api.EpisodePlay
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

/** Observes every distinct day with watch activity — the profile feature's streak input. */
class ObserveSeenActivityEpochDaysUseCase(private val repository: ProgressRepository) {
    operator fun invoke(): Flow<Set<Long>> = repository.observeSeenActivityEpochDays()
}

/** Ticks one episode. */
class ToggleEpisodeSeenUseCase(private val repository: ProgressRepository) {
    suspend operator fun invoke(episodeId: EpisodeId, seen: Boolean) = repository.setSeen(episodeId, seen)
}

/**
 * Marks every episode of [season] that has aired as seen, and only those,
 * returning the ids it actually ticked.
 *
 * The aired filter is load-bearing, not tidiness. Ticking a currently-airing
 * season's unaired episodes pushes `seenEpisodes` past `airedEpisodes`, and
 * [WatchProgress][com.codingpit.muviss.core.model.WatchProgress] `require`s
 * the opposite — so the library screen threw the next time it derived that
 * title's status. This is the same trap [MarkAllAiredSeenUseCase] documents
 * for triage; see [EpisodeOrdering.airedBy].
 *
 * Episodes already seen are skipped rather than replayed: catching up on a
 * season is not a claim to have rewatched it.
 */
class MarkSeasonAiredSeenUseCase(private val repository: ProgressRepository) {
    suspend operator fun invoke(season: Season, todayEpochDay: Long): List<EpisodeId> = repository.recordPlaysForUnseen(EpisodeOrdering.airedBy(listOf(season), todayEpochDay))
}

/** [MarkSeasonAiredSeenUseCase] across every season — "mark the whole show seen". */
class MarkShowAiredSeenUseCase(private val repository: ProgressRepository) {
    suspend operator fun invoke(seasons: List<Season>, todayEpochDay: Long): List<EpisodeId> = repository.recordPlaysForUnseen(EpisodeOrdering.airedBy(seasons, todayEpochDay))
}

/**
 * Reverses a bulk mark by dropping the most recent viewing of each episode in
 * [seasons] — the mirror of the single-episode "I ticked that by mistake".
 *
 * Episodes with no viewings are untouched, and one genuinely watched three
 * times drops to two rather than being wiped, so the toggle may legitimately
 * not clear a season outright. Losing real rewatch history to a toggle would
 * be the worse failure.
 */
class UnmarkSeasonsUseCase(private val repository: ProgressRepository) {
    suspend operator fun invoke(seasons: List<Season>) = repository.removeLatestPlays(EpisodeOrdering.flatten(seasons).map { it.id })
}

/** Takes back exactly the ids a bulk mark reported writing — the undo snackbar. */
class UndoBulkMarkUseCase(private val repository: ProgressRepository) {
    suspend operator fun invoke(episodeIds: List<EpisodeId>) = repository.removeLatestPlays(episodeIds)
}

/** "I watched this again": records another viewing without disturbing the ones before it. */
class RecordPlayUseCase(private val repository: ProgressRepository) {
    suspend operator fun invoke(episodeId: EpisodeId) = repository.recordPlay(episodeId)
}

/** "I ticked that by mistake": drops the newest viewing only. */
class RemoveLatestPlayUseCase(private val repository: ProgressRepository) {
    suspend operator fun invoke(episodeId: EpisodeId) = repository.removeLatestPlay(episodeId)
}

/** Forgets an episode's whole watch history — episode detail's explicit "clear". */
class ClearPlaysUseCase(private val repository: ProgressRepository) {
    suspend operator fun invoke(episodeId: EpisodeId) = repository.clearPlays(episodeId)
}

/** How many times each episode of a title has been watched. */
class ObservePlayCountsUseCase(private val repository: ProgressRepository) {
    operator fun invoke(mediaId: MediaId): Flow<Map<EpisodeId, Int>> = repository.observePlayCounts(mediaId)
}

/** One episode's viewings, newest first. */
class ObservePlaysUseCase(private val repository: ProgressRepository) {
    operator fun invoke(episodeId: EpisodeId): Flow<List<EpisodePlay>> = repository.observePlays(episodeId)
}

/**
 * Marks every episode at or before [target] (inclusive, across all of
 * [seasons]) as seen — the "I'm caught up through here" catch-up action.
 */
class MarkPreviousSeenUseCase(private val repository: ProgressRepository) {
    suspend operator fun invoke(seasons: List<Season>, target: EpisodeId) = repository.setSeenBulk(EpisodeOrdering.upToInclusive(seasons, target), seen = true)
}

/**
 * Marks exactly the episodes that have aired by `todayEpochDay` as seen —
 * "I am completely up to date with this show". Triage's `CaughtUp` verdict
 * writes progress through this rather than through [MarkPreviousSeenUseCase];
 * see [EpisodeOrdering.airedBy] for why the difference matters.
 */
class MarkAllAiredSeenUseCase(private val repository: ProgressRepository) {
    suspend operator fun invoke(seasons: List<Season>, todayEpochDay: Long) = repository.setSeenBulk(EpisodeOrdering.airedBy(seasons, todayEpochDay), seen = true)
}

/** Un-ticks everything for one title — used to take back a triage verdict. */
class ClearProgressUseCase(private val repository: ProgressRepository) {
    suspend operator fun invoke(mediaId: MediaId) = repository.clearForMedia(mediaId)
}

/** Toggles a movie's single watched tick (see [EpisodeId.forMovie]). */
class SetMovieWatchedUseCase(private val repository: ProgressRepository) {
    suspend operator fun invoke(mediaId: MediaId, watched: Boolean) = repository.setSeen(EpisodeId.forMovie(mediaId), watched)
}

/** Fetches a show's season/episode structure, e.g. to compute watch-next. */
class FetchEpisodeCatalogUseCase(private val source: EpisodeCatalogSource) {
    suspend operator fun invoke(mediaId: MediaId): Result<List<Season>> = source.fetch(mediaId)
}

/**
 * The write half of [com.codingpit.muviss.feature.progress.api.ProgressApi],
 * bundled so `DefaultProgressApi`'s constructor stays inside detekt's
 * `LongParameterList` budget — the same trick collection's `CollectionToggles`
 * uses.
 */
class ProgressMutations(
    val toggleEpisodeSeen: ToggleEpisodeSeenUseCase,
    val clearProgress: ClearProgressUseCase,
    val setMovieWatched: SetMovieWatchedUseCase,
    val plays: ProgressPlayMutations,
    val bulk: ProgressBulkMutations,
)

/** The per-episode rewatch writes (ADR 0011), grouped for the same budget reason as [ProgressMutations]. */
class ProgressPlayMutations(
    val recordPlay: RecordPlayUseCase,
    val removeLatestPlay: RemoveLatestPlayUseCase,
    val clearPlays: ClearPlaysUseCase,
)

/** Every write that covers more than one episode, and their reversals, grouped likewise. */
class ProgressBulkMutations(
    val markSeasonAiredSeen: MarkSeasonAiredSeenUseCase,
    val markShowAiredSeen: MarkShowAiredSeenUseCase,
    val unmarkSeasons: UnmarkSeasonsUseCase,
    val undoBulkMark: UndoBulkMarkUseCase,
    val markPreviousSeen: MarkPreviousSeenUseCase,
    val markAllAiredSeen: MarkAllAiredSeenUseCase,
)
