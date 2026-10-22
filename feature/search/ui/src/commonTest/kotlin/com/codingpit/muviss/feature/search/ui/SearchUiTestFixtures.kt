package com.codingpit.muviss.feature.search.ui

import com.codingpit.muviss.feature.collection.api.CollectionApi
import com.codingpit.muviss.feature.collection.api.CollectionMembership
import com.codingpit.muviss.feature.collection.api.CollectionSummary
import com.codingpit.muviss.feature.collection.api.NewEpisodesResult
import com.codingpit.muviss.feature.search.domain.SearchRepository
import com.codingpit.muviss.feature.settings.api.GenreMuting
import com.codingpit.muviss.feature.settings.api.NotificationsChange
import com.codingpit.muviss.feature.settings.api.SettingsApi
import com.codingpit.muviss.feature.settings.api.ThemeMode
import com.codingpit.muviss.feature.triage.api.SkippedTitle
import com.codingpit.muviss.feature.triage.api.SnoozedTitle
import com.codingpit.muviss.feature.triage.api.TriageApi
import com.codingpit.muviss.feature.triage.api.TriageDecisionSummary
import com.codingpit.muviss.feature.triage.api.TriageVerdict
import com.codingpit.muviss.models.EpisodeDetails
import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.Genre
import com.codingpit.muviss.models.GenreKey
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.MediaType
import com.codingpit.muviss.models.MutedGenres
import com.codingpit.muviss.models.PagedResult
import com.codingpit.muviss.models.WatchProviders
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * Stand-in for the triage feature. Search reads it for three things only:
 * excluding skipped and snoozed titles from "For you", and the Detail
 * screen's two undo lines.
 */
internal class FakeTriageApi(initial: Map<MediaId, TriageVerdict> = emptyMap()) : TriageApi {
    val decisions = MutableStateFlow(initial)
    val restored = mutableListOf<MediaId>()

    override fun observeDecidedIds(): Flow<Set<MediaId>> = decisions.map { it.keys }

    override fun observeSkipped(): Flow<List<SkippedTitle>> = decisions.map { all ->
        all.filterValues { it == TriageVerdict.SKIP }.keys.map { SkippedTitle(it, it.external, null, 0L) }
    }

    override fun observeDecision(mediaId: MediaId): Flow<TriageDecisionSummary?> = decisions.map { all ->
        all[mediaId]?.let { TriageDecisionSummary(mediaId, it, decidedAtEpochMs = 0L) }
    }

    override suspend fun restore(mediaId: MediaId) {
        restored += mediaId
        decisions.value = decisions.value - mediaId
    }

    val snoozes = MutableStateFlow<Map<MediaId, Long>>(emptyMap())
    val unsnoozed = mutableListOf<MediaId>()

    override fun observeSnoozedIds(): Flow<Set<MediaId>> = snoozes.map { it.keys }

    override fun observeSnoozed(): Flow<List<SnoozedTitle>> = snoozes.map { all ->
        all.map { (id, due) -> SnoozedTitle(id, id.external, null, snoozedAtEpochMs = 0L, dueAtEpochDay = due) }
    }

    override fun observeSnooze(mediaId: MediaId): Flow<SnoozedTitle?> = snoozes.map { all ->
        all[mediaId]?.let { SnoozedTitle(mediaId, mediaId.external, null, snoozedAtEpochMs = 0L, dueAtEpochDay = it) }
    }

    override suspend fun unsnooze(mediaId: MediaId) {
        unsnoozed += mediaId
        snoozes.value = snoozes.value - mediaId
    }
}

internal class FakeRepo(
    private val searchResult: (page: Int) -> Result<PagedResult<MediaSummary>> = { Result.success(PagedResult(emptyList(), it, it)) },
    private val movieGenresResult: Result<List<Genre>> = Result.success(emptyList()),
    private val tvGenresResult: Result<List<Genre>> = Result.success(emptyList()),
    private val discoverResult: (type: MediaType, page: Int, genreId: String?) -> Result<PagedResult<MediaSummary>> =
        { _, page, _ -> Result.success(PagedResult(emptyList(), page, page)) },
    private val recommendationsResult: (id: MediaId) -> Result<PagedResult<MediaSummary>> = { Result.success(PagedResult(emptyList(), 1, 1)) },
    /** Runs before a browse answers, so a test can hold one response back while the user moves on. */
    private val discoverGate: suspend (page: Int, genreId: String?) -> Unit = { _, _ -> },
) : SearchRepository {
    override suspend fun search(query: String, page: Int) = searchResult(page)
    override suspend fun trending() = Result.success(emptyList<MediaSummary>())
    override suspend fun details(id: MediaId) = Result.success(MediaDetails(MediaSummary(id, "t")))

    /** What each browse asked the source to leave out, per type. */
    val discoverExcluded = mutableListOf<Pair<MediaType, Set<String>>>()

    override suspend fun discover(type: MediaType, page: Int, genreId: String?, excludedGenreIds: Set<String>): Result<PagedResult<MediaSummary>> {
        discoverExcluded += type to excludedGenreIds
        discoverGate(page, genreId)
        return discoverResult(type, page, genreId)
    }
    override suspend fun genres(type: MediaType) = if (type == MediaType.MOVIE) movieGenresResult else tvGenresResult
    override suspend fun watchProviders(id: MediaId) = Result.success(WatchProviders())
    override suspend fun recommendations(id: MediaId, page: Int) = recommendationsResult(id)
    override suspend fun similar(id: MediaId, page: Int) = Result.success(PagedResult(emptyList<MediaSummary>(), 1, 1))
    override suspend fun episodeDetails(episodeId: EpisodeId) = Result.success(EpisodeDetails(episodeId, "Episode", episodeId.seasonNumber, episodeId.episodeNumber))
}

/** Muting as Settings would report it, with the session chip kept the way the real api keeps it. */
internal class FakeMutingApi(configured: MutedGenres = MutedGenres.NONE, inSearch: Boolean = false) : SettingsApi {
    val configured = MutableStateFlow(configured)
    val inSearch = MutableStateFlow(inSearch)
    val showMuted = MutableStateFlow(false)

    override fun observeMuting(): Flow<GenreMuting> = combine(this.configured, showMuted, this.inSearch) { muted, show, search -> GenreMuting(muted, show, search) }

    override fun setShowMutedThisSession(show: Boolean) {
        showMuted.value = show
    }

    override suspend fun setGenreMuted(key: GenreKey, name: String, muted: Boolean) = Unit

    override fun observeThemeMode(): Flow<ThemeMode> = flowOf(ThemeMode.SYSTEM)

    override suspend fun examplesFor(key: GenreKey, limit: Int): List<MediaSummary> = emptyList()

    override fun observeNotificationsEnabled(): Flow<Boolean> = flowOf(false)

    override fun observeCrashReportsEnabled(): Flow<Boolean> = flowOf(false)
    override fun observeDeviceBackupEnabled(): Flow<Boolean> = flowOf(false)

    override suspend fun setDeviceBackupEnabled(enabled: Boolean) = Unit

    override suspend fun setNotificationsEnabled(enabled: Boolean): NotificationsChange = NotificationsChange.DISABLED
}

internal class FakeSearchCollectionApi(initialLibrary: List<CollectionSummary> = emptyList()) : CollectionApi {
    val library = MutableStateFlow(initialLibrary)

    override fun observeMembership(mediaId: MediaId): Flow<CollectionMembership?> = error("not used")
    override fun observeSummaries(): Flow<List<CollectionSummary>> = library
    override suspend fun add(details: MediaDetails) = error("not used")
    override suspend fun remove(mediaId: MediaId) = error("not used")
    override suspend fun setFavorite(mediaId: MediaId, favorite: Boolean) = error("not used")
    override suspend fun setNotificationsMuted(mediaId: MediaId, muted: Boolean) = error("not used")
    override suspend fun setRating(mediaId: MediaId, rating: Int?) = error("not used")
    override suspend fun setNote(mediaId: MediaId, note: String?) = error("not used")

    override suspend fun setRevisitWillingness(mediaId: MediaId, willing: Boolean?) = error("not used")

    override suspend fun setCoWatchPinned(mediaId: MediaId, pinned: Boolean) = error("not used")
    override suspend fun refreshAndFindNewEpisodes(): List<NewEpisodesResult> = error("not used")
}
