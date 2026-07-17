@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.core.sync

import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.codingpit.muviss.core.database.MuvissDatabase
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncEngineTest {

    private lateinit var database: MuvissDatabase
    private lateinit var clock: FakeClock
    private lateinit var backend: FakeSyncBackend
    private lateinit var engine: SyncEngine

    private fun newDatabase(): MuvissDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MuvissDatabase.Schema.synchronous().create(driver)
        return MuvissDatabase(driver)
    }

    @BeforeTest
    fun setUp() {
        database = newDatabase()
        clock = FakeClock(1_000L)
        backend = FakeSyncBackend()
        engine = SyncEngine(backend, database, ImmediateDispatchers(UnconfinedTestDispatcher()), clock)
    }

    private suspend fun seedLocalCollectionEntry(
        favorite: Boolean = false,
        deleted: Boolean = false,
        updatedAtEpochMs: Long = clock.nowEpochMs(),
        isDirty: Boolean = true,
    ) {
        database.collectionEntryQueries.upsert(
            mediaId = SEEDED_MEDIA_ID,
            mediaType = "MOVIE",
            title = "The Matrix",
            posterUrl = null,
            releaseYear = 1999L,
            productionStatus = "RELEASED",
            totalEpisodes = 0L,
            airedEpisodes = 0L,
            favorite = favorite,
            genres = "",
            runtimeMinutes = null,
            addedAtEpochMs = 500L,
            updatedAtEpochMs = updatedAtEpochMs,
            isDirty = isDirty,
            deleted = deleted,
            notificationsMuted = false,
            rating = null,
            note = null,
        )
    }

    private fun remoteChange(
        mediaId: String = SEEDED_MEDIA_ID,
        title: String = "The Matrix",
        favorite: Boolean = false,
        deleted: Boolean = false,
        updatedAtEpochMs: Long,
    ) = CollectionEntryChange(
        mediaId = mediaId,
        mediaType = "MOVIE",
        title = title,
        posterUrl = null,
        releaseYear = 1999,
        productionStatus = "RELEASED",
        totalEpisodes = 0,
        airedEpisodes = 0,
        favorite = favorite,
        genres = "",
        runtimeMinutes = null,
        addedAtEpochMs = 500L,
        updatedAtEpochMs = updatedAtEpochMs,
        deleted = deleted,
        rating = null,
        note = null,
    )

    @Test
    fun syncNow_returns_NotSignedIn_and_does_nothing_when_no_session() = runTest {
        backend.setSession(null)
        seedLocalCollectionEntry()

        val outcome = engine.syncNow()

        assertEquals(SyncOutcome.NotSignedIn, outcome)
        val local = database.collectionEntryQueries.selectById(SEEDED_MEDIA_ID).awaitAsOneOrNull()
        assertTrue(local!!.isDirty) // untouched — never pushed
    }

    @Test
    fun syncNow_pushes_dirty_rows_and_clears_isDirty() = runTest {
        seedLocalCollectionEntry(isDirty = true)

        val outcome = engine.syncNow()

        assertTrue(outcome is SyncOutcome.Success)
        val local = database.collectionEntryQueries.selectById(SEEDED_MEDIA_ID).awaitAsOneOrNull()
        assertFalse(local!!.isDirty)
    }

    @Test
    fun syncNow_pulls_a_remote_row_into_an_empty_local_table() = runTest {
        backend.seedRemoteCollectionEntry(remoteChange(title = "The Matrix Reloaded", updatedAtEpochMs = 2_000L))

        engine.syncNow()

        val local = database.collectionEntryQueries.selectById(SEEDED_MEDIA_ID).awaitAsOneOrNull()
        assertEquals("The Matrix Reloaded", local?.title)
        assertFalse(local!!.isDirty) // pulled rows are clean, not re-flagged dirty
    }

    @Test
    fun syncNow_pull_applies_last_write_wins_when_remote_is_newer() = runTest {
        seedLocalCollectionEntry(favorite = false, updatedAtEpochMs = 1_000L, isDirty = false)
        backend.seedRemoteCollectionEntry(remoteChange(favorite = true, updatedAtEpochMs = 2_000L))

        engine.syncNow()

        val local = database.collectionEntryQueries.selectById(SEEDED_MEDIA_ID).awaitAsOneOrNull()
        assertTrue(local!!.favorite)
    }

    @Test
    fun syncNow_pull_keeps_the_local_row_when_remote_is_older() = runTest {
        seedLocalCollectionEntry(favorite = true, updatedAtEpochMs = 5_000L, isDirty = false)
        backend.seedRemoteCollectionEntry(remoteChange(favorite = false, updatedAtEpochMs = 1_000L))

        engine.syncNow()

        val local = database.collectionEntryQueries.selectById(SEEDED_MEDIA_ID).awaitAsOneOrNull()
        assertTrue(local!!.favorite) // local (newer) wins, remote's stale value never applied
    }

    @Test
    fun syncNow_propagates_a_remote_tombstone_when_it_is_newer() = runTest {
        seedLocalCollectionEntry(deleted = false, updatedAtEpochMs = 1_000L, isDirty = false)
        backend.seedRemoteCollectionEntry(remoteChange(deleted = true, updatedAtEpochMs = 2_000L))

        engine.syncNow()

        val local = database.collectionEntryQueries.selectById(SEEDED_MEDIA_ID).awaitAsOneOrNull()
        assertTrue(local!!.deleted)
    }

    @Test
    fun syncNow_never_resurrects_a_local_tombstone_from_a_stale_remote_row() = runTest {
        // Locally deleted *after* the remote's last known (non-deleted) state.
        seedLocalCollectionEntry(deleted = true, updatedAtEpochMs = 9_000L, isDirty = false)
        backend.seedRemoteCollectionEntry(remoteChange(deleted = false, updatedAtEpochMs = 1_000L))

        engine.syncNow()

        val local = database.collectionEntryQueries.selectById(SEEDED_MEDIA_ID).awaitAsOneOrNull()
        assertTrue(local!!.deleted, "a stale, older remote row must never undelete a newer local tombstone")
    }

    @Test
    fun syncNow_persists_lastSyncedAt() = runTest {
        database.appSettingsQueries.ensureRow()
        assertNull(database.appSettingsQueries.selectSettings().awaitAsOneOrNull()?.lastSyncedAtEpochMs)
        clock.advanceTo(42_000L)

        engine.syncNow()

        assertEquals(42_000L, database.appSettingsQueries.selectSettings().awaitAsOneOrNull()?.lastSyncedAtEpochMs)
    }

    @Test
    fun syncNow_returns_Failed_when_the_backend_push_errors_and_leaves_rows_dirty() = runTest {
        seedLocalCollectionEntry(isDirty = true)
        backend.pushFailure = IllegalStateException("network down")

        val outcome = engine.syncNow()

        assertTrue(outcome is SyncOutcome.Failed)
        val local = database.collectionEntryQueries.selectById(SEEDED_MEDIA_ID).awaitAsOneOrNull()
        assertTrue(local!!.isDirty, "a failed push must not clear isDirty, or the edit would be lost")
    }

    @Test
    fun two_devices_converge_to_the_same_state_after_offline_edits_on_both() = runTest {
        val sharedBackend = FakeSyncBackend()

        val deviceADb = newDatabase()
        val deviceAClock = FakeClock(1_000L)
        val deviceAEngine = SyncEngine(sharedBackend, deviceADb, ImmediateDispatchers(UnconfinedTestDispatcher()), deviceAClock)

        val deviceBDb = newDatabase()
        val deviceBClock = FakeClock(1_000L)
        val deviceBEngine = SyncEngine(sharedBackend, deviceBDb, ImmediateDispatchers(UnconfinedTestDispatcher()), deviceBClock)

        // Both devices save the same title while offline from each other.
        deviceADb.collectionEntryQueries.upsert(
            mediaId = "tmdb:tv:1399", mediaType = "TV", title = "Game of Thrones", posterUrl = null,
            releaseYear = 2011L, productionStatus = "ENDED", totalEpisodes = 73L, airedEpisodes = 73L,
            favorite = false, genres = "", runtimeMinutes = null, addedAtEpochMs = 1_000L, updatedAtEpochMs = 1_000L,
            isDirty = true, deleted = false, notificationsMuted = false, rating = null, note = null,
        )
        deviceBDb.collectionEntryQueries.upsert(
            mediaId = "tmdb:tv:1399", mediaType = "TV", title = "Game of Thrones", posterUrl = null,
            releaseYear = 2011L, productionStatus = "ENDED", totalEpisodes = 73L, airedEpisodes = 73L,
            favorite = false, genres = "", runtimeMinutes = null, addedAtEpochMs = 1_000L, updatedAtEpochMs = 1_000L,
            isDirty = true, deleted = false, notificationsMuted = false, rating = null, note = null,
        )

        // A syncs first (pushes its base snapshot).
        deviceAEngine.syncNow()
        deviceBEngine.syncNow()

        // Now both devices go offline and each favorites the show independently — B's edit is the later one.
        deviceAClock.advanceTo(5_000L)
        deviceADb.collectionEntryQueries.setFavorite(favorite = true, now = 5_000L, mediaId = "tmdb:tv:1399")

        deviceBClock.advanceTo(9_000L)
        deviceBDb.collectionEntryQueries.setFavorite(favorite = false, now = 9_000L, mediaId = "tmdb:tv:1399")
        deviceBDb.collectionEntryQueries.setRating(rating = 9L, now = 9_000L, mediaId = "tmdb:tv:1399")

        // A syncs (pushes its favorite=true@5000), then B syncs (pushes its own row — the fake backend's
        // server-side LWW guard keeps whichever push carries the higher updatedAtEpochMs, deterministic
        // regardless of arrival order — see FakeSyncBackend's KDoc).
        deviceAEngine.syncNow()
        deviceBEngine.syncNow()
        // A syncs again to pull B's newer write.
        deviceAEngine.syncNow()

        val finalA = deviceADb.collectionEntryQueries.selectById("tmdb:tv:1399").awaitAsOneOrNull()
        val finalB = deviceBDb.collectionEntryQueries.selectById("tmdb:tv:1399").awaitAsOneOrNull()

        // B's edit (updatedAtEpochMs = 9000) is the later write, so it wins on both devices.
        assertEquals(9L, finalA?.rating)
        assertFalse(finalA!!.favorite)
        assertEquals(finalA.rating, finalB?.rating)
        assertEquals(finalA.favorite, finalB?.favorite)
        assertEquals(finalA.updatedAtEpochMs, finalB?.updatedAtEpochMs)
    }

    private companion object {
        const val SEEDED_MEDIA_ID = "tmdb:movie:603"
    }
}
