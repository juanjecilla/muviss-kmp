package com.codingpit.muviss.feature.collection.data

import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.turbine.test
import com.codingpit.muviss.core.common.AppDispatchers
import com.codingpit.muviss.core.database.MuvissDatabase
import com.codingpit.muviss.core.testing.CountingDriver
import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.ProductionStatus
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * EPIC 28 (#70): the Library is one query per invalidation, whatever its size.
 *
 * It used to open one progress Flow per entry: a tick re-ran all N of them and
 * a snapshot refresh (N upserts) rebuilt all N subscriptions, on the order of
 * N² queries for a pull-to-refresh. Counts, not wall-clock, so CI is stable.
 */
class LibraryQueryBudgetTest {

    private val dispatchers = object : AppDispatchers {
        override val default = UnconfinedTestDispatcher()
        override val io = default
    }

    @Test
    fun `ticking an episode re-runs exactly the one library query`() = runTest {
        val (driver, database, repository) = library(size = 100)

        repository.observeAll().test {
            assertEquals(100, awaitItem().size)
            driver.reset()

            val movie = MediaId.tmdbMovie("1")
            database.episodeProgressQueries.upsert(EpisodeId.forMovie(movie).toString(), movie.toString(), 0L, 0L, true, 2_000L, true)
            assertEquals(1, awaitItem().single { it.mediaId == movie }.seenEpisodes)

            assertEquals(1, driver.queries, driver.executedSql.joinToString("\n"))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `refreshing every snapshot costs at most three queries per title`() = runTest {
        val (driver, _, repository) = library(size = 100)

        repository.observeAll().test {
            awaitItem()
            driver.reset()

            (1..100).forEach { repository.refreshSnapshot(movie(it)) }

            // Each refresh reads its row, writes it, and the Library re-queries
            // once — where it used to resubscribe 100 progress Flows.
            assertTrue(driver.queries <= 300, "expected at most 300 queries, ran ${driver.queries}")
            cancelAndIgnoreRemainingEvents()
        }
    }

    private suspend fun library(size: Int): Triple<CountingDriver, MuvissDatabase, SqlDelightCollectionRepository> {
        val driver = CountingDriver(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))
        MuvissDatabase.Schema.synchronous().create(driver)
        val database = MuvissDatabase(driver)
        val repository = SqlDelightCollectionRepository(database.collectionEntryQueries, dispatchers, FakeClock(1_000L))
        (1..size).forEach { repository.upsertSnapshot(movie(it)) }
        return Triple(driver, database, repository)
    }

    private fun movie(n: Int) = MediaDetails(
        MediaSummary(MediaId.tmdbMovie(n.toString()), "Movie $n", year = 2000),
        productionStatus = ProductionStatus.RELEASED,
    )
}
