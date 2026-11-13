package com.codingpit.muviss.feature.nextpick.data

import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.core.common.connectivity.ConnectivityMonitor
import com.codingpit.muviss.feature.collection.api.CollectionApi
import com.codingpit.muviss.feature.collection.api.CollectionMembership
import com.codingpit.muviss.feature.collection.api.CollectionSummary
import com.codingpit.muviss.feature.collection.api.NewEpisodesResult
import com.codingpit.muviss.feature.nextpick.domain.PickEngine
import com.codingpit.muviss.feature.nextpick.domain.PickExclusions
import com.codingpit.muviss.feature.nextpick.domain.PickMuting
import com.codingpit.muviss.models.Genre
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaType
import com.codingpit.muviss.models.MutedGenres
import com.codingpit.muviss.models.WatchStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf

/** The library as a list of summaries. Only [observeSummaries] is read by the code under test. */
internal class FakeLibrary(summaries: List<CollectionSummary> = emptyList()) : CollectionApi {
    private val flow = MutableStateFlow(summaries)
    override fun observeSummaries(): Flow<List<CollectionSummary>> = flow
    override fun observeMembership(mediaId: MediaId): Flow<CollectionMembership?> = error("not used")
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

internal fun libraryTitle(
    id: String,
    title: String,
    type: MediaType = MediaType.MOVIE,
    genres: List<String>,
    rating: Int? = null,
): CollectionSummary = CollectionSummary(
    mediaId = MediaId(com.codingpit.muviss.models.SourceId.TMDB, type, id),
    title = title,
    posterUrl = null,
    status = WatchStatus.NOT_STARTED,
    genres = genres,
    rating = rating,
)

internal fun connectivity(online: Boolean) = object : ConnectivityMonitor {
    override val isOnline: Flow<Boolean> = flowOf(online)
}

internal object FixedClock : AppClock {
    override fun nowEpochMs(): Long = 0L
}

/** A live catalogue that answers nothing, so only the bundled names can map. */
internal val noLiveCatalogue: suspend (MediaType) -> List<Genre> = { emptyList() }

/** The real engine over the real library sources, with the library and muting handed in. */
internal fun libraryEngine(
    library: List<CollectionSummary>,
    muted: MutedGenres = MutedGenres.NONE,
    live: suspend (MediaType) -> List<Genre> = noLiveCatalogue,
    language: String = "en-US",
    online: Boolean = true,
): PickEngine = engineOver(FixedClock, library, muted, LibraryGenreIds(liveCatalogue = live, language = { language }), online)

/** The real engine read on [clock], so the person's zone and "today" are the test's own (#756). */
internal fun libraryEngineOn(clock: AppClock, library: List<CollectionSummary>): PickEngine = engineOver(clock, library, MutedGenres.NONE, LibraryGenreIds(liveCatalogue = noLiveCatalogue, language = { "en-US" }), online = true)

private fun engineOver(
    clock: AppClock,
    library: List<CollectionSummary>,
    muted: MutedGenres,
    genreIds: LibraryGenreIds,
    online: Boolean,
): PickEngine {
    val collection = FakeLibrary(library)
    return PickEngine(
        sources = listOf(CollectionBacklogCandidates({ collection }, genreIds, clock)),
        exclusions = PickExclusions { emptySet() },
        history = CollectionHistoryReader(clock, { collection }, connectivity(online), genreIds),
        muting = PickMuting { muted },
    )
}
