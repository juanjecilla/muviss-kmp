@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.collection.data

import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.turbine.test
import com.codingpit.muviss.core.database.CollectionEntryQueries
import com.codingpit.muviss.core.database.MediaListQueries
import com.codingpit.muviss.core.database.MuvissDatabase
import com.codingpit.muviss.models.MediaId
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SqlDelightListsRepositoryTest {

    private lateinit var listQueries: MediaListQueries
    private lateinit var collectionQueries: CollectionEntryQueries
    private lateinit var clock: FakeClock
    private lateinit var repository: SqlDelightListsRepository

    private val matrix = MediaId.tmdbMovie("603")
    private val show = MediaId.tmdbTv("1399")

    @BeforeTest
    fun setUp() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MuvissDatabase.Schema.synchronous().create(driver)
        val database = MuvissDatabase(driver)
        listQueries = database.mediaListQueries
        collectionQueries = database.collectionEntryQueries
        clock = FakeClock(1_000L)
        repository = SqlDelightListsRepository(listQueries, ImmediateDispatchers(UnconfinedTestDispatcher()), clock)
    }

    /** Saves a minimal library snapshot for [mediaId] so it has a live `collectionEntry` row to join against. */
    private suspend fun saveSnapshot(mediaId: MediaId, title: String = mediaId.toString()) {
        collectionQueries.upsert(
            mediaId = mediaId.toString(),
            mediaType = mediaId.type.wireName,
            title = title,
            posterUrl = null,
            releaseYear = null,
            productionStatus = "RELEASED",
            totalEpisodes = 0,
            airedEpisodes = 0,
            favorite = false,
            genres = "",
            runtimeMinutes = null,
            addedAtEpochMs = clock.nowEpochMs(),
            updatedAtEpochMs = clock.nowEpochMs(),
            isDirty = true,
            deleted = false,
            notificationsMuted = false,
            rating = null,
            note = null,
        )
    }

    @Test
    fun createList_adds_a_list_with_zero_entries() = runTest {
        repository.createList("Marathon 2026")

        repository.observeLists().test {
            val list = awaitItem().single()
            assertEquals("Marathon 2026", list.name)
            assertEquals(0, list.entryCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun createList_generates_distinct_ids_for_lists_created_at_the_same_instant() = runTest {
        val first = repository.createList("First")
        val second = repository.createList("Second")

        assertTrue(first.id != second.id)
    }

    @Test
    fun renameList_updates_the_name() = runTest {
        val list = repository.createList("Original name")

        repository.renameList(list.id, "New name")

        repository.observeLists().test {
            assertEquals("New name", awaitItem().single().name)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun deleteList_soft_deletes_and_hides_from_observeLists() = runTest {
        val list = repository.createList("Temporary")

        repository.deleteList(list.id)

        repository.observeLists().test {
            assertTrue(awaitItem().isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun deleteList_cascades_to_its_entries() = runTest {
        saveSnapshot(matrix)
        val list = repository.createList("Temporary")
        repository.addEntry(list.id, matrix)

        repository.deleteList(list.id)

        // The membership row is gone too, not just hidden behind the deleted list.
        repository.observeListIdsContaining(matrix).test {
            assertTrue(awaitItem().isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun addEntry_then_observeListContents_shows_the_saved_title() = runTest {
        saveSnapshot(matrix, title = "The Matrix")
        val list = repository.createList("Favorites")

        repository.addEntry(list.id, matrix)

        repository.observeListContents(list.id).test {
            val item = awaitItem().single()
            assertEquals(matrix, item.mediaId)
            assertEquals("The Matrix", item.title)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun removeEntry_soft_deletes_and_hides_from_observeListContents() = runTest {
        saveSnapshot(matrix)
        val list = repository.createList("Favorites")
        repository.addEntry(list.id, matrix)

        repository.removeEntry(list.id, matrix)

        repository.observeListContents(list.id).test {
            assertTrue(awaitItem().isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun removeEntry_then_readd_undeletes_and_keeps_original_addedAt() = runTest {
        saveSnapshot(matrix)
        val list = repository.createList("Favorites")
        repository.addEntry(list.id, matrix)
        repository.removeEntry(list.id, matrix)

        clock.advanceTo(9_000L)
        repository.addEntry(list.id, matrix)

        repository.observeListContents(list.id).test {
            assertEquals(1_000L, awaitItem().single().addedAtEpochMs)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun observeListContents_hides_an_entry_with_no_library_snapshot() = runTest {
        val list = repository.createList("Watchlist")

        // Added to the list without ever being saved to the library.
        repository.addEntry(list.id, matrix)

        repository.observeListContents(list.id).test {
            assertTrue(awaitItem().isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun observeListContents_reappears_once_the_title_is_saved() = runTest {
        val list = repository.createList("Watchlist")
        repository.addEntry(list.id, matrix)

        saveSnapshot(matrix, title = "The Matrix")

        repository.observeListContents(list.id).test {
            assertEquals("The Matrix", awaitItem().single().title)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun observeListContents_hides_an_entry_whose_title_was_removed_from_the_library() = runTest {
        saveSnapshot(matrix)
        val list = repository.createList("Favorites")
        repository.addEntry(list.id, matrix)
        collectionQueries.softDelete(now = clock.nowEpochMs(), mediaId = matrix.toString())

        repository.observeListContents(list.id).test {
            assertTrue(awaitItem().isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun entryCount_matches_the_visible_contents_count_not_the_raw_membership_count() = runTest {
        saveSnapshot(matrix)
        val list = repository.createList("Mixed")
        repository.addEntry(list.id, matrix) // visible
        repository.addEntry(list.id, show) // orphaned: never saved

        repository.observeLists().test {
            assertEquals(1, awaitItem().single().entryCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun observeListIdsContaining_reflects_membership_across_multiple_lists() = runTest {
        saveSnapshot(matrix)
        val marathon = repository.createList("Marathon 2026")
        val favorites = repository.createList("Favorites")
        repository.addEntry(marathon.id, matrix)
        repository.addEntry(favorites.id, matrix)

        repository.observeListIdsContaining(matrix).test {
            assertEquals(setOf(marathon.id, favorites.id), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun observeListIdsContaining_is_empty_for_an_unlisted_title() = runTest {
        repository.observeListIdsContaining(matrix).test {
            assertTrue(awaitItem().isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
