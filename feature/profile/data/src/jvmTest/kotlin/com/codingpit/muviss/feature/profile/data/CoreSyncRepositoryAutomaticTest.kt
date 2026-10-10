@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.profile.data

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.codingpit.muviss.core.billing.Entitlement
import com.codingpit.muviss.core.billing.EntitlementProvider
import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.core.common.AppDispatchers
import com.codingpit.muviss.core.database.MuvissDatabase
import com.codingpit.muviss.core.sync.AutomaticSyncSettings
import com.codingpit.muviss.core.sync.DeepLinkRedirectTarget
import com.codingpit.muviss.core.sync.OAuthProvider
import com.codingpit.muviss.core.sync.SignInFeedback
import com.codingpit.muviss.core.sync.SyncAvailability
import com.codingpit.muviss.core.sync.SyncBackend
import com.codingpit.muviss.core.sync.SyncBackendId
import com.codingpit.muviss.core.sync.SyncChangeSet
import com.codingpit.muviss.core.sync.SyncCursor
import com.codingpit.muviss.core.sync.SyncEngine
import com.codingpit.muviss.core.sync.SyncPage
import com.codingpit.muviss.core.sync.SyncSession
import com.codingpit.muviss.core.sync.SyncSessionExpiredException
import com.codingpit.muviss.core.sync.SyncTable
import com.codingpit.muviss.feature.profile.domain.AccountChangeChoice
import com.codingpit.muviss.feature.profile.domain.SyncAccountState
import com.codingpit.muviss.feature.profile.domain.SyncFailureKind
import com.codingpit.muviss.feature.profile.domain.SyncOutcomeSummary
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** EPIC 40's additions to [CoreSyncRepository]: the switch, the status line's inputs, typed failures and a session that dies. */
class CoreSyncRepositoryAutomaticTest {

    private class ImmediateDispatchers(d: CoroutineDispatcher) : AppDispatchers {
        override val default = d
        override val io = d
    }

    private class FixedClock : AppClock {
        override fun nowEpochMs(): Long = 1_000L
    }

    private class ScriptedBackend(session: SyncSession?) : SyncBackend {
        override val id = SyncBackendId.SUPABASE
        val sessionState = MutableStateFlow(session)
        override val session: Flow<SyncSession?> = sessionState
        var failure: Throwable? = null
        var pushes = 0

        override suspend fun signInAnonymously() = error("not used")
        override suspend fun beginOAuth(provider: OAuthProvider, redirectUri: String) = error("not used")
        override suspend fun completeOAuth(authCode: String) = error("not used")
        override suspend fun signOut() {
            sessionState.value = null
        }

        override suspend fun push(changes: SyncChangeSet): Result<Unit> {
            pushes++
            return failure?.let { Result.failure(it) } ?: Result.success(Unit)
        }

        override suspend fun pull(after: Map<SyncTable, SyncCursor>, onPage: suspend (SyncPage) -> Unit): Result<Unit> = failure?.let { Result.failure(it) } ?: Result.success(Unit)
    }

    private class Rig(scope: TestScope, backgroundAvailable: Boolean = true) {
        val flags = FakeFeatureFlags()
        val database: MuvissDatabase = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { MuvissDatabase.Schema.synchronous().create(it) }.let { MuvissDatabase(it) }
        val backend = ScriptedBackend(SyncSession(SyncBackendId.SUPABASE, "user-1", "person@example.com", "token", null, null))
        val clock = FixedClock()
        val engine = SyncEngine(
            backend,
            database,
            ImmediateDispatchers(UnconfinedTestDispatcher(scope.testScheduler)),
            clock,
            automatic = AutomaticSyncSettings(
                availability = object : SyncAvailability {
                    override fun isConfigured() = true
                    override fun isBackgroundAvailable() = backgroundAvailable
                },
                switch = flags.syncAutomatically,
            ),
        )
        val repository = CoreSyncRepository(
            availability = SyncAvailability { true },
            backend = backend,
            engine = engine,
            entitlements = object : EntitlementProvider {
                override val entitlement: Flow<Entitlement> = MutableStateFlow(Entitlement.Active)
                override suspend fun refresh() = Unit
            },
            signInFeedback = SignInFeedback(),
            redirectTarget = DeepLinkRedirectTarget(),
            flags = flags,
            coordinator = idleCoordinator(engine, clock, scope.backgroundScope),
        )
    }

    private suspend fun dirtyRow(database: MuvissDatabase) {
        database.collectionEntryQueries.upsert(
            "tmdb:movie:603", "MOVIE", "The Matrix", null, 1999L, "RELEASED", 0L, 0L, false, "", null, 500L, 900L, true, false, false, null, null, null, false,
        )
    }

    @Test
    fun the_switch_starts_off_and_round_trips_through_the_flag() = runTest {
        val rig = Rig(this)

        assertFalse(rig.repository.observeAutomaticSync().first())
        rig.repository.setAutomaticSync(true)
        assertTrue(rig.repository.observeAutomaticSync().first())
        rig.repository.setAutomaticSync(false)
        assertFalse(rig.repository.observeAutomaticSync().first())
    }

    @Test
    fun turning_it_on_asks_for_a_sync_straight_away() = runTest {
        val rig = Rig(this)
        assertNull(rig.engine.lastFinishedAtEpochMs)

        rig.repository.setAutomaticSync(true)
        runCurrent()

        assertNotNull(rig.engine.lastFinishedAtEpochMs, "the person just said they want the other devices' changes")
    }

    @Test
    fun turning_it_off_does_not_sync() = runTest {
        val rig = Rig(this)

        rig.repository.setAutomaticSync(false)
        runCurrent()

        assertNull(rig.engine.lastFinishedAtEpochMs)
        assertEquals(0, rig.backend.pushes)
    }

    @Test
    fun a_build_without_background_sync_reports_it_and_a_stored_switch_still_cannot_sync() = runTest {
        val rig = Rig(this, backgroundAvailable = false)
        dirtyRow(rig.database)

        rig.repository.setAutomaticSync(true)
        runCurrent()

        assertFalse(rig.repository.isBackgroundAvailable)
        assertEquals(0, rig.backend.pushes, "the engine, not the screen, refuses")
    }

    @Test
    fun a_failed_sync_is_typed_and_carries_no_exception_text() = runTest {
        val rig = Rig(this)
        dirtyRow(rig.database)
        rig.backend.failure = IllegalStateException("java.net.SocketException: Connection reset by 10.0.0.7")

        val outcome = rig.repository.syncNow()

        assertEquals(SyncOutcomeSummary.Failed(SyncFailureKind.Unknown), outcome)
    }

    @Test
    fun a_failure_is_remembered_for_the_status_line_and_cleared_by_success() = runTest {
        val rig = Rig(this)
        dirtyRow(rig.database)
        rig.backend.failure = IllegalStateException("boom")
        rig.repository.syncNow()

        val failed = rig.repository.observeSyncStatus().first()
        assertEquals(SyncFailureKind.Unknown, failed.lastFailure)
        assertEquals(1L, failed.pendingChanges)

        rig.backend.failure = null
        rig.repository.syncNow()
        val recovered = rig.repository.observeSyncStatus().first()
        assertNull(recovered.lastFailure)
        assertEquals(0L, recovered.pendingChanges)
        assertEquals(1_000L, recovered.lastSyncedAtEpochMs)
    }

    @Test
    fun a_session_that_dies_reads_as_expired_not_as_never_signed_in() = runTest {
        val rig = Rig(this)
        dirtyRow(rig.database)
        rig.backend.failure = SyncSessionExpiredException()
        rig.repository.syncNow()

        rig.backend.sessionState.value = null

        assertEquals(SyncAccountState.SessionExpired, rig.repository.observeAccount().first())
    }

    @Test
    fun signing_out_on_purpose_is_not_an_expired_session() = runTest {
        val rig = Rig(this)
        dirtyRow(rig.database)
        rig.backend.failure = SyncSessionExpiredException()
        rig.repository.syncNow()

        rig.repository.signOut()

        assertEquals(SyncAccountState.SignedOut, rig.repository.observeAccount().first())
    }

    @Test
    fun a_different_account_is_reported_in_the_status_and_nothing_moves() = runTest {
        val rig = Rig(this)
        dirtyRow(rig.database)
        rig.database.syncStateQueries.ensureRow()
        rig.database.syncStateQueries.setOwner("someone-else")

        assertTrue(rig.repository.observeSyncStatus().first().accountChanged)
        assertEquals(SyncOutcomeSummary.AccountChanged, rig.repository.syncNow())
        assertEquals(0, rig.backend.pushes)
    }

    @Test
    fun signing_out_keeps_the_local_library_and_whose_it_is() = runTest {
        val rig = Rig(this)
        dirtyRow(rig.database)
        rig.repository.syncNow()

        rig.repository.signOut()

        assertEquals(1, rig.database.collectionEntryQueries.selectAll().awaitAsList().size, "sign-out is not a wipe")
        assertEquals("user-1", rig.database.syncStateQueries.selectState().awaitAsOne().ownerAccountId)
    }

    @Test
    fun signing_back_in_as_someone_else_after_a_sign_out_asks_rather_than_syncs() = runTest {
        val rig = Rig(this)
        dirtyRow(rig.database)
        rig.repository.syncNow()
        rig.repository.signOut()
        val pushesBefore = rig.backend.pushes

        rig.backend.sessionState.value = SyncSession(SyncBackendId.SUPABASE, "user-2", "other@example.com", "token", null, null)

        assertTrue(rig.repository.observeSyncStatus().first().accountChanged)
        assertEquals(SyncOutcomeSummary.AccountChanged, rig.repository.syncNow())
        assertEquals(pushesBefore, rig.backend.pushes)
    }

    @Test
    fun replacing_takes_the_new_accounts_library_and_drops_this_devices() = runTest {
        val rig = Rig(this)
        dirtyRow(rig.database)
        rig.database.syncStateQueries.ensureRow()
        rig.database.syncStateQueries.setOwner("someone-else")

        val outcome = rig.repository.resolveAccountChange(AccountChangeChoice.ReplaceWithAccountLibrary)

        assertEquals(SyncOutcomeSummary.Success(syncedAtEpochMs = 1_000L), outcome)
        assertTrue(rig.database.collectionEntryQueries.selectAll().awaitAsList().isEmpty())
        assertEquals("user-1", rig.database.syncStateQueries.selectState().awaitAsOne().ownerAccountId)
        assertFalse(rig.repository.observeSyncStatus().first().accountChanged)
    }

    @Test
    fun adding_keeps_this_devices_library_and_sends_it() = runTest {
        val rig = Rig(this)
        dirtyRow(rig.database)
        rig.database.syncStateQueries.ensureRow()
        rig.database.syncStateQueries.setOwner("someone-else")

        val outcome = rig.repository.resolveAccountChange(AccountChangeChoice.AddDeviceLibraryToAccount)

        assertEquals(SyncOutcomeSummary.Success(syncedAtEpochMs = 1_000L), outcome)
        assertEquals(1, rig.database.collectionEntryQueries.selectAll().awaitAsList().size)
        assertTrue(rig.backend.pushes > 0)
        assertEquals("user-1", rig.database.syncStateQueries.selectState().awaitAsOne().ownerAccountId)
    }

    @Test
    fun resync_everything_runs_a_cycle_and_reports_it() = runTest {
        val rig = Rig(this)

        val outcome = rig.repository.resyncEverything()

        assertEquals(SyncOutcomeSummary.Success(syncedAtEpochMs = 1_000L), outcome)
    }
}
