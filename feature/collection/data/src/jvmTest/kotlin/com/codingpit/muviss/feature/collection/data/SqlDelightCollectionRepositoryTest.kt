@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.collection.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.turbine.test
import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.core.common.AppDispatchers
import com.codingpit.muviss.core.database.CollectionEntryQueries
import com.codingpit.muviss.core.database.MuvissDatabase
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.ProductionStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
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

class SqlDelightCollectionRepositoryTest {

    private lateinit var queries: CollectionEntryQueries
    private lateinit var clock: FakeClock
    private lateinit var repository: SqlDelightCollectionRepository

    @BeforeTest
    fun setUp() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MuvissDatabase.Schema.create(driver)
        queries = MuvissDatabase(driver).collectionEntryQueries
        clock = FakeClock(1_000L)
        repository = SqlDelightCollectionRepository(queries, ImmediateDispatchers(UnconfinedTestDispatcher()), clock)
    }

    private fun details(
        id: MediaId = MediaId.tmdbMovie("603"),
        title: String = "The Matrix",
        productionStatus: ProductionStatus = ProductionStatus.RELEASED,
    ) = MediaDetails(MediaSummary(id, title, year = 1999), productionStatus = productionStatus)

    @Test
    fun upsertSnapshot_adds_a_new_entry() = runTest {
        repository.upsertSnapshot(details())

        repository.observeAll().test {
            val entry = awaitItem().single()
            assertEquals(MediaId.tmdbMovie("603"), entry.mediaId)
            assertEquals("The Matrix", entry.title)
            assertEquals(false, entry.favorite)
            assertEquals(1_000L, entry.addedAtEpochMs)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun upsertSnapshot_refresh_preserves_favorite_and_original_addedAt() = runTest {
        repository.upsertSnapshot(details())
        repository.setFavorite(MediaId.tmdbMovie("603"), favorite = true)

        clock.advanceTo(5_000L)
        repository.upsertSnapshot(details(title = "The Matrix Reloaded"))

        repository.observeAll().test {
            val entry = awaitItem().single()
            assertEquals("The Matrix Reloaded", entry.title)
            assertTrue(entry.favorite)
            assertEquals(1_000L, entry.addedAtEpochMs)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun remove_soft_deletes_and_hides_from_observeAll() = runTest {
        repository.upsertSnapshot(details())
        repository.remove(MediaId.tmdbMovie("603"))

        repository.observeAll().test {
            assertTrue(awaitItem().isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun remove_then_readd_undeletes_and_keeps_original_addedAt() = runTest {
        repository.upsertSnapshot(details())
        repository.remove(MediaId.tmdbMovie("603"))

        clock.advanceTo(9_000L)
        repository.upsertSnapshot(details())

        repository.observeAll().test {
            val entry = awaitItem().single()
            assertEquals(1_000L, entry.addedAtEpochMs)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun setFavorite_toggles_independently_of_status() = runTest {
        repository.upsertSnapshot(details())
        repository.setFavorite(MediaId.tmdbMovie("603"), favorite = true)

        repository.observeEntry(MediaId.tmdbMovie("603")).test {
            val entry = awaitItem()
            assertTrue(entry!!.favorite)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun observeEntry_is_null_for_an_unsaved_title() = runTest {
        repository.observeEntry(MediaId.tmdbMovie("999")).test {
            assertNull(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun observeEntry_is_null_after_remove() = runTest {
        repository.upsertSnapshot(details())
        repository.remove(MediaId.tmdbMovie("603"))

        repository.observeEntry(MediaId.tmdbMovie("603")).test {
            assertNull(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
