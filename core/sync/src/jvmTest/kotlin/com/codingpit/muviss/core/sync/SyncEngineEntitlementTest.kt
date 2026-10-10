@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.core.sync

import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.codingpit.muviss.core.database.MuvissDatabase
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The engine's half of ADR 0019, over [FakeSyncBackend]: what it does with
 * credentials that grant nothing, with a refused write, and with "delete
 * account". The wire behaviour behind each is in `SyncEntitlementIntegrationTest`.
 */
class SyncEngineEntitlementTest {

    private val database = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { MuvissDatabase.Schema.synchronous().create(it) }.let { MuvissDatabase(it) }
    private val clock = FakeClock(1_000L)
    private val backend = FakeSyncBackend()
    private val engine = SyncEngine(backend, database, ImmediateDispatchers(UnconfinedTestDispatcher()), clock)

    private fun lastSyncedAt(): Long? = database.appSettingsQueries.selectSettings().executeAsOneOrNull()?.lastSyncedAtEpochMs

    @Test
    fun credentials_without_a_grant_are_renewed_once_and_a_renewal_that_carries_one_syncs() = runTest {
        backend.grantedUntil = null
        backend.grantAfterRefresh = Long.MAX_VALUE

        assertIs<SyncOutcome.Success>(engine.syncNow())
        assertEquals(1, backend.refreshes)
    }

    @Test
    fun credentials_that_still_grant_nothing_after_renewal_are_not_entitled_and_touch_nothing() = runTest {
        backend.grantedUntil = null

        assertEquals(SyncOutcome.NotEntitled, engine.syncNow())

        assertEquals(1, backend.refreshes)
        assertTrue(backend.pushes.isEmpty())
        assertTrue(backend.pullRequests.isEmpty(), "an unentitled pull is an empty 200; never ask for one")
        assertNull(lastSyncedAt())
        assertNull(database.syncStateQueries.selectState().executeAsOneOrNull()?.ownerAccountId, "not even the account is adopted")
    }

    @Test
    fun a_grant_in_the_past_is_no_grant() = runTest {
        backend.grantedUntil = 999L

        assertEquals(SyncOutcome.NotEntitled, engine.syncNow())
    }

    @Test
    fun a_live_grant_is_not_renewed() = runTest {
        assertIs<SyncOutcome.Success>(engine.syncNow())
        assertEquals(0, backend.refreshes, "renewing on every cycle would spend a refresh token per sync")
    }

    @Test
    fun a_renewal_that_fails_is_reported_as_the_failure_it_is() = runTest {
        backend.grantedUntil = null
        backend.refreshFailure = IOException("connection reset").let { RuntimeException(it) }

        val outcome = engine.syncNow()

        assertIs<SyncOutcome.Failed>(outcome)
        assertEquals("FAILED", database.syncStateQueries.selectState().executeAsOneOrNull()?.lastOutcome)
    }

    @Test
    fun a_refused_write_under_a_live_grant_is_a_failure_not_the_paywall() = runTest {
        backend.pushFailure = SyncWriteRefusedException(IllegalStateException("403 42501"))
        seedDirtyEntry()

        val outcome = engine.syncNow()

        assertIs<SyncOutcome.Failed>(outcome)
        assertEquals(1, backend.pushes.size)
    }

    @Test
    fun a_refused_write_once_the_grant_is_gone_is_the_paywall() = runTest {
        backend.grantedUntil = 5_000L
        backend.pushFailure = SyncWriteRefusedException(IllegalStateException("403 42501"))
        // The clock moves past the grant while the push is in flight; the
        // precheck ran at 1_000, so it is the refusal that has to be read as
        // the paywall.
        val engineWithLateClock = SyncEngine(
            object : SyncBackend by backend {
                override suspend fun push(changes: SyncChangeSet): Result<Unit> {
                    clock.advanceTo(5_001L)
                    return backend.push(changes)
                }
            },
            database,
            ImmediateDispatchers(UnconfinedTestDispatcher()),
            clock,
        )
        seedDirtyEntry()

        assertEquals(SyncOutcome.NotEntitled, engineWithLateClock.syncNow())
        assertNull(database.syncStateQueries.selectState().executeAsOneOrNull()?.lastOutcome, "the paywall is not recorded as a failure")
    }

    // --- Delete account ----------------------------------------------------

    @Test
    fun a_deleted_account_forgets_its_ownership_its_cursors_and_its_failure_but_keeps_the_library() = runTest {
        seedDirtyEntry()
        assertIs<SyncOutcome.Success>(engine.syncNow())
        database.syncStateQueries.recordFailure(error = "SERVER: down", attemptedAtEpochMs = 2_000L)
        database.syncCursorQueries.upsert("collectionEntry", 7L)

        val outcome = engine.deleteAccount()

        assertEquals(AccountDeletionOutcome.Deleted, outcome)
        assertEquals(1, backend.deletions)
        val state = database.syncStateQueries.selectState().executeAsOne()
        assertNull(state.ownerAccountId)
        assertNull(state.lastOutcome)
        assertTrue(database.syncCursorQueries.selectAll().executeAsList().isEmpty())
        assertEquals(1, database.collectionEntryQueries.selectAll().executeAsList().size)
    }

    @Test
    fun a_failed_deletion_says_why_and_changes_nothing() = runTest {
        assertIs<SyncOutcome.Success>(engine.syncNow())
        backend.deleteFailure = SyncSessionExpiredException()

        val outcome = engine.deleteAccount()

        assertEquals(AccountDeletionOutcome.Failed(SyncFailureReason.Unauthorised), outcome)
        assertEquals(FAKE_SESSION.userId, database.syncStateQueries.selectState().executeAsOne().ownerAccountId)
    }

    @Test
    fun there_is_nothing_to_delete_while_signed_out() = runTest {
        backend.setSession(null)

        assertEquals(AccountDeletionOutcome.NotSignedIn, engine.deleteAccount())
        assertEquals(0, backend.deletions)
    }

    private suspend fun seedDirtyEntry() {
        database.collectionEntryQueries.upsert(
            mediaId = "tmdb:movie:603",
            mediaType = "MOVIE",
            title = "The Matrix",
            posterUrl = null,
            releaseYear = 1999L,
            productionStatus = "RELEASED",
            totalEpisodes = 0L,
            airedEpisodes = 0L,
            favorite = false,
            genres = "",
            runtimeMinutes = null,
            addedAtEpochMs = 500L,
            updatedAtEpochMs = 900L,
            isDirty = true,
            deleted = false,
            notificationsMuted = false,
            rating = null,
            note = null,
            revisitWillingness = null,
            coWatchPinned = false,
        )
    }
}
