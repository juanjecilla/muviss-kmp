package com.codingpit.muviss.core.sync

import com.codingpit.muviss.core.testing.FakeSupabaseServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Defect 12: how a sync fails, and what a failure is allowed to take with it. */
class SyncEngineFailureTest {

    @Test
    fun cancelling_a_sync_cancels_it_instead_of_reporting_a_failed_outcome() = runTest {
        val server = FakeSupabaseServer()
        val device = TestDevice(server)
        device.saveEntry("tmdb:movie:1")
        val requestHeld = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        server.onRequest = {
            requestHeld.complete(Unit)
            release.await()
        }
        var outcome: SyncOutcome? = null
        val job = launch { outcome = device.sync() }
        requestHeld.await()

        job.cancel()
        job.join()
        release.complete(Unit)

        assertTrue(job.isCancelled)
        assertNull(outcome, "a cancelled sync must propagate the cancellation; a Failed outcome means it was swallowed")
    }

    @Test
    fun a_cancellation_raised_by_the_backend_propagates_instead_of_becoming_a_failed_outcome() = runTest {
        // Cancelling the *caller* hides the swallow (withContext re-throws on
        // the way out), so this raises it from underneath: a timeout or a
        // cancelled scope inside the backend is a CancellationException too,
        // and reporting it as "Sync failed" both lies to the profile screen
        // and, once failures are recorded, counts a cancellation as one.
        val device = TestDevice(FakeSupabaseServer())
        val backend = FakeSyncBackend().apply { pushFailure = CancellationException("scope was cancelled") }
        val engine = SyncEngine(backend, device.database, ImmediateDispatchers(UnconfinedTestDispatcher()), FakeClock(1_000L))
        device.saveEntry("tmdb:movie:1")

        assertFailsWith<CancellationException> { engine.syncNow() }
    }

    // --- token refresh --------------------------------------------------------

    private fun expiredDevice(server: FakeSupabaseServer): TestDevice {
        val device = TestDevice(server, startMillis = 5_000L)
        // Replace the session with one that has already expired, so the next request refreshes first.
        runBlocking { device.sessionStore.save(sessionFor("alice", expiresAtEpochMs = 1_000L)) }
        return device
    }

    private fun tokenRequests(request: FakeSupabaseServer.Request) = request.path == "/auth/v1/token"

    @Test
    fun a_network_error_while_refreshing_keeps_the_session() = runTest {
        val server = FakeSupabaseServer()
        val device = expiredDevice(server)
        server.dropConnection(matching = ::tokenRequests)

        val outcome = device.sync()

        assertTrue(outcome is SyncOutcome.Failed)
        assertFalse(device.sessionStore.cleared, "being offline is not a reason to forget who is signed in")
        assertNotNull(device.backend.session.first())
    }

    @Test
    fun a_server_error_while_refreshing_keeps_the_session() = runTest {
        val server = FakeSupabaseServer()
        val device = expiredDevice(server)
        server.failWith(503, matching = ::tokenRequests)

        device.sync()

        assertFalse(device.sessionStore.cleared)
        assertNotNull(device.backend.session.first())
    }

    @Test
    fun a_rejected_refresh_token_signs_the_user_out() = runTest {
        val server = FakeSupabaseServer()
        val device = expiredDevice(server)
        server.revokeRefreshToken("refresh-alice")

        device.sync()

        assertTrue(device.sessionStore.cleared, "GoTrue said the refresh token is dead; nothing else can recover it")
        assertNull(device.backend.session.first())
    }
}
