package com.codingpit.muviss.feature.triage.ui

import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.core.common.analytics.AnalyticsEvent
import com.codingpit.muviss.core.common.analytics.AnalyticsTracker
import com.codingpit.muviss.core.common.flags.FeatureFlags
import com.codingpit.muviss.core.common.flags.SnoozePeriod
import com.codingpit.muviss.core.common.flags.SnoozePlacement
import com.codingpit.muviss.core.common.flags.TriageControlScheme
import com.codingpit.muviss.feature.collection.api.CollectionApi
import com.codingpit.muviss.feature.collection.api.CollectionMembership
import com.codingpit.muviss.feature.collection.api.CollectionSummary
import com.codingpit.muviss.feature.collection.api.NewEpisodesResult
import com.codingpit.muviss.feature.progress.api.EpisodePlay
import com.codingpit.muviss.feature.progress.api.ProgressApi
import com.codingpit.muviss.feature.progress.api.WatchNextItem
import com.codingpit.muviss.feature.triage.api.TriageVerdict
import com.codingpit.muviss.feature.triage.domain.DeckLoader
import com.codingpit.muviss.feature.triage.domain.DeckSource
import com.codingpit.muviss.feature.triage.domain.LoadDeckGenresUseCase
import com.codingpit.muviss.feature.triage.domain.LoadDeckUseCase
import com.codingpit.muviss.feature.triage.domain.ObserveSnoozeHintSeenUseCase
import com.codingpit.muviss.feature.triage.domain.ObserveTutorialSeenUseCase
import com.codingpit.muviss.feature.triage.domain.RecordDecisionUseCase
import com.codingpit.muviss.feature.triage.domain.RetryUnresolvedUseCase
import com.codingpit.muviss.feature.triage.domain.SetSnoozeHintSeenUseCase
import com.codingpit.muviss.feature.triage.domain.SetTutorialSeenUseCase
import com.codingpit.muviss.feature.triage.domain.SnoozeActions
import com.codingpit.muviss.feature.triage.domain.SnoozeUseCase
import com.codingpit.muviss.feature.triage.domain.TriageActions
import com.codingpit.muviss.feature.triage.domain.TriageDecision
import com.codingpit.muviss.feature.triage.domain.TriageDecisionRepository
import com.codingpit.muviss.feature.triage.domain.TriageDetailsSource
import com.codingpit.muviss.feature.triage.domain.TriageOnboarding
import com.codingpit.muviss.feature.triage.domain.TriagePreferences
import com.codingpit.muviss.feature.triage.domain.TriageSnooze
import com.codingpit.muviss.feature.triage.domain.TriageSnoozeRepository
import com.codingpit.muviss.feature.triage.domain.UndoDecisionUseCase
import com.codingpit.muviss.feature.triage.domain.UnsnoozeUseCase
import com.codingpit.muviss.models.Episode
import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.Genre
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.MediaType
import com.codingpit.muviss.models.PagedResult
import com.codingpit.muviss.models.ProductionStatus
import com.codingpit.muviss.models.Season
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

internal const val TODAY_EPOCH_DAY = 20_000L
internal const val NOW_EPOCH_MS = TODAY_EPOCH_DAY * 86_400_000L

internal fun movie(id: String) = MediaSummary(MediaId.tmdbMovie(id), "Movie $id")

internal fun show(id: String) = MediaSummary(MediaId.tmdbTv(id), "Show $id")

internal fun detailsFor(summary: MediaSummary): MediaDetails = when (summary.type) {
    MediaType.MOVIE -> MediaDetails(summary, productionStatus = ProductionStatus.RELEASED)

    MediaType.TV -> MediaDetails(
        summary = summary,
        productionStatus = ProductionStatus.RETURNING,
        seasons = listOf(
            Season(
                1,
                "Season 1",
                listOf(Episode(EpisodeId(summary.id, 1, 1), 1, 1, "Pilot", airDateEpochDay = TODAY_EPOCH_DAY - 10)),
            ),
        ),
    )
}

internal class FakeClock(private val millis: Long = NOW_EPOCH_MS) : AppClock {
    override fun nowEpochMs(): Long = millis
}

internal class RecordingAnalytics : AnalyticsTracker {
    val events = mutableListOf<AnalyticsEvent>()

    override fun track(event: AnalyticsEvent) {
        events += event
    }
}

internal class FakeFeatureFlags(
    scheme: TriageControlScheme = TriageControlScheme.FOUR_WAY,
    animations: Boolean = true,
    deckAnimations: Boolean = true,
    snoozePeriodInitial: SnoozePeriod = SnoozePeriod.DEFAULT,
    snoozePlacementInitial: SnoozePlacement = SnoozePlacement.DEFAULT,
) : FeatureFlags {
    private val state = MutableStateFlow(scheme)

    /** Settable directly, so a test can pick a starting point without a suspend context. */
    val appAnimations = MutableStateFlow(animations)
    val deck = MutableStateFlow(deckAnimations)

    override val triageControlScheme: Flow<TriageControlScheme> = state
    override val animationsEnabled: Flow<Boolean> = appAnimations
    override val triageDeckAnimations: Flow<Boolean> = deck
    private val autoSync = MutableStateFlow(false)
    override val syncAutomatically: Flow<Boolean> = autoSync

    /** Settable directly, like the motion flags, so a deck test can pick a period without suspending. */
    val snoozePeriod = MutableStateFlow(snoozePeriodInitial)
    val snoozePlacement = MutableStateFlow(snoozePlacementInitial)
    override val triageSnoozePeriod: Flow<SnoozePeriod> = snoozePeriod
    override val triageSnoozePlacement: Flow<SnoozePlacement> = snoozePlacement

    override suspend fun setTriageSnoozePeriod(period: SnoozePeriod) {
        snoozePeriod.value = period
    }

    override suspend fun setTriageSnoozePlacement(placement: SnoozePlacement) {
        snoozePlacement.value = placement
    }

    override suspend fun setSyncAutomatically(enabled: Boolean) {
        autoSync.value = enabled
    }

    override suspend fun setTriageControlScheme(scheme: TriageControlScheme) {
        state.value = scheme
    }

    override suspend fun setAnimationsEnabled(enabled: Boolean) {
        appAnimations.value = enabled
    }

    override suspend fun setTriageDeckAnimations(enabled: Boolean) {
        deck.value = enabled
    }
}

internal class FakeTriagePreferences(seen: Boolean = true, snoozeHintSeen: Boolean = true) : TriagePreferences {
    val tutorialSeen = MutableStateFlow(seen)
    val snoozeHint = MutableStateFlow(snoozeHintSeen)

    override fun observeTutorialSeen(): Flow<Boolean> = tutorialSeen

    override suspend fun setTutorialSeen(seen: Boolean) {
        tutorialSeen.value = seen
    }

    override fun observeSnoozeHintSeen(): Flow<Boolean> = snoozeHint

    override suspend fun setSnoozeHintSeen(seen: Boolean) {
        snoozeHint.value = seen
    }
}

/** An in-memory snooze log, mirroring [FakeTriageDecisionRepository]'s shape. */
internal class FakeTriageSnoozeRepository : TriageSnoozeRepository {
    val snoozes = MutableStateFlow<Map<MediaId, TriageSnooze>>(emptyMap())

    override fun observeAll(): Flow<List<TriageSnooze>> = snoozes.map { all -> all.values.sortedBy { it.dueAtEpochDay } }

    override fun observeSnoozedIds(): Flow<Set<MediaId>> = snoozes.map { it.keys }

    override fun observeSnooze(mediaId: MediaId): Flow<TriageSnooze?> = snoozes.map { it[mediaId] }

    override suspend fun snooze(snooze: TriageSnooze) {
        snoozes.value = snoozes.value + (snooze.mediaId to snooze)
    }

    override suspend fun unsnooze(mediaId: MediaId) {
        snoozes.value = snoozes.value - mediaId
    }

    override suspend fun due(today: Long): List<TriageSnooze> = snoozes.value.values
        .filter { it.isDueBy(today) }
        .sortedBy { it.dueAtEpochDay }
}

internal class FakeTriageDecisionRepository : TriageDecisionRepository {
    val decisions = MutableStateFlow<Map<MediaId, TriageDecision>>(emptyMap())

    override fun observeByVerdict(verdict: TriageVerdict): Flow<List<TriageDecision>> = decisions.map { all -> all.values.filter { it.verdict == verdict } }

    override fun observeDecidedIds(): Flow<Set<MediaId>> = decisions.map { it.keys }

    override fun observeDecision(mediaId: MediaId): Flow<TriageDecision?> = decisions.map { it[mediaId] }

    override suspend fun record(decision: TriageDecision) {
        decisions.value = decisions.value + (decision.mediaId to decision)
    }

    override suspend fun markResolved(mediaId: MediaId, resolved: Boolean) {
        decisions.value[mediaId]?.let { decisions.value = decisions.value + (mediaId to it.copy(resolved = resolved)) }
    }

    override suspend fun restore(mediaId: MediaId) {
        decisions.value = decisions.value - mediaId
    }

    override suspend fun unresolved(): List<TriageDecision> = decisions.value.values.filterNot { it.resolved }
}

internal class FakeCollectionApi : CollectionApi {
    val summaries = MutableStateFlow<List<CollectionSummary>>(emptyList())
    val added = mutableListOf<MediaId>()
    val removed = mutableListOf<MediaId>()

    override fun observeMembership(mediaId: MediaId): Flow<CollectionMembership?> = MutableStateFlow(null)

    override fun observeSummaries(): Flow<List<CollectionSummary>> = summaries

    override suspend fun add(details: MediaDetails) {
        added += details.id
    }

    override suspend fun remove(mediaId: MediaId) {
        removed += mediaId
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
    val cleared = mutableListOf<MediaId>()
    val markedAllAired = mutableListOf<Long>()
    val moviesWatched = mutableListOf<MediaId>()

    override fun observeSeenEpisodes(mediaId: MediaId): Flow<Set<EpisodeId>> = MutableStateFlow(emptySet())

    // Watch-next (EPIC 22) — this fake's subject never asks for it.
    override fun observeWatchNext(): Flow<List<WatchNextItem>> = flowOf(emptyList())
    override suspend fun refreshWatchNextCatalogs() = Unit

    override fun observeSeenActivityEpochDays(): Flow<Set<Long>> = MutableStateFlow(emptySet())

    override suspend fun setEpisodeSeen(episodeId: EpisodeId, seen: Boolean) {
        ticked += episodeId
    }

    override fun observePlayCounts(mediaId: MediaId): Flow<Map<EpisodeId, Int>> = flowOf(emptyMap())
    override fun observePlays(episodeId: EpisodeId): Flow<List<EpisodePlay>> = flowOf(emptyList())

    override fun observeRewatchCounts(sinceEpochMs: Long): Flow<Map<MediaId, Int>> = flowOf(emptyMap())

    override fun observeRewatchTimestamps(sinceEpochMs: Long): Flow<List<Long>> = flowOf(emptyList())
    override suspend fun recordPlay(episodeId: EpisodeId) = Unit
    override suspend fun removeLatestPlay(episodeId: EpisodeId) = Unit
    override suspend fun clearPlays(episodeId: EpisodeId) = Unit
    override suspend fun markSeasonAiredSeen(season: Season, todayEpochDay: Long): List<EpisodeId> = emptyList()
    override suspend fun markShowAiredSeen(seasons: List<Season>, todayEpochDay: Long): List<EpisodeId> = emptyList()
    override suspend fun unmarkSeason(season: Season) = Unit
    override suspend fun unmarkShow(seasons: List<Season>) = Unit
    override suspend fun undoBulkMark(episodeIds: List<EpisodeId>) = Unit
    override suspend fun markPreviousSeen(seasons: List<Season>, target: EpisodeId) = Unit

    override suspend fun markAllAiredSeen(seasons: List<Season>, todayEpochDay: Long) {
        markedAllAired += todayEpochDay
    }

    override suspend fun clearProgress(mediaId: MediaId) {
        cleared += mediaId
    }

    override suspend fun setMovieWatched(mediaId: MediaId, watched: Boolean) {
        moviesWatched += mediaId
    }
}

/**
 * Details source that can be held open, so a test can observe the deck while a
 * verdict's side effects are still in flight — the optimistic-commit promise.
 */
internal class GatedDetailsSource(private val summaries: List<MediaSummary>) : TriageDetailsSource {
    var gate: CompletableDeferred<Unit>? = null
    var failure: Throwable? = null

    override suspend fun fetch(mediaId: MediaId): Result<MediaDetails> {
        gate?.await()
        failure?.let { return Result.failure(it) }
        val summary = summaries.firstOrNull { it.id == mediaId } ?: return Result.failure(IllegalStateException("unknown $mediaId"))
        return Result.success(detailsFor(summary))
    }
}

internal class FakeDeckSource(
    private val movies: List<MediaSummary> = emptyList(),
    private val tv: List<MediaSummary> = emptyList(),
    private val genres: List<Genre> = emptyList(),
) : DeckSource {
    var failure: Throwable? = null

    override suspend fun page(type: MediaType, page: Int, genreId: String?): Result<PagedResult<MediaSummary>> {
        failure?.let { return Result.failure(it) }
        val items = if (type == MediaType.MOVIE) movies else tv
        return Result.success(PagedResult(if (page == 1) items else emptyList(), page = page, totalPages = 1))
    }

    override suspend fun genres(type: MediaType): Result<List<Genre>> = Result.success(genres)
}

/** Everything a [TriageViewModel] needs, wired from real domain types over the fakes above. */
internal class TriageHarness(
    movies: List<MediaSummary> = emptyList(),
    tv: List<MediaSummary> = emptyList(),
    genres: List<Genre> = emptyList(),
    scheme: TriageControlScheme = TriageControlScheme.FOUR_WAY,
    tutorialSeen: Boolean = true,
    deckAnimations: Boolean = true,
) {
    val source = FakeDeckSource(movies, tv, genres)
    val details = GatedDetailsSource(movies + tv)
    val repository = FakeTriageDecisionRepository()
    val collection = FakeCollectionApi()
    val progress = FakeProgressApi()
    val analytics = RecordingAnalytics()
    val preferences = FakeTriagePreferences(tutorialSeen)
    val flags = FakeFeatureFlags(scheme, deckAnimations = deckAnimations)
    val snoozes = FakeTriageSnoozeRepository()
    private val clock = FakeClock()

    private val record = RecordDecisionUseCase(repository, collection, progress, details, clock)

    fun viewModel() = TriageViewModel(
        LoadDeckUseCase(DeckLoader(source), repository, snoozes, collection, clock),
        LoadDeckGenresUseCase(source),
        TriageActions(
            record = record,
            undo = UndoDecisionUseCase(repository, collection, progress),
            setTutorialSeen = SetTutorialSeenUseCase(preferences),
            retryUnresolved = RetryUnresolvedUseCase(repository, record),
            snoozing = SnoozeActions(
                snooze = SnoozeUseCase(snoozes, clock),
                unsnooze = UnsnoozeUseCase(snoozes),
                setSnoozeHintSeen = SetSnoozeHintSeenUseCase(preferences),
            ),
        ),
        TriageOnboarding(ObserveTutorialSeenUseCase(preferences), ObserveSnoozeHintSeenUseCase(preferences)),
        flags,
        analytics,
    )
}
