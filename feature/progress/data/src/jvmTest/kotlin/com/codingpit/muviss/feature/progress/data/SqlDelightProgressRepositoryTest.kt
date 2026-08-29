@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.progress.data

import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.turbine.test
import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.core.common.AppDispatchers
import com.codingpit.muviss.core.common.widget.NoOpWidgetRefresher
import com.codingpit.muviss.core.database.EpisodeProgressQueries
import com.codingpit.muviss.core.database.MuvissDatabase
import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.MediaId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class ImmediateDispatchers(d: CoroutineDispatcher) : AppDispatchers {
    override val default = d
    override val io = d
}

private class FakeClock(private var millis: Long) : AppClock {
    override fun nowEpochMs(): Long = millis
    fun advanceTo(newMillis: Long) {
        millis = newMillis
    }
}

class SqlDelightProgressRepositoryTest {

    private lateinit var queries: EpisodeProgressQueries
    private lateinit var clock: FakeClock
    private lateinit var repository: SqlDelightProgressRepository

    private val show = MediaId.tmdbTv("1399")
    private val ep1 = EpisodeId(show, 1, 1)
    private val ep2 = EpisodeId(show, 1, 2)
    private val ep3 = EpisodeId(show, 1, 3)

    @BeforeTest
    fun setUp() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MuvissDatabase.Schema.synchronous().create(driver)
        val database = MuvissDatabase(driver)
        queries = database.episodeProgressQueries
        clock = FakeClock(1_000L)
        repository = SqlDelightProgressRepository(
            queries,
            database.episodePlayQueries,
            ImmediateDispatchers(UnconfinedTestDispatcher()),
            clock,
            NoOpWidgetRefresher,
        )
    }

    @Test
    fun setSeen_creates_a_row_for_a_never_ticked_episode() = runTest {
        repository.setSeen(ep1, true)

        repository.observeForMedia(show).test {
            val row = awaitItem().single()
            assertEquals(ep1, row.episodeId)
            assertTrue(row.seen)
            assertEquals(1_000L, row.updatedAtEpochMs)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun setSeen_toggles_an_existing_row() = runTest {
        repository.setSeen(ep1, true)
        clock.advanceTo(2_000L)
        repository.setSeen(ep1, false)

        repository.observeForMedia(show).test {
            val row = awaitItem().single()
            assertFalse(row.seen)
            assertEquals(2_000L, row.updatedAtEpochMs)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun setSeenBulk_ticks_every_given_episode() = runTest {
        repository.setSeenBulk(listOf(ep1, ep2, ep3), seen = true)

        repository.observeForMedia(show).test {
            val rows = awaitItem()
            assertEquals(3, rows.size)
            assertTrue(rows.all { it.seen })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun observeSeenCount_counts_only_seen_rows() = runTest {
        repository.setSeen(ep1, true)
        repository.setSeen(ep2, true)
        repository.setSeen(ep3, false)

        repository.observeSeenCount(show).test {
            assertEquals(2, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun observeSeenCount_is_zero_for_an_untouched_media() = runTest {
        repository.observeSeenCount(MediaId.tmdbTv("999")).test {
            assertEquals(0, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun observeForMedia_only_returns_rows_for_that_media() = runTest {
        val other = EpisodeId(MediaId.tmdbTv("999"), 1, 1)
        repository.setSeen(ep1, true)
        repository.setSeen(other, true)

        repository.observeForMedia(show).test {
            assertEquals(listOf(ep1), awaitItem().map { it.episodeId })
            cancelAndIgnoreRemainingEvents()
        }
    }
}
