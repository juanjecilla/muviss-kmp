@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.profile.data

import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.codingpit.muviss.core.billing.Entitlement
import com.codingpit.muviss.core.billing.EntitlementProvider
import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.core.common.AppDispatchers
import com.codingpit.muviss.core.database.MuvissDatabase
import com.codingpit.muviss.core.sync.EntitlementGate
import com.codingpit.muviss.core.sync.SyncAvailability
import com.codingpit.muviss.core.sync.SyncBackend
import com.codingpit.muviss.core.sync.SyncBackendId
import com.codingpit.muviss.core.sync.SyncChangeSet
import com.codingpit.muviss.core.sync.SyncEngine
import com.codingpit.muviss.core.sync.SyncSession
import com.codingpit.muviss.feature.profile.domain.SyncAccountState
import com.codingpit.muviss.feature.profile.domain.SyncOutcomeSummary
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The two gates the profile screen has to tell apart (ADR 0012): a build
 * without sync renders nothing, while a build with sync that the user has not
 * paid for renders the row and a way to buy it. Getting these the same way
 * round would either hide a purchasable feature or advertise one the app
 * cannot perform.
 */
class CoreSyncRepositoryGatingTest {

    private class ImmediateDispatchers(d: CoroutineDispatcher) : AppDispatchers {
        override val default = d
        override val io = d
    }

    private class FixedClock : AppClock {
        override fun nowEpochMs(): Long = 1_000L
    }

    private class StubBackend(session: SyncSession?) : SyncBackend {
        override val id = SyncBackendId.SUPABASE
        override val session: Flow<SyncSession?> = MutableStateFlow(session)
        override suspend fun signInAnonymously() = error("not used")
        override suspend fun requestEmailOtp(email: String) = Result.success(Unit)
        override suspend fun verifyEmailOtp(email: String, code: String) = error("not used")
        override suspend fun signOut() = Unit
        override suspend fun push(changes: SyncChangeSet) = Result.success(Unit)
        override suspend fun pull(sinceEpochMs: Long?) = Result.success(SyncChangeSet())
    }

    private class StubEntitlements(entitlement: Entitlement) : EntitlementProvider {
        override val entitlement: Flow<Entitlement> = MutableStateFlow(entitlement)
        override suspend fun refresh() = Unit
    }

    private val signedIn = SyncSession(
        backendId = SyncBackendId.SUPABASE,
        userId = "user-1",
        email = "person@example.com",
        accessToken = "token",
        refreshToken = null,
        expiresAtEpochMs = null,
    )

    private fun repository(
        configured: Boolean = true,
        entitlement: Entitlement = Entitlement.Active,
        session: SyncSession? = signedIn,
    ): CoreSyncRepository {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MuvissDatabase.Schema.synchronous().create(driver)
        val database = MuvissDatabase(driver)
        val backend = StubBackend(session)
        val gate = EntitlementGate { entitlement.isEntitled }
        return CoreSyncRepository(
            availability = SyncAvailability { configured },
            backend = backend,
            engine = SyncEngine(backend, database, ImmediateDispatchers(UnconfinedTestDispatcher()), FixedClock(), gate),
            entitlements = StubEntitlements(entitlement),
        )
    }

    @Test
    fun a_build_without_sync_reports_unavailable_whatever_the_entitlement_says() = runTest {
        val state = repository(configured = false, entitlement = Entitlement.Active).observeAccount().first()

        // SYNC_ENABLED or the Supabase keys are missing, so there is nothing to
        // unlock — offering a purchase here would sell something the binary
        // cannot do.
        assertEquals(SyncAccountState.Unavailable, state)
    }

    @Test
    fun an_unentitled_user_sees_a_locked_row_not_a_hidden_one() = runTest {
        val state = repository(entitlement = Entitlement.Inactive, session = null).observeAccount().first()

        assertEquals(SyncAccountState.Locked(email = null), state)
    }

    @Test
    fun a_lapsed_subscriber_keeps_a_way_out_of_their_account() = runTest {
        val state = repository(entitlement = Entitlement.Inactive, session = signedIn).observeAccount().first()

        // Locked carries the email so the screen can still offer sign-out;
        // without it a lapsed subscriber is trapped behind the paywall.
        assertEquals(SyncAccountState.Locked(email = "person@example.com"), state)
    }

    @Test
    fun an_entitled_signed_in_user_sees_their_account() = runTest {
        val state = repository(entitlement = Entitlement.Active, session = signedIn).observeAccount().first()

        assertEquals(SyncAccountState.SignedIn(email = "person@example.com"), state)
    }

    @Test
    fun an_unanswered_entitlement_locks_rather_than_granting() = runTest {
        val state = repository(entitlement = Entitlement.Unknown, session = signedIn).observeAccount().first()

        assertEquals(SyncAccountState.Locked(email = "person@example.com"), state)
    }

    @Test
    fun syncing_while_unentitled_reports_it_instead_of_silently_doing_nothing() = runTest {
        // The button can still be on screen when an entitlement lapses mid-session.
        assertEquals(SyncOutcomeSummary.NotEntitled, repository(entitlement = Entitlement.Inactive).syncNow())
    }

    @Test
    fun syncing_while_entitled_runs_a_cycle() = runTest {
        val outcome = repository(entitlement = Entitlement.Active).syncNow()

        assertEquals(SyncOutcomeSummary.Success(syncedAtEpochMs = 1_000L), outcome)
    }
}
