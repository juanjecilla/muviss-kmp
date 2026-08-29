@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.collection.data

import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.turbine.test
import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.core.common.AppDispatchers
import com.codingpit.muviss.core.common.todayEpochDay
import com.codingpit.muviss.core.database.MuvissDatabase
import com.codingpit.muviss.feature.progress.api.EpisodePlay
import com.codingpit.muviss.feature.progress.api.ProgressApi
import com.codingpit.muviss.feature.progress.data.SqlDelightProgressRepository
import com.codingpit.muviss.feature.progress.domain.EpisodeOrdering
import com.codingpit.muviss.models.Episode
import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.ProductionStatus
import com.codingpit.muviss.models.Season
import com.codingpit.muviss.models.WatchStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

private class StatusTestDispatchers(d: CoroutineDispatcher) : AppDispatchers {
    override val default = d
    override val io = d
}

private class StatusTestClock(private var millis: Long) : AppClock {
    override fun nowEpochMs(): Long = millis
    fun advanceToEpochDay(day: Long) {
        millis = day * 86_400_000L
    }
}

/**
 * [ProgressApi] wired directly over the real [SqlDelightProgressRepository] —
 * this test's only use of `:feature:progress:data`/`:domain` (test-only
 * dependencies, see this module's `build.gradle.kts`); production code only
 * ever depends on progress's `:api`.
 */
private class RealSeenEpisodesProgressApi(private val repository: SqlDelightProgressRepository) : ProgressApi {
    override fun observeSeenEpisodes(mediaId: MediaId): Flow<Set<EpisodeId>> = repository.observeForMedia(mediaId)
        .map { rows -> rows.filter { it.seen }.map { it.episodeId }.toSet() }

    override fun observeSeenActivityEpochDays(): Flow<Set<Long>> = repository.observeSeenActivityEpochDays()

    override fun observePlayCounts(mediaId: MediaId): Flow<Map<EpisodeId, Int>> = repository.observePlayCounts(mediaId)
    override fun observePlays(episodeId: EpisodeId): Flow<List<EpisodePlay>> = repository.observePlays(episodeId)

    override fun observeRewatchCounts(sinceEpochMs: Long): Flow<Map<MediaId, Int>> = repository.observeRewatchCounts(sinceEpochMs)
    override fun observeRewatchTimestamps(sinceEpochMs: Long): Flow<List<Long>> = repository.observeRewatchTimestamps(sinceEpochMs)

    override suspend fun setEpisodeSeen(episodeId: EpisodeId, seen: Boolean) = repository.setSeen(episodeId, seen)
    override suspend fun recordPlay(episodeId: EpisodeId) = repository.recordPlay(episodeId)
    override suspend fun removeLatestPlay(episodeId: EpisodeId) = repository.removeLatestPlay(episodeId)
    override suspend fun clearPlays(episodeId: EpisodeId) = repository.clearPlays(episodeId)

    override suspend fun markSeasonAiredSeen(season: Season, todayEpochDay: Long): List<EpisodeId> = repository.recordPlaysForUnseen(EpisodeOrdering.airedBy(listOf(season), todayEpochDay))

    override suspend fun markShowAiredSeen(seasons: List<Season>, todayEpochDay: Long): List<EpisodeId> = repository.recordPlaysForUnseen(EpisodeOrdering.airedBy(seasons, todayEpochDay))

    override suspend fun unmarkSeason(season: Season) = repository.removeLatestPlays(season.episodes.map { it.id })
    override suspend fun unmarkShow(seasons: List<Season>) = repository.removeLatestPlays(seasons.flatMap { it.episodes }.map { it.id })
    override suspend fun undoBulkMark(episodeIds: List<EpisodeId>) = repository.removeLatestPlays(episodeIds)

    override suspend fun markPreviousSeen(seasons: List<Season>, target: EpisodeId) = error("not used")

    override suspend fun markAllAiredSeen(seasons: List<Season>, todayEpochDay: Long) = error("not used")

    override suspend fun clearProgress(mediaId: MediaId) = error("not used")
    override suspend fun setMovieWatched(mediaId: MediaId, watched: Boolean) = error("not used")
}

/**
 * End-to-end check of EPIC 3's core promise (issue #2): episode ticks
 * (progress feature) flow through to collection's derived [WatchStatus]
 * (ADR 0005) without either feature storing status directly. Exercises the
 * real SQLDelight-backed repositories from both slices against one shared
 * in-memory database.
 */
class ProgressCollectionStatusIntegrationTest {

    private lateinit var clock: StatusTestClock
    private lateinit var progressApi: ProgressApi
    private lateinit var collectionRepository: SqlDelightCollectionRepository

    private val show = MediaId.tmdbTv("1399")

    private fun episode(season: Int, number: Int, airDay: Long) = Episode(
        id = EpisodeId(show, season, number),
        seasonNumber = season,
        episodeNumber = number,
        name = "S${season}E$number",
        airDateEpochDay = airDay,
    )

    private val episodeA = episode(1, 1, airDay = 100)
    private val episodeB = episode(1, 2, airDay = 101)
    private val episodeC = episode(2, 1, airDay = 102)
    private val episodeD = episode(2, 2, airDay = 140)

    // Genuinely beyond "today" (day 150), unlike episodeD which the older
    // tests move the clock past. This is the one a bulk mark must not touch.
    private val unairedEpisode = episode(2, 3, airDay = 900)

    private fun details(seasons: List<Season>, productionStatus: ProductionStatus) = MediaDetails(
        summary = MediaSummary(show, "Show"),
        productionStatus = productionStatus,
        seasons = seasons,
    )

    @BeforeTest
    fun setUp() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MuvissDatabase.Schema.synchronous().create(driver)
        val db = MuvissDatabase(driver)
        val dispatchers = StatusTestDispatchers(UnconfinedTestDispatcher())
        clock = StatusTestClock(0L)
        clock.advanceToEpochDay(150) // "today" — episodes A, B, C have aired; D hasn't yet.
        val progressRepository = SqlDelightProgressRepository(db.episodeProgressQueries, db.episodePlayQueries, dispatchers, clock)
        progressApi = RealSeenEpisodesProgressApi(progressRepository)
        collectionRepository = SqlDelightCollectionRepository(db.collectionEntryQueries, dispatchers, clock, progressApi)
    }

    @Test
    fun ticks_drive_the_full_notStarted_to_finished_lifecycle() = runTest {
        // Season 1 (A, B) + season 2 episode 1 (C) have aired; D hasn't. 3/3 aired, 3 total.
        collectionRepository.upsertSnapshot(
            details(listOf(Season(1, "S1", listOf(episodeA, episodeB)), Season(2, "S2", listOf(episodeC))), ProductionStatus.RETURNING),
        )

        collectionRepository.observeEntry(show).test {
            assertEquals(WatchStatus.NOT_STARTED, awaitItem()!!.status)

            progressApi.setEpisodeSeen(episodeA.id, true)
            assertEquals(WatchStatus.WATCHING, awaitItem()!!.status)

            progressApi.setEpisodeSeen(episodeB.id, true)
            assertEquals(WatchStatus.WATCHING, awaitItem()!!.status) // still 2/3 aired

            progressApi.setEpisodeSeen(episodeC.id, true)
            assertEquals(WatchStatus.WATCHED, awaitItem()!!.status) // caught up, show still returning

            // A new episode (D) airs: re-snapshot now includes it, aired count grows to 4.
            collectionRepository.upsertSnapshot(
                details(
                    listOf(Season(1, "S1", listOf(episodeA, episodeB)), Season(2, "S2", listOf(episodeC, episodeD))),
                    ProductionStatus.RETURNING,
                ),
            )
            assertEquals(WatchStatus.WATCHING, awaitItem()!!.status) // Watched -> Watching per ADR 0005

            progressApi.setEpisodeSeen(episodeD.id, true)
            assertEquals(WatchStatus.WATCHED, awaitItem()!!.status) // caught up again, still returning

            // Production wraps: same seen/aired counts, but now Finished.
            collectionRepository.upsertSnapshot(
                details(
                    listOf(Season(1, "S1", listOf(episodeA, episodeB)), Season(2, "S2", listOf(episodeC, episodeD))),
                    ProductionStatus.ENDED,
                ),
            )
            assertEquals(WatchStatus.FINISHED, awaitItem()!!.status)

            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * The regression this whole aired-only rule exists for.
     *
     * "Mark season seen" used to tick every episode in the season, unaired
     * ones included. `CollectionEntry.status` builds a `WatchProgress`, which
     * `require(seenEpisodes <= airedEpisodes)` — so the next time the library
     * screen derived this title's status, it threw. Reading the status here is
     * the assertion: before the fix, this test crashed rather than failed.
     */
    @Test
    fun marking_a_currently_airing_season_seen_leaves_a_status_that_can_be_read() = runTest {
        val airing = Season(2, "S2", listOf(episodeC, unairedEpisode))
        collectionRepository.upsertSnapshot(
            details(listOf(Season(1, "S1", listOf(episodeA, episodeB)), airing), ProductionStatus.RETURNING),
        )

        collectionRepository.observeEntry(show).test {
            awaitItem()

            progressApi.markSeasonAiredSeen(airing, clock.todayEpochDay())

            val entry = awaitItem()!!
            assertEquals(1, entry.seenEpisodes, "only the aired episode of that season should be ticked")
            // Deriving the status is what used to throw.
            assertEquals(WatchStatus.WATCHING, entry.status)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun marking_the_whole_show_seen_stops_at_what_has_aired() = runTest {
        val seasons = listOf(
            Season(1, "S1", listOf(episodeA, episodeB)),
            Season(2, "S2", listOf(episodeC, unairedEpisode)),
        )
        collectionRepository.upsertSnapshot(details(seasons, ProductionStatus.RETURNING))

        collectionRepository.observeEntry(show).test {
            awaitItem()

            progressApi.markShowAiredSeen(seasons, clock.todayEpochDay())

            val entry = awaitItem()!!
            assertEquals(3, entry.airedEpisodes)
            assertEquals(entry.airedEpisodes, entry.seenEpisodes, "caught up is exactly 'everything that has aired'")
            assertEquals(WatchStatus.WATCHED, entry.status)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
