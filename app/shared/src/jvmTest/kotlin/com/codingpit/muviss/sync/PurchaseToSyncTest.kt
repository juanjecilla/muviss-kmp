@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.sync

import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.codingpit.muviss.core.billing.Entitlement
import com.codingpit.muviss.core.billing.SupabaseEntitlementProvider
import com.codingpit.muviss.core.common.AppDispatchers
import com.codingpit.muviss.core.database.MuvissDatabase
import com.codingpit.muviss.core.sync.EntitlementGate
import com.codingpit.muviss.core.sync.SyncBackendId
import com.codingpit.muviss.core.sync.SyncEngine
import com.codingpit.muviss.core.sync.SyncOutcome
import com.codingpit.muviss.core.sync.SyncSession
import com.codingpit.muviss.core.sync.SyncSessionStore
import com.codingpit.muviss.core.sync.supabase.createSupabaseSyncBackend
import com.codingpit.muviss.core.testing.FakeSupabaseServer
import com.codingpit.muviss.di.SyncBackendEntitlementSource
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * The whole paid path as the app wires it (`BillingSyncBridge`): the real
 * Supabase backend, adapted to billing's `EntitlementSource`, feeding the real
 * [SupabaseEntitlementProvider], which is the engine's [EntitlementGate] —
 * against a [FakeSupabaseServer] that enforces the `sync_until` claim. Built by
 * hand rather than from `appModules`, because two Koin graphs built from the
 * same module objects share their singletons, and the provider is one.
 *
 * #279's "done when": a purchase becomes a successful sync without a restart.
 */
class PurchaseToSyncTest {

    private val server = FakeSupabaseServer()
    private val clock = LambdaClock { 1_000L }
    private val database = MuvissDatabase(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { MuvissDatabase.Schema.synchronous().create(it) })

    private class Store(var session: SyncSession?) : SyncSessionStore {
        override suspend fun load() = session
        override suspend fun save(session: SyncSession) {
            this.session = session
        }
        override suspend fun clear() {
            session = null
        }
    }

    private val token = server.signUp("alice", entitled = false)
    private val backend = createSupabaseSyncBackend(
        // As the app's shared client is configured (`:core:network`): GoTrue's
        // typed bodies go through its content negotiation.
        client = HttpClient(server.engine) {
            install(ContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                        isLenient = true
                        explicitNulls = false
                    },
                )
            }
        },
        baseUrl = FAKE_PROJECT_URL,
        anonKey = "anon-key",
        sessionStore = Store(SyncSession(SyncBackendId.SUPABASE, "alice", "alice@example.com", token, "refresh-alice", null)),
        clock = clock,
    )
    private val provider = SupabaseEntitlementProvider(SyncBackendEntitlementSource(backend), clock)
    private fun TestScope.engine() = SyncEngine(
        backend = backend,
        database = database,
        dispatchers = object : AppDispatchers {
            override val default: CoroutineDispatcher = UnconfinedTestDispatcher(testScheduler)
            override val io: CoroutineDispatcher = default
        },
        clock = clock,
        entitlementGate = EntitlementGate { provider.entitlement.first().isEntitled },
    )

    @Test
    fun before_paying_the_engine_shows_the_paywall_and_after_paying_it_syncs_with_no_restart() = runTest {
        assertEquals(Entitlement.Inactive, provider.entitlement.first())
        assertEquals(SyncOutcome.NotEntitled, engine().syncNow())

        // The store says the purchase is done; the webhook lands a moment later.
        server.grantEntitlement("alice")
        val confirmed = provider.refreshAfterPurchase()

        assertEquals(Entitlement.Active, confirmed)
        assertNotNull(backend.syncGrantedUntil(), "the renewed token carries sync_until")
        assertIs<SyncOutcome.Success>(engine().syncNow())
    }

    @Test
    fun a_device_that_never_bought_syncs_once_its_user_paid_elsewhere() = runTest {
        // Desktop never sells. Its token predates the purchase made on a phone;
        // the row is what tells it, and the engine renews the token itself.
        server.grantEntitlement("alice")

        assertEquals(Entitlement.Active, provider.entitlement.first())
        assertIs<SyncOutcome.Success>(engine().syncNow())
        assertEquals(1, server.requests.count { it.path == "/auth/v1/token" })
    }
}
