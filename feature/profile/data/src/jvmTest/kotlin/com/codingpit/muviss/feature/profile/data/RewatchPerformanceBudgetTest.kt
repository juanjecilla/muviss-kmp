@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.profile.data

import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.core.common.AppDispatchers
import com.codingpit.muviss.core.database.MuvissDatabase
import com.codingpit.muviss.feature.collection.api.CollectionSummary
import com.codingpit.muviss.feature.profile.domain.MonthlyRewatchCalculator
import com.codingpit.muviss.feature.profile.domain.RewatchRankingCalculator
import com.codingpit.muviss.feature.progress.data.SqlDelightProgressRepository
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.WatchStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.measureTime

private class BudgetDispatchers(d: CoroutineDispatcher) : AppDispatchers {
    override val default = d
    override val io = d
}

private class BudgetClock : AppClock {
    override fun nowEpochMs(): Long = 0L
}

/**
 * Performance budgets for the rewatch ranking, as **operation counts** rather
 * than wall-clock time — the same reasoning as
 * `TriagePerformanceBudgetTest`: counts stay stable in CI where a timing
 * assertion would not.
 *
 * The structural risk here is specific. The counts live in one feature and
 * the titles in another, and the obvious way to put them together is to look
 * each title up as you walk the ranking — which is a query per title, and
 * degrades exactly as a library grows. The join is deliberately in Kotlin
 * over two whole-table reads instead, and that is what these numbers pin.
 */
class RewatchPerformanceBudgetTest {

    private lateinit var driver: CountingDriver
    private lateinit var repository: SqlDelightProgressRepository
    private lateinit var database: MuvissDatabase

    /** A heavier library than anyone has: 500 titles, 20 000 recorded viewings. */
    private val titleCount = 500
    private val playsPerTitle = 40

    @BeforeTest
    fun setUp() {
        driver = CountingDriver(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))
        MuvissDatabase.Schema.synchronous().create(driver)
        database = MuvissDatabase(driver)
        repository = SqlDelightProgressRepository(
            database.episodeProgressQueries,
            database.episodePlayQueries,
            BudgetDispatchers(UnconfinedTestDispatcher()),
            BudgetClock(),
        )
    }

    private suspend fun seedHeavyLibrary() {
        database.episodePlayQueries.transaction {
            repeat(titleCount) { title ->
                val mediaId = "tmdb:tv:$title"
                repeat(playsPerTitle) { play ->
                    // Ten episodes each watched four times: real rewatches,
                    // not one long first watch-through.
                    database.episodePlayQueries.insert(
                        episodeId = "$mediaId/1/${play % 10}",
                        mediaId = mediaId,
                        watchedAtEpochMs = play.toLong() * 1_000L,
                        isDirty = false,
                    )
                }
            }
        }
    }

    private fun heavyLibrarySummaries() = (0 until titleCount).map { title ->
        CollectionSummary(
            mediaId = MediaId.parse("tmdb:tv:$title"),
            title = "Show $title",
            posterUrl = null,
            status = WatchStatus.WATCHING,
        )
    }

    @Test
    fun `the whole ranking costs one query, however many titles are in it`() = runTest {
        seedHeavyLibrary()
        driver.reset()

        val counts = repository.observeRewatchCounts(0L).first()

        assertEquals(titleCount, counts.size)
        assertEquals(1, driver.queries, "the ranking must not look titles up one at a time")
    }

    @Test
    fun `the trend costs one more, not one per month`() = runTest {
        seedHeavyLibrary()
        driver.reset()

        repository.observeRewatchTimestamps(MonthlyRewatchCalculator.sinceEpochMs(0L)).first()

        assertEquals(1, driver.queries)
    }

    @Test
    fun `joining counts onto the library is linear in the library`() = runTest {
        seedHeavyLibrary()
        val counts = repository.observeRewatchCounts(0L).first()
        val summaries = heavyLibrarySummaries()

        val ranking = RewatchRankingCalculator.calculate(counts, summaries)

        assertEquals(titleCount, ranking.shows.size)
        // 10 distinct episodes played 4 times each = 30 rewatches per title.
        assertEquals(30, ranking.shows.first().rewatches)
    }

    /**
     * Deliberately loose: a canary for a query plan that turns quadratic as
     * the play table grows (the CTE joins every play against its episode's
     * first), not a benchmark. Normal machine variance comes nowhere near it.
     */
    @Test
    fun `the ranking query stays comfortably fast on a heavy library`() = runTest {
        seedHeavyLibrary()

        val elapsed = measureTime { repository.observeRewatchCounts(0L).first() }

        assertTrue(elapsed.inWholeMilliseconds < RANKING_CEILING_MS, "ranking took ${elapsed.inWholeMilliseconds}ms")
    }

    private companion object {
        const val RANKING_CEILING_MS = 2_000
    }
}
