package com.codingpit.muviss.feature.triage.ui

import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.core.common.analytics.AnalyticsEvent
import com.codingpit.muviss.core.common.analytics.AnalyticsTracker
import com.codingpit.muviss.core.common.flags.FeatureFlags
import com.codingpit.muviss.core.common.flags.TriageControlScheme
import com.codingpit.muviss.feature.collection.api.CollectionApi
import com.codingpit.muviss.feature.collection.api.CollectionMembership
import com.codingpit.muviss.feature.collection.api.CollectionSummary
import com.codingpit.muviss.feature.collection.api.NewEpisodesResult
import com.codingpit.muviss.feature.progress.api.ProgressApi
import com.codingpit.muviss.feature.triage.api.TriageVerdict
import com.codingpit.muviss.feature.triage.domain.DeckLoader
import com.codingpit.muviss.feature.triage.domain.DeckSource
import com.codingpit.muviss.feature.triage.domain.LoadDeckGenresUseCase
import com.codingpit.muviss.feature.triage.domain.LoadDeckUseCase
import com.codingpit.muviss.feature.triage.domain.ObserveTutorialSeenUseCase
import com.codingpit.muviss.feature.triage.domain.RecordDecisionUseCase
import com.codingpit.muviss.feature.triage.domain.RetryUnresolvedUseCase
import com.codingpit.muviss.feature.triage.domain.SetTutorialSeenUseCase
import com.codingpit.muviss.feature.triage.domain.TriageActions
import com.codingpit.muviss.feature.triage.domain.TriageDecision
import com.codingpit.muviss.feature.triage.domain.TriageDecisionRepository
import com.codingpit.muviss.feature.triage.domain.TriageDetailsSource
import com.codingpit.muviss.feature.triage.domain.TriagePreferences
import com.codingpit.muviss.feature.triage.domain.UndoDecisionUseCase
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
) : FeatureFlags {
    private val state = MutableStateFlow(scheme)

    /** Settable directly, so a test can pick a starting point without a suspend context. */
    val appAnimations = MutableStateFlow(animations)
    val deck = MutableStateFlow(deckAnimations)

    override val triageControlScheme: Flow<TriageControlScheme> = state
    override val animationsEnabled: Flow<Boolean> = appAnimations
    override val triageDeckAnimations: Flow<Boolean> = deck

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

internal class FakeTriagePreferences(seen: Boolean = true) : TriagePreferences {
    val tutorialSeen = MutableStateFlow(seen)

    override fun observeTutorialSeen(): Flow<Boolean> = tutorialSeen

    override suspend fun setTutorialSeen(seen: Boolean) {
        tutorialSeen.value = seen
    }
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
    override suspend fun refreshAndFindNewEpisodes(): List<NewEpisodesResult> = emptyList()
}

internal class FakeProgressApi : ProgressApi {
    val ticked = mutableListOf<EpisodeId>()
    val cleared = mutableListOf<MediaId>()
    val markedAllAired = mutableListOf<Long>()
    val moviesWatched = mutableListOf<MediaId>()

    override fun observeSeenEpisodes(mediaId: MediaId): Flow<Set<EpisodeId>> = MutableStateFlow(emptySet())
    override fun observeSeenActivityEpochDays(): Flow<Set<Long>> = MutableStateFlow(emptySet())

    override suspend fun setEpisodeSeen(episodeId: EpisodeId, seen: Boolean) {
        ticked += episodeId
    }

    override suspend fun markSeasonSeen(season: Season) = Unit
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

    private val record = RecordDecisionUseCase(repository, collection, progress, details, FakeClock())

    fun viewModel() = TriageViewModel(
        LoadDeckUseCase(DeckLoader(source), repository, collection),
        LoadDeckGenresUseCase(source),
        TriageActions(
            record = record,
            undo = UndoDecisionUseCase(repository, collection, progress),
            setTutorialSeen = SetTutorialSeenUseCase(preferences),
            retryUnresolved = RetryUnresolvedUseCase(repository, record),
        ),
        ObserveTutorialSeenUseCase(preferences),
        flags,
        analytics,
    )
}
