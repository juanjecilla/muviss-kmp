package com.codingpit.muviss.feature.settings.data

import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import com.codingpit.muviss.core.common.AppDispatchers
import com.codingpit.muviss.core.testing.FakeClock
import com.codingpit.muviss.core.testing.inMemoryDatabase
import com.codingpit.muviss.feature.settings.api.TasteItem
import com.codingpit.muviss.feature.settings.api.TasteKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SqlDelightTasteProfileRepositoryTest {

    private val dispatchers = object : AppDispatchers {
        override val default = UnconfinedTestDispatcher()
        override val io = default
    }

    private val netflix = TasteItem(TasteKind.SERVICE, "8", "Netflix")

    private fun repository(clock: FakeClock) = SqlDelightTasteProfileRepository(
        inMemoryDatabase().tasteProfileQueries,
        dispatchers,
        clock,
    )

    @Test
    fun liking_an_item_stores_it_dirty_with_the_clock_stamp() = runTest {
        val database = inMemoryDatabase()
        val repository = SqlDelightTasteProfileRepository(database.tasteProfileQueries, dispatchers, FakeClock(7_000L))

        repository.setLiked(netflix, liked = true)

        val row = database.tasteProfileQueries.selectById("SERVICE:8").awaitAsOneOrNull()!!
        assertEquals(7_000L, row.updatedAtEpochMs)
        assertTrue(row.isDirty)
        assertFalse(row.deleted)
        assertEquals("Netflix", row.label)
    }

    @Test
    fun liking_an_item_already_liked_does_not_move_its_stamp() = runTest {
        val database = inMemoryDatabase()
        val clock = FakeClock(7_000L)
        val repository = SqlDelightTasteProfileRepository(database.tasteProfileQueries, dispatchers, clock)
        repository.setLiked(netflix, liked = true)

        clock.epochMs = 9_000L
        repository.setLiked(netflix, liked = true)

        assertEquals(7_000L, database.tasteProfileQueries.selectById("SERVICE:8").awaitAsOneOrNull()!!.updatedAtEpochMs)
    }

    @Test
    fun unliking_soft_deletes_and_drops_the_item_from_every_read() = runTest {
        val database = inMemoryDatabase()
        val clock = FakeClock(7_000L)
        val repository = SqlDelightTasteProfileRepository(database.tasteProfileQueries, dispatchers, clock)
        repository.setLiked(netflix, liked = true)

        clock.epochMs = 8_000L
        repository.setLiked(netflix, liked = false)

        val tombstone = database.tasteProfileQueries.selectById("SERVICE:8").awaitAsOneOrNull()!!
        assertTrue(tombstone.deleted, "a soft delete, so the tombstone can reach another device")
        assertEquals(8_000L, tombstone.updatedAtEpochMs)
        assertTrue(repository.observe().first().isEmpty())
    }

    @Test
    fun observe_emits_liked_items_only() = runTest {
        val repository = repository(FakeClock(1L))
        repository.setLiked(netflix, liked = true)
        repository.setLiked(TasteItem(TasteKind.LANGUAGE, "es", "Español"), liked = true)
        repository.setLiked(netflix, liked = false)

        val items = repository.observe().first()

        assertEquals(listOf("Español"), items.map { it.label })
    }
}
