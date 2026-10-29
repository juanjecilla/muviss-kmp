package com.codingpit.muviss.feature.collection.data

import app.cash.turbine.test
import com.codingpit.muviss.core.common.AppDispatchers
import com.codingpit.muviss.core.database.MuvissDatabase
import com.codingpit.muviss.core.testing.CountingDriver
import com.codingpit.muviss.core.testing.FakeClock
import com.codingpit.muviss.core.testing.inMemoryDriver
import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.ProductionStatus
import com.codingpit.muviss.models.WatchStatus
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #600: Detail reads one title's status through its membership, so a finished title costs one by-id query
 * per tick, not a read of the whole library. Counting statements is what pins it: the select-all would also
 * be one query, so the SQL text is checked too.
 */
class MembershipQueryBudgetTest {

    private val dispatchers = object : AppDispatchers {
        override val default = UnconfinedTestDispatcher()
        override val io = default
    }

    @Test
    fun `a membership read is one by-id query and ticking the title re-runs only that query`() = runTest {
        val (driver, database, repository) = library(size = 100)
        val target = MediaId.tmdbMovie("42")

        repository.observeEntry(target).test {
            assertEquals(WatchStatus.NOT_STARTED, awaitItem()!!.status)
            driver.reset()

            database.episodeProgressQueries.upsert(EpisodeId.forMovie(target).toString(), target.toString(), 0L, 0L, true, 2_000L, true)

            assertEquals(WatchStatus.WATCHED, awaitItem()!!.status)
            assertEquals(1, driver.queries, driver.executedSql.joinToString("\n"))
            val reads = driver.executedSql.filter { it.trimStart().startsWith("SELECT") }
            assertEquals(1, reads.size, reads.joinToString("\n"))
            assertTrue(reads.single().contains("c.mediaId = ?"), "expected the by-id query, ran: ${reads.single()}")
            assertTrue(!reads.single().contains("ORDER BY"), "the Library's select-all must not run for one title: ${reads.single()}")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `an unsaved title emits no membership and reads nothing but its own row`() = runTest {
        val (driver, _, repository) = library(size = 100)
        driver.reset()

        repository.observeEntry(MediaId.tmdbMovie("9999")).test {
            assertEquals(null, awaitItem())
            assertEquals(1, driver.queries, driver.executedSql.joinToString("\n"))
            cancelAndIgnoreRemainingEvents()
        }
    }

    private suspend fun library(size: Int): Triple<CountingDriver, MuvissDatabase, SqlDelightCollectionRepository> {
        val driver = inMemoryDriver(::CountingDriver)
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
