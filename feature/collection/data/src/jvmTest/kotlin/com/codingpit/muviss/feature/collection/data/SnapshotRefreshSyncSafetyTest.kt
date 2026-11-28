@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.collection.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.codingpit.muviss.core.database.CollectionEntry
import com.codingpit.muviss.core.database.CollectionEntryQueries
import com.codingpit.muviss.core.database.MuvissDatabase
import com.codingpit.muviss.core.testing.FakeClock
import com.codingpit.muviss.core.testing.inMemoryDriver
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.ProductionStatus
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Defect 5 of EPIC 39: a snapshot refresh is not a user edit, and must not be
 * synced as one.
 *
 * The refresh runs on every visit to the Library and in the Android worker.
 * When it stamped `updatedAtEpochMs = now` and set `isDirty`, a device that had
 * merely been *looking* at a title beat another device's genuine, newer rating
 * in last-write-wins, un-deleted a title removed elsewhere, and dirtied the
 * whole library on every visit. These tests pin the rows a refresh leaves
 * behind, since the repository's public surface (a `Flow` of domain entries)
 * cannot show a sync flag at all.
 */
class SnapshotRefreshSyncSafetyTest {

    private lateinit var driver: SqlDriver
    private lateinit var database: MuvissDatabase
    private lateinit var queries: CollectionEntryQueries
    private lateinit var clock: FakeClock
    private lateinit var repository: SqlDelightCollectionRepository

    @BeforeTest
    fun setUp() {
        driver = inMemoryDriver()
        database = MuvissDatabase(driver)
        queries = database.collectionEntryQueries
        clock = FakeClock(10_000L)
        repository = SqlDelightCollectionRepository(queries, ImmediateDispatchers(UnconfinedTestDispatcher()), clock)
    }

    private val matrix = MediaId.tmdbMovie("603")

    private fun details(title: String = "The Matrix Reloaded") = MediaDetails(
        MediaSummary(matrix, title, year = 1999),
        productionStatus = ProductionStatus.RELEASED,
    )

    /** A row exactly as a completed sync leaves it: clean, and stamped by whichever device last edited it. */
    private suspend fun seedSyncedRow(
        deleted: Boolean = false,
        rating: Long? = null,
        updatedAt: Long = 2_000L,
        totalEpisodes: Long = 1L,
        airedEpisodes: Long = 1L,
    ) {
        queries.upsert(
            mediaId = matrix.toString(),
            mediaType = "movie",
            title = "The Matrix",
            posterUrl = null,
            releaseYear = 1999L,
            productionStatus = "RELEASED",
            totalEpisodes = totalEpisodes,
            airedEpisodes = airedEpisodes,
            favorite = true,
            genres = "",
            runtimeMinutes = null,
            addedAtEpochMs = 500L,
            updatedAtEpochMs = updatedAt,
            isDirty = false,
            deleted = deleted,
            notificationsMuted = false,
            rating = rating,
            note = "keep",
            revisitWillingness = null,
            coWatchPinned = false,
        )
    }

    private fun row(): CollectionEntry = assertNotNull(queries.selectById(matrix.toString()).executeAsOneOrNull())

    @Test
    fun refreshing_a_title_already_in_the_library_leaves_it_clean_and_its_timestamp_alone() = runTest {
        seedSyncedRow(rating = 9)

        repository.upsertSnapshot(details())

        val after = row()
        assertEquals("The Matrix Reloaded", after.title, "the snapshot fields are still refreshed")
        assertFalse(after.isDirty, "a refresh is not an edit, so there is nothing to push")
        assertEquals(2_000L, after.updatedAtEpochMs, "bumping the stamp lets a stale device outrank another device's newer edit")
    }

    @Test
    fun a_refresh_keeps_every_user_field() = runTest {
        seedSyncedRow(rating = 9)

        repository.upsertSnapshot(details())

        val after = row()
        assertEquals(9L, after.rating)
        assertEquals("keep", after.note)
        assertTrue(after.favorite)
        assertEquals(500L, after.addedAtEpochMs)
    }

    /** Rows changed by this connection so far. SQLite counts a row an UPDATE matched even when its values are unchanged. */
    private fun totalChanges(): Long = driver.executeQuery(
        identifier = null,
        sql = "SELECT total_changes()",
        mapper = { cursor ->
            cursor.next()
            QueryResult.Value(cursor.getLong(0) ?: 0L)
        },
        parameters = 0,
    ).value

    @Test
    fun a_refresh_that_changes_no_provider_value_writes_no_row() = runTest {
        seedSyncedRow()
        repository.refreshSnapshot(details())

        val before = totalChanges()
        repository.refreshSnapshot(details())

        assertEquals(before, totalChanges(), "a refresh with the same provider data changes no row, so a tab that only visits the Library is not seen to have written")
    }

    /** One episodeProgress tick for [matrix]; seasons 0 are specials (ADR 0037). */
    private suspend fun tick(season: Long, episode: Long) {
        database.episodeProgressQueries.upsert(
            episodeId = "$matrix-s$season-e$episode",
            mediaId = matrix.toString(),
            seasonNumber = season,
            episodeNumber = episode,
            seen = true,
            updatedAtEpochMs = 1_000L,
            isDirty = false,
        )
    }

    private class CountCase(
        val name: String,
        val storedCount: Long,
        val incomingCount: Long,
        val regularSeen: Int,
        val specialSeen: Int,
        val expectedCount: Long,
    )

    /**
     * The guard in `updateSnapshot` must use the same regular-episode floor as
     * its SET clause (ADR 0037, #938). A special tick that the floor ignores must
     * not make an identical refresh look like a change (the web worker would call
     * it this tab's own write), and a stored count the floor corrects must not be
     * skipped.
     */
    @Test
    fun a_refresh_guard_counts_the_same_regular_episodes_as_its_set_clause() = runTest {
        val cases = listOf(
            CountCase("regular only, counts match: nothing changes", 10, 10, 10, 0, 10),
            CountCase("a seen special, counts match: nothing changes", 10, 10, 10, 1, 10),
            CountCase("a seen special, stored one above regular: corrected", 11, 10, 10, 1, 10),
            CountCase("regular only, stored one above: corrected", 11, 10, 10, 0, 10),
        )
        for (case in cases) {
            queries.deleteAll()
            database.episodeProgressQueries.deleteAll()
            seedSyncedRow(totalEpisodes = case.storedCount, airedEpisodes = case.storedCount)
            (1..case.regularSeen).forEach { tick(season = 1, episode = it.toLong()) }
            (1..case.specialSeen).forEach { tick(season = 0, episode = it.toLong()) }

            val before = totalChanges()
            queries.updateSnapshot(
                title = "The Matrix",
                posterUrl = null,
                releaseYear = 1999L,
                productionStatus = "RELEASED",
                totalEpisodes = case.incomingCount,
                airedEpisodes = case.incomingCount,
                genres = "",
                runtimeMinutes = null,
                mediaId = matrix.toString(),
            )

            // A stored count the floor corrects is the only thing that may change the row.
            val expectChanged = case.storedCount != case.expectedCount
            assertEquals(expectChanged, totalChanges() > before, "${case.name}: rows changed")
            assertEquals(case.expectedCount, row().totalEpisodes, "${case.name}: totalEpisodes")
            assertEquals(case.expectedCount, row().airedEpisodes, "${case.name}: airedEpisodes")
        }
    }

    @Test
    fun several_refreshes_in_a_row_still_issue_no_sync_writes() = runTest {
        seedSyncedRow()

        repeat(5) {
            clock.advanceTo(20_000L + it)
            repository.upsertSnapshot(details(title = "Edit $it"))
        }

        assertTrue(queries.selectDirty().executeAsList().isEmpty(), "a Library visit must leave nothing waiting to sync")
    }

    @Test
    fun refreshSnapshot_does_not_revive_a_title_removed_on_this_or_another_device() = runTest {
        seedSyncedRow(deleted = true, updatedAt = 8_000L)

        repository.refreshSnapshot(details())

        val after = row()
        assertTrue(after.deleted, "a refresh is not an add; a tombstone stays until someone re-adds the title")
        assertEquals("The Matrix", after.title, "and a tombstone's snapshot is not touched either")
        assertFalse(after.isDirty)
        assertEquals(8_000L, after.updatedAtEpochMs)
    }

    @Test
    fun refreshSnapshot_on_a_title_that_was_never_saved_creates_nothing() = runTest {
        repository.refreshSnapshot(details())

        assertEquals(null, queries.selectById(matrix.toString()).executeAsOneOrNull())
    }

    @Test
    fun refreshSnapshot_leaves_a_live_row_clean_and_unstamped_and_updates_only_provider_data() = runTest {
        seedSyncedRow(rating = 7)

        repository.refreshSnapshot(details(title = "Fresh"))

        val after = row()
        assertEquals("Fresh", after.title)
        assertFalse(after.isDirty)
        assertEquals(2_000L, after.updatedAtEpochMs)
        assertEquals(7L, after.rating)
        assertEquals("keep", after.note)
        assertTrue(after.favorite)
    }

    @Test
    fun readding_a_removed_title_is_a_user_action_and_is_stamped_and_dirty() = runTest {
        seedSyncedRow(deleted = true, updatedAt = 8_000L, rating = 7)
        clock.advanceTo(20_000L)

        repository.upsertSnapshot(details())

        val after = row()
        assertFalse(after.deleted)
        assertTrue(after.isDirty)
        assertEquals(20_000L, after.updatedAtEpochMs)
        assertEquals(7L, after.rating, "the re-added title keeps what the user said about it")
        // The re-add is a new add, so it takes the new date rather than the one from the removed row (#902).
        assertEquals(20_000L, after.addedAtEpochMs)
    }

    @Test
    fun a_new_title_is_stamped_and_dirty() = runTest {
        repository.upsertSnapshot(details())

        val after = row()
        assertTrue(after.isDirty)
        assertEquals(10_000L, after.updatedAtEpochMs)
    }
}
