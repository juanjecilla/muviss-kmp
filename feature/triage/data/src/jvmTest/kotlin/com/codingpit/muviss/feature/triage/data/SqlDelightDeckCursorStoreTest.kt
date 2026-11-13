package com.codingpit.muviss.feature.triage.data

import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import com.codingpit.muviss.core.common.AppDispatchers
import com.codingpit.muviss.core.testing.inMemoryDatabase
import com.codingpit.muviss.feature.triage.domain.DeckResume
import com.codingpit.muviss.feature.triage.domain.SavedDeckCursor
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** #722: the saved deck position round-trips through the per-device table, and stale rows are pruned. */
class SqlDelightDeckCursorStoreTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val dispatchers = object : AppDispatchers {
        override val default = dispatcher
        override val io = dispatcher
    }

    @Test
    fun a_saved_position_reads_back_as_it_was_written() = runTest {
        val database = inMemoryDatabase()
        val store = SqlDelightDeckCursorStore(database.triageDeckCursorQueries, dispatchers)
        val saved = SavedDeckCursor(moviePage = 9, tvPage = 4, moviesResumePage = 7, tvResumePage = 3, savedAtEpochMs = 1_000L)

        store.write("movie|any", saved)

        assertEquals(saved, store.read("movie|any"))
        assertNull(store.read("tv|any"), "another filter has its own position")
    }

    @Test
    fun a_write_prunes_positions_older_than_the_ttl() = runTest {
        val database = inMemoryDatabase()
        val store = SqlDelightDeckCursorStore(database.triageDeckCursorQueries, dispatchers)
        val old = SavedDeckCursor(moviePage = 2, tvPage = 2, moviesResumePage = 2, tvResumePage = 2, savedAtEpochMs = 0L)
        store.write("stale", old)

        val now = DeckResume.TTL_MS + 10
        store.write("fresh", SavedDeckCursor(moviePage = 2, tvPage = 2, moviesResumePage = 2, tvResumePage = 2, savedAtEpochMs = now))

        assertNull(database.triageDeckCursorQueries.selectByKey("stale").awaitAsOneOrNull())
    }

    @Test
    fun delete_all_data_wipes_the_saved_positions() = runTest {
        val database = inMemoryDatabase()
        val store = SqlDelightDeckCursorStore(database.triageDeckCursorQueries, dispatchers)
        store.write("movie|any", SavedDeckCursor(moviePage = 2, tvPage = 2, moviesResumePage = 2, tvResumePage = 2, savedAtEpochMs = 5L))

        database.triageDeckCursorQueries.deleteAll()

        assertNull(store.read("movie|any"))
    }
}
