package com.codingpit.muviss.feature.triage.domain

import com.codingpit.muviss.feature.collection.api.CollectionApi
import com.codingpit.muviss.feature.collection.api.CollectionMembership
import com.codingpit.muviss.feature.collection.api.CollectionSummary
import com.codingpit.muviss.feature.collection.api.NewEpisodesResult
import com.codingpit.muviss.feature.progress.api.EpisodeOrdering
import com.codingpit.muviss.feature.progress.api.EpisodePlay
import com.codingpit.muviss.feature.progress.api.ProgressApi
import com.codingpit.muviss.feature.progress.api.WatchNextItem
import com.codingpit.muviss.feature.triage.api.TriageVerdict
import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.Season
import com.codingpit.muviss.models.WatchStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

internal class FakeTriageDecisionRepository : TriageDecisionRepository {
    val decisions = MutableStateFlow<Map<MediaId, TriageDecision>>(emptyMap())
    val restored = mutableListOf<MediaId>()

    override fun observeByVerdict(verdict: TriageVerdict): Flow<List<TriageDecision>> = decisions.map { all ->
        all.values.filter { it.verdict == verdict }.sortedByDescending { it.decidedAtEpochMs }
    }

    override fun observeDecidedIds(): Flow<Set<MediaId>> = decisions.map { it.keys }

    override fun observeDecision(mediaId: MediaId): Flow<TriageDecision?> = decisions.map { it[mediaId] }

    /** Thrown by the next write of a claim (a decision with [TriageDecision.createdEntry]), once. Simulates #1071's failed claim write. */
    var failClaimWrite: Throwable? = null

    /**
     * Thrown by the claim write of an attempt whose add found the entry already there (a write with `createdEntry`
     * false), once. The verdict's own first write is also `createdEntry` false, so this hook skips that one and fails
     * the second write. Simulates a claim write that fails when the title was already in the library (#1071).
     */
    var failUnclaimedClaimWrite: Throwable? = null
    private var writes = 0

    /** Runs before a claim write, so a test can suspend the write and cancel the verdict while it is in flight (#1079). */
    var beforeClaimWrite: (suspend () -> Unit)? = null

    override suspend fun record(decision: TriageDecision) {
        writes++
        if (decision.createdEntry) {
            beforeClaimWrite?.invoke()
            failClaimWrite?.let {
                failClaimWrite = null
                throw it
            }
        } else if (writes == 2) {
            failUnclaimedClaimWrite?.let {
                failUnclaimedClaimWrite = null
                throw it
            }
        }
        decisions.value = decisions.value + (decision.mediaId to decision)
    }

    override suspend fun markResolved(mediaId: MediaId, resolved: Boolean) {
        decisions.value[mediaId]?.let { decisions.value = decisions.value + (mediaId to it.copy(resolved = resolved)) }
    }

    override suspend fun restore(mediaId: MediaId) {
        restored += mediaId
        decisions.value = decisions.value - mediaId
    }

    override suspend fun unresolved(): List<TriageDecision> = decisions.value.values.filterNot { it.resolved }
}

internal class FakeCollectionApi(initial: List<CollectionSummary> = emptyList()) : CollectionApi {
    val summaries = MutableStateFlow(initial)
    val added = mutableListOf<MediaDetails>()
    val removed = mutableListOf<MediaId>()

    /** Each add that makes a new entry stamps it with the next value, as the real repository does (#902). */
    private var nextAddedAt = 1_000L

    override fun observeMembership(mediaId: MediaId): Flow<CollectionMembership?> = summaries.map { all ->
        all.firstOrNull { it.mediaId == mediaId }?.let { CollectionMembership(it.mediaId, it.favorite, it.notificationsMuted, it.rating, null) }
    }

    override fun observeSummaries(): Flow<List<CollectionSummary>> = summaries

    /**
     * A re-add of a title that is not in the library gets a new stamp; adding a present title only refreshes it.
     * That mirrors `SqlDelightCollectionRepository`, where a removed title's re-add is stamped anew (#902).
     */
    override suspend fun add(details: MediaDetails) {
        added += details
        if (summaries.value.none { it.mediaId == details.id }) {
            summaries.value = summaries.value + CollectionSummary(
                mediaId = details.id,
                title = details.summary.title,
                posterUrl = details.summary.posterUrl,
                status = WatchStatus.NOT_STARTED,
                addedAtEpochMs = nextAddedAt++,
            )
        }
    }

    /** Thrown by [remove] before it records anything, to simulate a failed cleanup. */
    var failRemove: Throwable? = null

    override suspend fun remove(mediaId: MediaId) {
        failRemove?.let { throw it }
        removed += mediaId
        summaries.value = summaries.value.filterNot { it.mediaId == mediaId }
    }

    override suspend fun setFavorite(mediaId: MediaId, favorite: Boolean) = Unit

    override suspend fun setNotificationsMuted(mediaId: MediaId, muted: Boolean) = Unit

    override suspend fun setRating(mediaId: MediaId, rating: Int?) = Unit

    override suspend fun setNote(mediaId: MediaId, note: String?) = Unit

    override suspend fun setRevisitWillingness(mediaId: MediaId, willing: Boolean?) = error("not used")

    override suspend fun setCoWatchPinned(mediaId: MediaId, pinned: Boolean) = error("not used")

    override suspend fun refreshAndFindNewEpisodes(): List<NewEpisodesResult> = emptyList()
}

internal class FakeProgressApi : ProgressApi {
    val ticked = mutableListOf<EpisodeId>()
    val markedAllAired = mutableListOf<Pair<List<Season>, Long>>()
    val moviesWatched = mutableListOf<MediaId>()
    val cleared = mutableListOf<MediaId>()

    /** The seen set, kept so a test can tell which ticks were already there when a verdict ran (#790). */
    val seen = MutableStateFlow<Set<EpisodeId>>(emptySet())

    /** Seeds ticks the person made before the verdict, as Detail's checkmarks would. */
    fun seedSeen(vararg episodes: EpisodeId) {
        seen.value = seen.value + episodes
    }

    /** The live viewings, by [EpisodePlay.idOf] id, so a test can see which viewing undo took and which it kept. */
    val plays = MutableStateFlow<Map<String, EpisodeId>>(emptyMap())

    /** The person's own viewing of an episode at [watchedAtEpochMs], as a rewatch or a tick from Detail. */
    fun recordRewatch(episodeId: EpisodeId, watchedAtEpochMs: Long) {
        plays.value = plays.value + (EpisodePlay.idOf(episodeId, watchedAtEpochMs) to episodeId)
        seen.value = seen.value + episodeId
    }

    override fun observeSeenEpisodes(mediaId: MediaId): Flow<Set<EpisodeId>> = seen.map { all -> all.filterTo(mutableSetOf()) { it.show == mediaId } }

    // Watch-next (EPIC 22) — this fake's subject never asks for it.
    override fun observeWatchNext(): Flow<List<WatchNextItem>> = flowOf(emptyList())
    override suspend fun refreshWatchNextCatalogs() = Unit

    override fun observeSeenActivityEpochDays(): Flow<Set<Long>> = MutableStateFlow(emptySet())

    override suspend fun setEpisodeSeen(episodeId: EpisodeId, seen: Boolean) {
        ticked += episodeId
        this.seen.value = if (seen) this.seen.value + episodeId else this.seen.value - episodeId
    }

    // Derived from the live viewings, as the repository derives them: a viewing's time is the suffix of its id.
    override fun observePlayCounts(mediaId: MediaId): Flow<Map<EpisodeId, Int>> = plays.map { all ->
        all.values.filter { it.show == mediaId }.groupingBy { it }.eachCount()
    }

    override fun observePlays(episodeId: EpisodeId): Flow<List<EpisodePlay>> = plays.map { all ->
        all.filter { it.value == episodeId }.keys.map { EpisodePlay(episodeId, it.substringAfterLast('@').toLong()) }
    }

    override fun observeRewatchCounts(sinceEpochMs: Long): Flow<Map<MediaId, Int>> = flowOf(emptyMap())

    override fun observeRewatchTimestamps(sinceEpochMs: Long): Flow<List<Long>> = flowOf(emptyList())
    override suspend fun recordPlay(episodeId: EpisodeId) = Unit
    override suspend fun removeLatestPlay(episodeId: EpisodeId) = Unit
    override suspend fun clearPlays(episodeId: EpisodeId) = Unit
    override suspend fun recordFirstPlays(episodeIds: List<EpisodeId>, watchedAtEpochMs: Long) {
        ticked += episodeIds
        episodeIds.forEach { episodeId ->
            if (plays.value.values.none { it == episodeId }) {
                plays.value = plays.value + (EpisodePlay.idOf(episodeId, watchedAtEpochMs) to episodeId)
            }
        }
        seen.value = seen.value + episodeIds
    }

    /** As the real repository does: only this viewing goes, and the episode is unseen only when no viewing of it is left. */
    override suspend fun removePlay(playId: String) {
        val episodeId = plays.value[playId] ?: return
        plays.value = plays.value - playId
        if (plays.value.values.none { it == episodeId }) seen.value = seen.value - episodeId
    }

    override suspend fun markSeasonAiredSeen(season: Season, todayEpochDay: Long): List<EpisodeId> = emptyList()
    override suspend fun markShowAiredSeen(seasons: List<Season>, todayEpochDay: Long): List<EpisodeId> = emptyList()
    override suspend fun unmarkSeason(season: Season) = Unit
    override suspend fun unmarkShow(seasons: List<Season>) = Unit
    override suspend fun undoBulkMark(episodeIds: List<EpisodeId>) = Unit

    override suspend fun markPreviousSeen(seasons: List<Season>, target: EpisodeId) = error("triage uses markAllAiredSeen — see EpisodeOrdering.airedBy")

    override suspend fun markAllAiredSeen(seasons: List<Season>, todayEpochDay: Long) {
        markedAllAired += seasons to todayEpochDay
        seen.value = seen.value + EpisodeOrdering.airedBy(seasons, todayEpochDay)
    }

    override suspend fun clearProgress(mediaId: MediaId) {
        cleared += mediaId
        seen.value = seen.value.filterNotTo(mutableSetOf()) { it.show == mediaId }
    }

    override suspend fun setMovieWatched(mediaId: MediaId, watched: Boolean) {
        moviesWatched += mediaId
        val movie = EpisodeId.forMovie(mediaId)
        seen.value = if (watched) seen.value + movie else seen.value - movie
    }
}

internal class FakeTriageDetailsSource(
    private val byId: Map<MediaId, MediaDetails> = emptyMap(),
    private val failure: Throwable? = null,
) : TriageDetailsSource {
    var fetchCalls = 0
        private set

    override suspend fun fetch(mediaId: MediaId): Result<MediaDetails> {
        fetchCalls++
        failure?.let { return Result.failure(it) }
        return byId[mediaId]?.let { Result.success(it) }
            ?: Result.failure(IllegalStateException("no details for $mediaId"))
    }
}

/** An in-memory snooze log (EPIC 42, ADR 0023). */
internal class FakeTriageSnoozeRepository : TriageSnoozeRepository {
    val snoozes = MutableStateFlow<Map<MediaId, TriageSnooze>>(emptyMap())

    /** Every id [unsnooze] was called for, so a test can prove a soft delete happened. */
    val unsnoozed = mutableListOf<MediaId>()

    override fun observeAll(): Flow<List<TriageSnooze>> = snoozes.map { all -> all.values.sortedBy { it.dueAtEpochDay } }

    override fun observeSnoozedIds(): Flow<Set<MediaId>> = snoozes.map { it.keys }

    override fun observeSnooze(mediaId: MediaId): Flow<TriageSnooze?> = snoozes.map { it[mediaId] }

    override suspend fun snooze(snooze: TriageSnooze) {
        snoozes.value = snoozes.value + (snooze.mediaId to snooze)
    }

    override suspend fun unsnooze(mediaId: MediaId) {
        unsnoozed += mediaId
        snoozes.value = snoozes.value - mediaId
    }

    override suspend fun due(today: Long): List<TriageSnooze> = snoozes.value.values
        .filter { it.isDueBy(today) }
        .sortedBy { it.dueAtEpochDay }
}
