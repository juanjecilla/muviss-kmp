@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.core.sync

import com.codingpit.muviss.core.common.widget.NoOpWidgetRefresher
import com.codingpit.muviss.core.testing.FakeSupabaseServer
import com.codingpit.muviss.feature.collection.data.SqlDelightCollectionRepository
import com.codingpit.muviss.feature.progress.api.EpisodePlay
import com.codingpit.muviss.feature.progress.api.ProgressApi
import com.codingpit.muviss.feature.progress.api.WatchNextItem
import com.codingpit.muviss.feature.progress.data.SqlDelightProgressRepository
import com.codingpit.muviss.models.Episode
import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.ProductionStatus
import com.codingpit.muviss.models.Season
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/** "Today" for every [AppDevice] clock: epoch day 150. Episodes dated before it have aired. */
internal const val TODAY_EPOCH_DAY = 150L
private const val MILLIS_PER_DAY = 86_400_000L

/**
 * A [TestDevice] with the app's real write paths on top: the same
 * [SqlDelightProgressRepository] and [SqlDelightCollectionRepository] the app
 * runs, over the device's own database. What a user does on a device goes
 * through these, so a test that drives them exercises the timestamps, dirty
 * flags and transactions the app actually produces, rather than rows a test
 * wrote by hand.
 */
internal class AppDevice(server: FakeSupabaseServer, userId: String = "alice") {
    val device = TestDevice(server, userId, startMillis = TODAY_EPOCH_DAY * MILLIS_PER_DAY)
    private val dispatchers = ImmediateDispatchers(UnconfinedTestDispatcher())
    val progress = SqlDelightProgressRepository(device.database.episodeProgressQueries, device.database.episodePlayQueries, dispatchers, device.clock, NoOpWidgetRefresher)
    val progressApi: ProgressApi = SeenOnlyProgressApi(progress)
    val collection = SqlDelightCollectionRepository(device.database.collectionEntryQueries, dispatchers, device.clock, progressApi)

    /** Advances this device's clock, and so the stamp on whatever it writes next. */
    fun at(millisAfterStart: Long) = device.clock.advanceTo(TODAY_EPOCH_DAY * MILLIS_PER_DAY + millisAfterStart)

    suspend fun sync(): SyncOutcome = device.sync()
}

internal val SHOW: MediaId = MediaId.tmdbTv("1399")

internal fun episodeIds(count: Int, show: MediaId = SHOW): List<EpisodeId> = (1..count).map { EpisodeId(show, 1, it) }

/** A show whose first [aired] episodes aired before [TODAY_EPOCH_DAY], and whose next [unaired] have not. */
internal fun showDetails(aired: Int, unaired: Int = 0, show: MediaId = SHOW, status: ProductionStatus = ProductionStatus.RETURNING, title: String = "Show"): MediaDetails {
    val episodes = (1..aired + unaired).map { number ->
        Episode(
            id = EpisodeId(show, 1, number),
            seasonNumber = 1,
            episodeNumber = number,
            name = "Episode $number",
            airDateEpochDay = if (number <= aired) TODAY_EPOCH_DAY - 10 else TODAY_EPOCH_DAY + 100,
        )
    }
    return MediaDetails(MediaSummary(show, title), productionStatus = status, seasons = listOf(Season(1, "Season 1", episodes)))
}

/**
 * The slice of [ProgressApi] the collection repository reads (`observeSeenEpisodes`),
 * over the real progress repository; everything else is unused here.
 */
private class SeenOnlyProgressApi(private val repository: SqlDelightProgressRepository) : ProgressApi {
    override fun observeSeenEpisodes(mediaId: MediaId): Flow<Set<EpisodeId>> = repository.observeForMedia(mediaId)
        .map { rows -> rows.filter { it.seen }.map { it.episodeId }.toSet() }

    override fun observeWatchNext(): Flow<List<WatchNextItem>> = flowOf(emptyList())
    override suspend fun refreshWatchNextCatalogs() = Unit
    override fun observeSeenActivityEpochDays(): Flow<Set<Long>> = repository.observeSeenActivityEpochDays()
    override fun observePlayCounts(mediaId: MediaId): Flow<Map<EpisodeId, Int>> = repository.observePlayCounts(mediaId)
    override fun observePlays(episodeId: EpisodeId): Flow<List<EpisodePlay>> = repository.observePlays(episodeId)
    override fun observeRewatchCounts(sinceEpochMs: Long): Flow<Map<MediaId, Int>> = repository.observeRewatchCounts(sinceEpochMs)
    override fun observeRewatchTimestamps(sinceEpochMs: Long): Flow<List<Long>> = repository.observeRewatchTimestamps(sinceEpochMs)
    override suspend fun setEpisodeSeen(episodeId: EpisodeId, seen: Boolean) = repository.setSeen(episodeId, seen)
    override suspend fun recordPlay(episodeId: EpisodeId) = repository.recordPlay(episodeId)
    override suspend fun removeLatestPlay(episodeId: EpisodeId) = repository.removeLatestPlay(episodeId)
    override suspend fun clearPlays(episodeId: EpisodeId) = repository.clearPlays(episodeId)
    override suspend fun markSeasonAiredSeen(season: Season, todayEpochDay: Long): List<EpisodeId> = error("not used")
    override suspend fun markShowAiredSeen(seasons: List<Season>, todayEpochDay: Long): List<EpisodeId> = error("not used")
    override suspend fun unmarkSeason(season: Season) = error("not used")
    override suspend fun unmarkShow(seasons: List<Season>) = error("not used")
    override suspend fun undoBulkMark(episodeIds: List<EpisodeId>) = error("not used")
    override suspend fun markPreviousSeen(seasons: List<Season>, target: EpisodeId) = error("not used")
    override suspend fun markAllAiredSeen(seasons: List<Season>, todayEpochDay: Long) = error("not used")
    override suspend fun clearProgress(mediaId: MediaId) = error("not used")
    override suspend fun setMovieWatched(mediaId: MediaId, watched: Boolean) = error("not used")
}
