@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.profile.data

import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.turbine.test
import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.core.common.AppDispatchers
import com.codingpit.muviss.core.database.MuvissDatabase
import com.codingpit.muviss.feature.collection.api.CollectionApi
import com.codingpit.muviss.feature.collection.api.CollectionMembership
import com.codingpit.muviss.feature.collection.api.CollectionSummary
import com.codingpit.muviss.feature.collection.api.NewEpisodesResult
import com.codingpit.muviss.feature.collection.data.SqlDelightCollectionRepository
import com.codingpit.muviss.feature.collection.domain.CollectionRepository
import com.codingpit.muviss.feature.profile.domain.GenreCount
import com.codingpit.muviss.feature.profile.domain.GenreRating
import com.codingpit.muviss.feature.profile.domain.ObserveProfileStatsUseCase
import com.codingpit.muviss.feature.profile.domain.StatusBreakdown
import com.codingpit.muviss.feature.profile.domain.WatchStreak
import com.codingpit.muviss.feature.progress.api.EpisodePlay
import com.codingpit.muviss.feature.progress.api.ProgressApi
import com.codingpit.muviss.feature.progress.data.SqlDelightProgressRepository
import com.codingpit.muviss.feature.progress.domain.ProgressRepository
import com.codingpit.muviss.models.Episode
import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.MediaType
import com.codingpit.muviss.models.ProductionStatus
import com.codingpit.muviss.models.Season
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

private class StatsAggregationDispatchers(d: CoroutineDispatcher) : AppDispatchers {
    override val default = d
    override val io = d
}

private class StatsTestClock(private var epochDay: Long) : AppClock {
    override fun nowEpochMs(): Long = epochDay * MILLIS_PER_DAY
    fun advanceToEpochDay(day: Long) {
        epochDay = day
    }

    private companion object {
        const val MILLIS_PER_DAY = 86_400_000L
    }
}

/** [CollectionApi] wired directly over the real [SqlDelightCollectionRepository] — test-only, mirrors `ProgressCollectionStatusIntegrationTest`'s `RealSeenEpisodesProgressApi`. */
private class RealCollectionApiForStats(private val repository: CollectionRepository) : CollectionApi {
    override fun observeMembership(mediaId: MediaId): Flow<CollectionMembership?> = error("not used")

    override fun observeSummaries(): Flow<List<CollectionSummary>> = repository.observeAll().map { entries ->
        entries.map { entry ->
            CollectionSummary(
                mediaId = entry.mediaId,
                title = entry.title,
                posterUrl = entry.posterUrl,
                status = entry.status,
                genres = entry.genres,
                runtimeMinutes = entry.runtimeMinutes,
                seenEpisodes = entry.seenEpisodes,
                rating = entry.rating,
            )
        }
    }

    override suspend fun add(details: MediaDetails) = repository.upsertSnapshot(details)
    override suspend fun remove(mediaId: MediaId) = repository.remove(mediaId)
    override suspend fun setFavorite(mediaId: MediaId, favorite: Boolean) = error("not used")
    override suspend fun setNotificationsMuted(mediaId: MediaId, muted: Boolean) = error("not used")
    override suspend fun setRating(mediaId: MediaId, rating: Int?) = repository.setRating(mediaId, rating)
    override suspend fun setNote(mediaId: MediaId, note: String?) = error("not used")
    override suspend fun refreshAndFindNewEpisodes(): List<NewEpisodesResult> = error("not used")
}

/**
 * [ProgressApi] wired directly over the real [SqlDelightProgressRepository] —
 * test-only. [observeSeenEpisodes] has to be real (not a stub): the
 * [SqlDelightCollectionRepository] under test calls it internally to join
 * each entry's live seen count (see its own `withSeenEpisodes`).
 */
private class RealProgressApiForStats(private val repository: ProgressRepository) : ProgressApi {
    override fun observeSeenEpisodes(mediaId: MediaId): Flow<Set<EpisodeId>> = repository.observeForMedia(mediaId)
        .map { rows -> rows.filter { it.seen }.map { it.episodeId }.toSet() }

    override fun observeSeenActivityEpochDays(): Flow<Set<Long>> = repository.observeSeenActivityEpochDays()
    override suspend fun setEpisodeSeen(episodeId: EpisodeId, seen: Boolean) = repository.setSeen(episodeId, seen)
    override fun observePlayCounts(mediaId: MediaId): Flow<Map<EpisodeId, Int>> = repository.observePlayCounts(mediaId)
    override fun observePlays(episodeId: EpisodeId): Flow<List<EpisodePlay>> = repository.observePlays(episodeId)
    override fun observeRewatchCounts(sinceEpochMs: Long): Flow<Map<MediaId, Int>> = repository.observeRewatchCounts(sinceEpochMs)
    override fun observeRewatchTimestamps(sinceEpochMs: Long): Flow<List<Long>> = repository.observeRewatchTimestamps(sinceEpochMs)
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
    override suspend fun setMovieWatched(mediaId: MediaId, watched: Boolean) = repository.setSeen(EpisodeId.forMovie(mediaId), watched)
}

/**
 * Exercises [ObserveProfileStatsUseCase] end to end against real
 * SQLDelight-backed collection + progress repositories over one shared
 * in-memory database — the "JVM in-memory driver fixtures" the stats
 * aggregation use case is required to be tested with. [ProfileStatsCalculatorTest]
 * (profile:domain, no database) covers the pure arithmetic exhaustively; this
 * test proves the whole pipeline (snapshot -> ticks -> derived status ->
 * aggregated stats) wires together correctly.
 */
class ProfileStatsAggregationTest {

    private lateinit var clock: StatsTestClock
    private lateinit var collectionApi: CollectionApi
    private lateinit var progressApi: ProgressApi
    private lateinit var useCase: ObserveProfileStatsUseCase

    private val movieA = MediaId.tmdbMovie("1")
    private val movieB = MediaId.tmdbMovie("2")
    private val showX = MediaId.tmdbTv("3")

    private fun episode(number: Int, airDay: Long, runtime: Int?) = Episode(
        id = EpisodeId(showX, seasonNumber = 1, episodeNumber = number),
        seasonNumber = 1,
        episodeNumber = number,
        name = "E$number",
        airDateEpochDay = airDay,
        runtimeMinutes = runtime,
    )

    private val ep1 = episode(1, airDay = 1, runtime = 40)
    private val ep2 = episode(2, airDay = 2, runtime = 50)
    private val ep3 = episode(3, airDay = 3, runtime = null)
    private val ep4 = episode(4, airDay = 4, runtime = null)

    @BeforeTest
    fun setUp() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MuvissDatabase.Schema.synchronous().create(driver)
        val database = MuvissDatabase(driver)
        val dispatchers = StatsAggregationDispatchers(UnconfinedTestDispatcher())
        clock = StatsTestClock(epochDay = 0)

        val progressRepository: ProgressRepository = SqlDelightProgressRepository(database.episodeProgressQueries, database.episodePlayQueries, dispatchers, clock)
        progressApi = RealProgressApiForStats(progressRepository)
        val collectionRepository: CollectionRepository =
            SqlDelightCollectionRepository(database.collectionEntryQueries, dispatchers, clock, progressApi)
        collectionApi = RealCollectionApiForStats(collectionRepository)
        useCase = ObserveProfileStatsUseCase(collectionApi, progressApi, clock)
    }

    @Test
    fun aggregates_status_counts_hours_genres_and_a_gapped_streak() = runTest {
        clock.advanceToEpochDay(10) // "today" for every snapshot below.

        collectionApi.add(
            MediaDetails(
                summary = MediaSummary(movieA, "Movie A"),
                genres = listOf("Action", "Sci-Fi"),
                runtimeMinutes = 136,
                productionStatus = ProductionStatus.RELEASED,
            ),
        )
        collectionApi.add(
            MediaDetails(
                summary = MediaSummary(movieB, "Movie B"),
                genres = listOf("Comedy"),
                runtimeMinutes = 90, // known runtime, but never watched -> contributes 0 minutes.
                productionStatus = ProductionStatus.RELEASED,
            ),
        )
        collectionApi.add(
            MediaDetails(
                summary = MediaSummary(showX, "Show X"),
                genres = listOf("Drama", "Action"),
                productionStatus = ProductionStatus.RETURNING,
                seasons = listOf(Season(1, "S1", listOf(ep1, ep2, ep3, ep4))),
            ),
        )

        // Watch activity: two consecutive days early on (5, 6), a gap, then a
        // single day of activity that lands on "today" (10) — exercises the
        // streak's gap handling against real ticked rows, not just the pure
        // calculator (see WatchStreakCalculatorTest for exhaustive gap cases).
        clock.advanceToEpochDay(5)
        progressApi.setEpisodeSeen(ep1.id, true)
        clock.advanceToEpochDay(6)
        progressApi.setEpisodeSeen(ep2.id, true)
        clock.advanceToEpochDay(10)
        progressApi.setMovieWatched(movieA, true)

        useCase().test {
            val stats = awaitItem()

            assertEquals(StatusBreakdown(notStarted = 1, watching = 1, watched = 1, finished = 0), stats.statusBreakdown)
            assertEquals(1, stats.moviesWatched)
            assertEquals(2, stats.episodesSeen) // TV only: ep1 + ep2; Movie A's synthetic tick doesn't count.
            // Movie A: real runtime (136, watched). Show X: 2 seen episodes * average
            // known episode runtime ((40+50)/2 = 45); Movie B is never watched.
            assertEquals(136L + 2 * 45L, stats.estimatedMinutesWatched)
            assertEquals(
                setOf(GenreCount("Action", 2), GenreCount("Sci-Fi", 1), GenreCount("Comedy", 1), GenreCount("Drama", 1)),
                stats.genreBreakdown.toSet(),
            )
            assertEquals(WatchStreak(currentDays = 1, longestDays = 2), stats.streak)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun ratings_flow_through_the_real_repository_into_the_aggregated_stats() = runTest {
        collectionApi.add(MediaDetails(summary = MediaSummary(movieA, "Movie A"), genres = listOf("Action")))
        collectionApi.add(MediaDetails(summary = MediaSummary(movieB, "Movie B"), genres = listOf("Action")))
        collectionApi.setRating(movieA, 8)
        collectionApi.setRating(movieB, 6)

        useCase().test {
            val stats = awaitItem()

            assertEquals(2, stats.ratedCount)
            assertEquals(7.0, stats.averageRating)
            assertEquals(GenreRating("Action", 7.0, 2), stats.topRatedGenre)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun a_second_viewing_reaches_the_profile_card_with_its_unit_intact() = runTest {
        clock.advanceToEpochDay(10)
        collectionApi.add(MediaDetails(summary = MediaSummary(movieA, "Movie A")))
        collectionApi.add(
            MediaDetails(
                summary = MediaSummary(showX, "Show X"),
                productionStatus = ProductionStatus.RETURNING,
                seasons = listOf(Season(1, "S1", listOf(ep1, ep2, ep3, ep4))),
            ),
        )
        progressApi.setEpisodeSeen(ep1.id, true)
        progressApi.setEpisodeSeen(ep2.id, true)
        progressApi.setMovieWatched(movieA, true)

        clock.advanceToEpochDay(20)
        progressApi.recordPlay(ep1.id)
        progressApi.recordPlay(ep2.id)
        progressApi.recordPlay(EpisodeId.forMovie(movieA))

        useCase().test {
            val entries = awaitItem().mostRewatched

            assertEquals(listOf("Show X" to 2, "Movie A" to 1), entries.map { it.title to it.rewatches })
            assertEquals(listOf(MediaType.TV, MediaType.MOVIE), entries.map { it.mediaType })

            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * The ranking is a view of the library, so removing a title takes it out
     * of the list — but only out of the list. Its viewings are untouched on
     * disk (only `clearForMedia` erases those), which is what lets the trend
     * keep counting them and what restores the number if the title is saved
     * again. See ADR 0012.
     */
    @Test
    fun a_removed_title_leaves_the_ranking_and_keeps_its_history() = runTest {
        clock.advanceToEpochDay(10)
        collectionApi.add(MediaDetails(summary = MediaSummary(movieA, "Movie A")))
        progressApi.setMovieWatched(movieA, true)
        clock.advanceToEpochDay(20)
        progressApi.recordPlay(EpisodeId.forMovie(movieA))

        collectionApi.remove(movieA)

        useCase().test {
            assertEquals(emptyList(), awaitItem().mostRewatched)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(mapOf(movieA to 1), progressApi.observeRewatchCounts(0L).first())
    }
}
