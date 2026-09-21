@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.core.sync

import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.codingpit.muviss.core.database.MuvissDatabase
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The engine's own bookkeeping (EPIC 39): where each table's pull stopped, whose
 * library this is, and what the last attempt left behind. Runs against
 * [FakeSyncBackend], which hands the engine exactly the pages and failures a
 * test dictates; anything about the wire is in the suites that use
 * `FakeSupabaseServer`.
 */
class SyncEngineCursorAndOwnerTest {

    private lateinit var database: MuvissDatabase
    private lateinit var clock: FakeClock
    private lateinit var backend: FakeSyncBackend
    private lateinit var engine: SyncEngine

    @BeforeTest
    fun setUp() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MuvissDatabase.Schema.synchronous().create(driver)
        database = MuvissDatabase(driver)
        clock = FakeClock(1_000L)
        backend = FakeSyncBackend()
        engine = SyncEngine(backend, database, ImmediateDispatchers(UnconfinedTestDispatcher()), clock)
    }

    private fun remoteEntry(mediaId: String, updatedAt: Long = 2_000L, favorite: Boolean = false, title: String = "Remote title") = CollectionEntryChange(
        mediaId = mediaId, mediaType = "MOVIE", title = title, posterUrl = null, releaseYear = 1999, productionStatus = "RELEASED",
        totalEpisodes = 1, airedEpisodes = 1, favorite = favorite, genres = "", runtimeMinutes = null, addedAtEpochMs = 500L,
        updatedAtEpochMs = updatedAt, deleted = false, rating = null, note = null,
    )

    private fun remoteTick(episode: Int, updatedAt: Long = 2_000L) = EpisodeProgressChange(
        episodeId = "tmdb:tv:1/1/$episode",
        mediaId = "tmdb:tv:1",
        seasonNumber = 1,
        episodeNumber = episode,
        seen = true,
        updatedAtEpochMs = updatedAt,
    )

    @Suppress("LongParameterList") // a row builder: each column is a named default a test may override
    private suspend fun localEntry(mediaId: String, updatedAt: Long, isDirty: Boolean, title: String = "Local title", favorite: Boolean = false, deleted: Boolean = false) {
        database.collectionEntryQueries.upsert(
            mediaId, "MOVIE", title, null, 1999L, "RELEASED", 1L, 1L, favorite, "", null, 500L, updatedAt, isDirty, deleted, false, null, null,
        )
    }

    // `_`-prefixed rows are not cursors (the weekly-reconcile stamp, EPIC 40).
    private fun cursors() = database.syncCursorQueries.selectAll().executeAsList().filterNot { it.tableName.startsWith("_") }.associate { it.tableName to it.seq }

    private fun localEntries() = database.collectionEntryQueries.selectAll().executeAsList()

    private fun state() = database.syncStateQueries.selectState().executeAsOne()

    // --- cursors --------------------------------------------------------------

    @Test
    fun each_table_keeps_its_own_cursor() = runTest {
        backend.seedRemoteCollectionEntry(remoteEntry("tmdb:movie:1"))
        backend.seedRemoteCollectionEntry(remoteEntry("tmdb:movie:2"))
        backend.seedRemoteEpisodeProgress(remoteTick(1))

        engine.syncNow()

        assertEquals(mapOf("collectionEntry" to 2L, "episodeProgress" to 3L), cursors(), "a table that returned nothing has no cursor of its own")
    }

    @Test
    fun the_next_pull_resumes_from_the_stored_cursors() = runTest {
        backend.seedRemoteCollectionEntry(remoteEntry("tmdb:movie:1"))
        engine.syncNow()

        engine.syncNow()

        assertEquals(emptyMap(), backend.pullRequests.first(), "the first pull asks for everything")
        assertEquals(mapOf(SyncTable.COLLECTION_ENTRY to SyncCursor(1L)), backend.pullRequests.last())
    }

    @Test
    fun an_interrupted_pull_moves_no_cursor_at_all() = runTest {
        backend.pullPageSize = 2
        (1..5).forEach { backend.seedRemoteEpisodeProgress(remoteTick(it)) }
        backend.seedRemoteCollectionEntry(remoteEntry("tmdb:tv:1"))
        // The collection table drains, then the progress table dies on its second page.
        backend.failPullAfterPages = 2

        val outcome = engine.syncNow()

        assertTrue(outcome is SyncOutcome.Failed)
        assertEquals(emptyMap(), cursors(), "a cursor that moved past rows the retry would then skip is exactly the bug being fixed")
    }

    @Test
    fun retrying_an_interrupted_pull_converges_to_the_same_state_as_one_clean_pull() = runTest {
        backend.pullPageSize = 2
        (1..5).forEach { backend.seedRemoteEpisodeProgress(remoteTick(it)) }
        backend.failPullAfterPages = 2
        engine.syncNow()
        val afterInterruption = database.episodeProgressQueries.selectAll().executeAsList().size
        assertTrue(afterInterruption in 1..4, "some pages were applied before the interruption: $afterInterruption")

        backend.failPullAfterPages = null
        val retried = engine.syncNow()

        assertTrue(retried is SyncOutcome.Success)
        assertEquals(5, database.episodeProgressQueries.selectAll().executeAsList().size)
        assertEquals(mapOf("episodeProgress" to 5L), cursors())
    }

    @Test
    fun a_push_that_fails_leaves_the_cursors_and_the_dirty_rows_alone() = runTest {
        localEntry("tmdb:movie:1", updatedAt = 1_500L, isDirty = true)
        backend.pushFailure = IllegalStateException("offline")

        engine.syncNow()

        assertEquals(emptyMap(), cursors())
        assertTrue(database.collectionEntryQueries.selectById("tmdb:movie:1").executeAsOne().isDirty)
    }

    @Test
    fun a_failure_partway_through_a_chunked_push_keeps_the_chunks_that_landed_clean() = runTest {
        repeat(PUSH_CHUNK_ROWS + 10) { index ->
            database.episodeProgressQueries.upsert("tmdb:tv:1/1/${index + 1}", "tmdb:tv:1", 1L, index + 1L, true, 1_000L + index, true)
        }
        backend.failPushOnCall = 2

        val outcome = engine.syncNow()

        assertTrue(outcome is SyncOutcome.Failed)
        val dirty = database.episodeProgressQueries.selectDirty().executeAsList().size
        assertEquals(10, dirty, "the first 500 were sent and cleared; only the failed chunk is still waiting")
    }

    @Test
    fun resyncEverything_forgets_the_cursors_and_sends_every_row_again() = runTest {
        backend.seedRemoteCollectionEntry(remoteEntry("tmdb:movie:1"))
        engine.syncNow()
        assertEquals(mapOf("collectionEntry" to 1L), cursors())
        backend.pushes.clear()

        val outcome = engine.resyncEverything()

        assertTrue(outcome is SyncOutcome.Success)
        assertEquals(emptyMap(), backend.pullRequests.last(), "a full pull again")
        assertEquals(1, backend.pushes.sumOf { it.collectionEntries.size }, "and the whole library pushed, clean rows included")
    }

    // --- whose library this is ------------------------------------------------

    @Test
    fun the_first_sync_adopts_the_signed_in_account_and_pushes_every_local_row() = runTest {
        localEntry("tmdb:movie:1", updatedAt = 1_500L, isDirty = false)
        assertNull(database.syncStateQueries.selectState().executeAsOneOrNull())

        val outcome = engine.syncNow()

        assertEquals(1, (outcome as SyncOutcome.Success).pushedCount, "a clean row from before this table existed has still never been sent")
        assertEquals(FAKE_SESSION.userId, state().ownerAccountId)
    }

    @Test
    fun once_owned_a_clean_row_stays_unsent() = runTest {
        engine.syncNow()
        localEntry("tmdb:movie:1", updatedAt = 1_500L, isDirty = false)

        val outcome = engine.syncNow()

        assertEquals(0, (outcome as SyncOutcome.Success).pushedCount)
    }

    @Test
    fun a_different_account_stops_the_cycle_and_touches_nothing() = runTest {
        engine.syncNow()
        localEntry("tmdb:movie:1", updatedAt = 1_500L, isDirty = true)
        backend.pushes.clear()
        backend.pullRequests.clear()
        backend.setSession(FAKE_SESSION.copy(userId = "someone-else"))

        val outcome = engine.syncNow()

        assertEquals(SyncOutcome.AccountChanged(previousAccountId = FAKE_SESSION.userId, currentAccountId = "someone-else"), outcome)
        assertTrue(backend.pushes.isEmpty(), "the first person's library must not be sent into the second person's account")
        assertTrue(backend.pullRequests.isEmpty())
        assertTrue(database.collectionEntryQueries.selectById("tmdb:movie:1").executeAsOne().isDirty)
        assertEquals(FAKE_SESSION.userId, state().ownerAccountId, "and nothing is adopted until someone decides")
    }

    @Test
    fun discarding_local_data_wipes_the_library_resets_the_cursors_and_takes_the_new_account() = runTest {
        backend.seedRemoteCollectionEntry(remoteEntry("tmdb:movie:1"))
        engine.syncNow()
        localEntry("tmdb:movie:2", updatedAt = 1_500L, isDirty = true)
        database.episodeProgressQueries.upsert("tmdb:tv:1/1/1", "tmdb:tv:1", 1L, 1L, true, 1_500L, true)
        backend.setSession(FAKE_SESSION.copy(userId = "someone-else"))
        val freshBackendRows = FakeSyncBackend(session = FAKE_SESSION.copy(userId = "someone-else"))
        val switched = SyncEngine(freshBackendRows, database, ImmediateDispatchers(UnconfinedTestDispatcher()), clock)
        freshBackendRows.seedRemoteCollectionEntry(remoteEntry("tmdb:movie:9", title = "Theirs"))

        val outcome = switched.resolveAccountChange(AccountChangeResolution.DiscardLocalData)

        assertTrue(outcome is SyncOutcome.Success)
        assertEquals(listOf("tmdb:movie:9"), localEntries().map { it.mediaId }, "only the new account's library remains")
        assertTrue(database.episodeProgressQueries.selectAll().executeAsList().isEmpty())
        assertEquals("someone-else", state().ownerAccountId)
        assertTrue(freshBackendRows.pushes.all { it.isEmpty }, "nothing of the first account was sent")
        assertEquals(emptyMap(), freshBackendRows.pullRequests.single(), "and the new account is pulled from the start")
    }

    @Test
    fun merging_keeps_the_local_library_and_sends_all_of_it_to_the_new_account() = runTest {
        engine.syncNow()
        localEntry("tmdb:movie:1", updatedAt = 1_500L, isDirty = false)
        val other = FakeSyncBackend(session = FAKE_SESSION.copy(userId = "someone-else"))
        val switched = SyncEngine(other, database, ImmediateDispatchers(UnconfinedTestDispatcher()), clock)

        val outcome = switched.resolveAccountChange(AccountChangeResolution.MergeLocalDataIntoAccount)

        assertTrue(outcome is SyncOutcome.Success)
        assertEquals(1, other.pushes.sumOf { it.collectionEntries.size })
        assertEquals("someone-else", state().ownerAccountId)
    }

    @Test
    fun resolving_when_the_owner_already_matches_just_syncs() = runTest {
        engine.syncNow()
        localEntry("tmdb:movie:1", updatedAt = 1_500L, isDirty = true)

        val outcome = engine.resolveAccountChange(AccountChangeResolution.DiscardLocalData)

        assertTrue(outcome is SyncOutcome.Success)
        assertEquals(1, localEntries().size, "nothing to resolve, so nothing was wiped")
    }

    // --- what the last attempt left behind ------------------------------------

    @Test
    fun a_failed_attempt_is_recorded_and_counted() = runTest {
        backend.pullFailure = IllegalStateException("server said no")

        engine.syncNow()
        engine.syncNow()

        val state = state()
        assertEquals("FAILED", state.lastOutcome)
        assertEquals("Unknown: server said no", state.lastError, "the reason token, then the diagnostic text")
        assertEquals(2L, state.consecutiveFailures)
        assertEquals(1_000L, state.lastAttemptAtEpochMs)
    }

    @Test
    fun a_success_clears_the_failure_record() = runTest {
        backend.pullFailure = IllegalStateException("server said no")
        engine.syncNow()
        backend.pullFailure = null
        clock.advanceTo(5_000L)

        engine.syncNow()

        val state = state()
        assertEquals("SUCCESS", state.lastOutcome)
        assertNull(state.lastError)
        assertEquals(0L, state.consecutiveFailures)
        assertEquals(5_000L, state.lastAttemptAtEpochMs)
    }

    @Test
    fun a_cancelled_sync_is_not_recorded_as_a_failure() = runTest {
        engine.syncNow()
        backend.pushFailure = kotlin.coroutines.cancellation.CancellationException("scope cancelled")
        localEntry("tmdb:movie:1", updatedAt = 1_500L, isDirty = true)

        runCatching { engine.syncNow() }

        assertEquals(0L, state().consecutiveFailures)
        assertEquals("SUCCESS", state().lastOutcome, "the earlier real outcome is still the last one")
    }

    // --- who wins a merge -----------------------------------------------------

    @Test
    fun a_clean_local_row_takes_the_servers_version_even_when_the_server_stamp_is_older() = runTest {
        // A fast device's row was clamped on the way in, so the server holds a
        // lower stamp than the device kept. The device has nothing unsent, so
        // there is no contest: the server's copy is the truth.
        engine.syncNow()
        localEntry("tmdb:movie:1", updatedAt = 9_000_000L, isDirty = false, favorite = false)
        backend.seedRemoteCollectionEntry(remoteEntry("tmdb:movie:1", updatedAt = 2_000L, favorite = true))

        engine.syncNow()

        assertTrue(database.collectionEntryQueries.selectById("tmdb:movie:1").executeAsOne().favorite)
    }

    @Test
    fun a_dirty_local_row_newer_than_the_pulled_one_keeps_its_edit() = runTest {
        engine.syncNow()
        localEntry("tmdb:movie:1", updatedAt = 5_000L, isDirty = true, favorite = true)
        backend.seedRemoteCollectionEntry(remoteEntry("tmdb:movie:1", updatedAt = 2_000L, favorite = false))
        backend.pushFailure = IllegalStateException("offline, so the edit cannot have been sent")

        engine.syncNow()

        val row = database.collectionEntryQueries.selectById("tmdb:movie:1").executeAsOne()
        assertTrue(row.favorite)
        assertTrue(row.isDirty)
    }

    @Test
    fun a_pulled_row_that_wins_takes_the_user_fields_and_keeps_the_local_snapshot() = runTest {
        engine.syncNow()
        localEntry("tmdb:movie:1", updatedAt = 1_000L, isDirty = false, title = "Freshly refreshed title")
        backend.seedRemoteCollectionEntry(remoteEntry("tmdb:movie:1", updatedAt = 3_000L, favorite = true, title = "Stale title from another device"))

        engine.syncNow()

        val row = database.collectionEntryQueries.selectById("tmdb:movie:1").executeAsOne()
        assertTrue(row.favorite, "the user field came from the winning row")
        assertEquals("Freshly refreshed title", row.title, "the snapshot field did not")
        assertEquals(3_000L, row.updatedAtEpochMs)
        assertFalse(row.isDirty)
    }

    @Test
    fun a_pulled_row_with_no_local_counterpart_is_taken_whole() = runTest {
        backend.seedRemoteCollectionEntry(remoteEntry("tmdb:movie:1", title = "Only copy"))

        engine.syncNow()

        assertEquals("Only copy", assertNotNull(database.collectionEntryQueries.selectById("tmdb:movie:1").executeAsOneOrNull()).title)
    }

    @Test
    fun a_local_tombstone_is_not_revived_by_a_stale_pulled_row_while_it_is_unsent() = runTest {
        engine.syncNow()
        localEntry("tmdb:movie:1", updatedAt = 9_000L, isDirty = true, deleted = true)
        backend.seedRemoteCollectionEntry(remoteEntry("tmdb:movie:1", updatedAt = 1_000L))
        backend.pushFailure = IllegalStateException("offline")

        engine.syncNow()

        assertTrue(database.collectionEntryQueries.selectById("tmdb:movie:1").executeAsOne().deleted)
    }

    // --- pages ------------------------------------------------------------------

    @Test
    fun a_page_larger_than_the_sql_variable_limit_still_reconciles_every_title() = runTest {
        // 450 distinct titles in one page is more than one IN-list chunk.
        val perTitle = (1..450).map { EpisodeProgressChange("tmdb:tv:$it/1/1", "tmdb:tv:$it", 1, 1, true, 2_000L) }
        perTitle.forEach { backend.seedRemoteEpisodeProgress(it) }
        backend.pullPageSize = 500

        val outcome = engine.syncNow()

        assertTrue(outcome is SyncOutcome.Success)
        assertEquals(450, database.episodePlayQueries.selectAll().executeAsList().size, "every tick got its viewing, across both chunks")
    }
}
