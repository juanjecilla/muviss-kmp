package com.codingpit.muviss.feature.progress.domain

import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.core.common.todayEpochDay
import com.codingpit.muviss.feature.progress.api.EpisodeOrdering
import com.codingpit.muviss.feature.progress.api.EpisodePlay
import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaType
import com.codingpit.muviss.models.Season
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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

/**
 * Runs before progress is written for a title, so the library snapshot already covers what the write proves (ADR 0037,
 * #1115, #1125). Before, not after: the snapshot is then never behind the seen count, even for the instant between two
 * writes that a Library observer can see. [aired] is what the caller knows has aired (a bulk mark's displayed seasons,
 * Detail's displayed catalog); it may be empty, and then the stored catalog alone supplies the count. [None] for
 * callers that have no library behind them.
 */
fun interface AiredBeforeWrite {
    suspend fun beforeWrite(mediaId: MediaId, aired: Collection<EpisodeId>)

    companion object {
        val None: AiredBeforeWrite = AiredBeforeWrite { _, _ -> }
    }
}

/** [AiredBeforeWrite] for the aired episodes of [seasons] as of [todayEpochDay]: what a bulk mark or a Detail tick knows. */
suspend fun AiredBeforeWrite.beforeAired(seasons: List<Season>, todayEpochDay: Long) {
    val aired = EpisodeOrdering.airedBy(seasons, todayEpochDay)
    aired.firstOrNull()?.let { beforeWrite(it.show, aired) }
}

/**
 * Writes the aired count of a title's library snapshot, never lowering it. Implemented in `:data` over the
 * `collectionEntry` table, the way the sync engine and the restore raise the same count.
 */
fun interface AiredSnapshotRaiser {
    suspend fun raise(mediaId: MediaId, airedEpisodes: Int)
}

/** Ticks one episode. A tick on a title whose snapshot is stale raises the snapshot first; see [AiredBeforeWrite]. */
class ToggleEpisodeSeenUseCase(
    private val repository: ProgressRepository,
    private val beforeWrite: AiredBeforeWrite = AiredBeforeWrite.None,
) {
    suspend operator fun invoke(episodeId: EpisodeId, seen: Boolean) {
        if (seen) beforeWrite.beforeWrite(episodeId.show, emptyList())
        repository.setSeen(episodeId, seen)
    }
}

/**
 * Raises the library snapshot's aired count to the size of the union of three sets (ADR 0037, #1115, #1125): the stored
 * catalog's aired episodes, the caller's [aired] episodes, and the title's seen episodes. The union, not the larger of
 * two counts: a seen episode the catalog does not list still counts. A title with nothing known about it at all is
 * left alone, and the read path floors the stored count at the seen count, so nothing can throw either way.
 */
class RaiseAiredBeforeWriteUseCase(
    private val repository: ProgressRepository,
    private val catalog: EpisodeCatalogStore,
    private val raiser: AiredSnapshotRaiser,
    private val clock: AppClock,
) : AiredBeforeWrite {
    override suspend fun beforeWrite(mediaId: MediaId, aired: Collection<EpisodeId>) {
        val stored = catalog.load(listOf(mediaId))[mediaId]?.let { EpisodeOrdering.airedBy(it, clock.todayEpochDay()) }.orEmpty()
        val seen = repository.observeForMedia(mediaId).first().filter { it.seen }.map { it.episodeId }
        val count = (stored.toSet() + aired + seen).size
        if (count > 0) raiser.raise(mediaId, count)
    }
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
class MarkSeasonAiredSeenUseCase(private val repository: ProgressRepository, private val beforeWrite: AiredBeforeWrite = AiredBeforeWrite.None) {
    suspend operator fun invoke(season: Season, todayEpochDay: Long): List<EpisodeId> {
        beforeWrite.beforeAired(listOf(season), todayEpochDay)
        return repository.recordPlaysForUnseen(EpisodeOrdering.airedBy(listOf(season), todayEpochDay))
    }
}

/** [MarkSeasonAiredSeenUseCase] across every season — "mark the whole show seen". */
class MarkShowAiredSeenUseCase(private val repository: ProgressRepository, private val beforeWrite: AiredBeforeWrite = AiredBeforeWrite.None) {
    suspend operator fun invoke(seasons: List<Season>, todayEpochDay: Long): List<EpisodeId> {
        beforeWrite.beforeAired(seasons, todayEpochDay)
        return repository.recordPlaysForUnseen(EpisodeOrdering.airedBy(seasons, todayEpochDay))
    }
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
class RecordPlayUseCase(private val repository: ProgressRepository, private val beforeWrite: AiredBeforeWrite = AiredBeforeWrite.None) {
    suspend operator fun invoke(episodeId: EpisodeId) {
        beforeWrite.beforeWrite(episodeId.show, emptyList())
        repository.recordPlay(episodeId)
    }
}

/** "I ticked that by mistake": drops the newest viewing only. */
class RemoveLatestPlayUseCase(private val repository: ProgressRepository) {
    suspend operator fun invoke(episodeId: EpisodeId) = repository.removeLatestPlay(episodeId)
}

/** Forgets an episode's whole watch history — episode detail's explicit "clear". */
class ClearPlaysUseCase(private val repository: ProgressRepository) {
    suspend operator fun invoke(episodeId: EpisodeId) = repository.clearPlays(episodeId)
}

/** Ticks episodes as seen, giving each one with no viewing yet a viewing stamped [watchedAtEpochMs] (#790). */
class RecordFirstPlaysUseCase(private val repository: ProgressRepository, private val beforeWrite: AiredBeforeWrite = AiredBeforeWrite.None) {
    suspend operator fun invoke(episodeIds: List<EpisodeId>, watchedAtEpochMs: Long) {
        episodeIds.map { it.show }.distinct().forEach { beforeWrite.beforeWrite(it, emptyList()) }
        repository.recordFirstPlays(episodeIds, watchedAtEpochMs)
    }
}

/** Takes back one viewing by id, and only that one (#790). */
class RemovePlayUseCase(private val repository: ProgressRepository) {
    suspend operator fun invoke(playId: String) = repository.removePlay(playId)
}

/** How many times each episode of a title has been watched. */
class ObservePlayCountsUseCase(private val repository: ProgressRepository) {
    operator fun invoke(mediaId: MediaId): Flow<Map<EpisodeId, Int>> = repository.observePlayCounts(mediaId)
}

/** One episode's viewings, newest first. */
class ObservePlaysUseCase(private val repository: ProgressRepository) {
    operator fun invoke(episodeId: EpisodeId): Flow<List<EpisodePlay>> = repository.observePlays(episodeId)
}

/** Rewatches per title since a bound (0 = all time) — the profile ranking's input. */
class ObserveRewatchCountsUseCase(private val repository: ProgressRepository) {
    operator fun invoke(sinceEpochMs: Long): Flow<Map<MediaId, Int>> = repository.observeRewatchCounts(sinceEpochMs)
}

/** When each title was last watched (its newest live Play) — what orders Watch Next. */
class ObserveLastWatchedAtUseCase(private val repository: ProgressRepository) {
    operator fun invoke(): Flow<Map<MediaId, Long>> = repository.observeLastWatchedAt()
}

/** Every rewatch's timestamp since a bound — the profile trend's input. */
class ObserveRewatchTimestampsUseCase(private val repository: ProgressRepository) {
    operator fun invoke(sinceEpochMs: Long): Flow<List<Long>> = repository.observeRewatchTimestamps(sinceEpochMs)
}

/**
 * Marks every episode at or before [target] (inclusive, across all of
 * [seasons]) as seen — the "I'm caught up through here" catch-up action — and
 * returns exactly the ids it ticked. Like [MarkSeasonAiredSeenUseCase], it
 * writes one first viewing per episode that has none, so an undo can drop
 * precisely those viewings.
 */
class MarkPreviousSeenUseCase(
    private val repository: ProgressRepository,
    private val clock: AppClock,
    private val beforeWrite: AiredBeforeWrite = AiredBeforeWrite.None,
) {
    suspend operator fun invoke(seasons: List<Season>, target: EpisodeId): List<EpisodeId> {
        beforeWrite.beforeAired(seasons, clock.todayEpochDay())
        return repository.recordPlaysForUnseen(EpisodeOrdering.upToInclusive(seasons, target))
    }
}

/**
 * Marks exactly the episodes that have aired by `todayEpochDay` as seen —
 * "I am completely up to date with this show". Triage's `CaughtUp` verdict
 * writes progress through this rather than through [MarkPreviousSeenUseCase];
 * see [EpisodeOrdering.airedBy] for why the difference matters.
 */
class MarkAllAiredSeenUseCase(private val repository: ProgressRepository, private val beforeWrite: AiredBeforeWrite = AiredBeforeWrite.None) {
    suspend operator fun invoke(seasons: List<Season>, todayEpochDay: Long) {
        beforeWrite.beforeAired(seasons, todayEpochDay)
        repository.setSeenBulk(EpisodeOrdering.airedBy(seasons, todayEpochDay), seen = true)
    }
}

/** Un-ticks everything for one title — used to take back a triage verdict. */
class ClearProgressUseCase(private val repository: ProgressRepository) {
    suspend operator fun invoke(mediaId: MediaId) = repository.clearForMedia(mediaId)
}

/**
 * Toggles a movie's single watched tick (see [EpisodeId.forMovie]).
 *
 * A TV id is a programmer error, not a user error: [EpisodeId.forMovie] would write a synthetic 0/0 episode
 * for the show, which can push `seenEpisodes` past `airedEpisodes` and make `WatchProgress` throw later.
 * So the id is rejected before anything is written. Callers report the exception (`launchReporting`).
 */
class SetMovieWatchedUseCase(private val repository: ProgressRepository) {
    suspend operator fun invoke(mediaId: MediaId, watched: Boolean) {
        require(mediaId.type == MediaType.MOVIE) { "SetMovieWatchedUseCase needs a movie id, got $mediaId" }
        repository.setSeen(EpisodeId.forMovie(mediaId), watched)
    }
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
    val recordFirstPlays: RecordFirstPlaysUseCase,
    val removePlay: RemovePlayUseCase,
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
