@file:OptIn(ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.sync

import app.cash.turbine.test
import com.codingpit.muviss.core.billing.Entitlement
import com.codingpit.muviss.core.sync.SyncOutcome
import com.codingpit.muviss.core.testing.FakeSupabaseServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private const val CLOCK_BASE = 1_800_000_000_000L

/**
 * Automatic sync end to end, through everything a real install has except the
 * network and the store: the real `appModules` graph, an in-memory database,
 * the real [com.codingpit.muviss.core.sync.SyncEngine] and Supabase backend
 * over [FakeSupabaseServer], and time that only moves when a test moves it.
 *
 * These are the tests that would have caught ADR 0018's warning coming true:
 * each one that says "never reaches the server" reads the *server's* request
 * log, not a return value, so a gate held only by the UI would still fail it.
 */
class AutomaticSyncIntegrationTest {

    private val apps = mutableListOf<SyncApp>()

    @AfterTest
    fun tearDown() = apps.forEach { it.close() }

    private fun TestScope.newApp(
        server: FakeSupabaseServer,
        userId: String = "alice",
        entitlement: Entitlement = Entitlement.Active,
        backgroundAvailable: Boolean = true,
    ) = SyncApp(
        server = server,
        dispatcher = UnconfinedTestDispatcher(testScheduler),
        clock = LambdaClock { CLOCK_BASE + testScheduler.currentTime },
        userId = userId,
        entitlement = entitlement,
        backgroundAvailable = backgroundAvailable,
    ).also { apps += it }

    /**
     * The mock engine answers on real threads while time is virtual, so waiting
     * for "the request arrived" means letting real time pass a few milliseconds
     * at a time between draining the scheduler.
     */
    private suspend fun TestScope.settle(maxWaitMs: Long = 5_000, until: () -> Boolean) {
        var waited = 0L
        while (!until() && waited < maxWaitMs) {
            runCurrent()
            // Blocking on purpose: suspending here would let runTest auto-advance virtual time.
            Thread.sleep(10)
            waited += 10
        }
        runCurrent()
    }

    /** Lets everything already due run and any real-thread reply land, for tests that assert something did NOT happen. */
    private suspend fun TestScope.quiesce() = settle(maxWaitMs = 300) { false }

    private fun FakeSupabaseServer.upserts() = requests.count { it.isUpsert }

    // --- the switch ------------------------------------------------------------

    @Test
    fun with_the_switch_off_a_local_write_is_never_sent_and_manual_sync_still_works() = runTest {
        val server = FakeSupabaseServer()
        val app = newApp(server)
        app.signIn()
        app.start()
        runCurrent()

        app.addMovie()
        advanceTimeBy(10.minutes)
        quiesce()
        assertEquals(0, server.upserts(), "the default is off: nothing leaves the device by itself")

        val outcome = app.engine.syncNow()

        assertTrue(outcome is SyncOutcome.Success, "$outcome")
        assertEquals(1, server.rows("collection_entry", "alice").size, "and a tap on Sync now still works")
    }

    @Test
    fun with_the_switch_off_a_foreground_does_not_touch_the_server_either() = runTest {
        val server = FakeSupabaseServer()
        val app = newApp(server)
        app.signIn()
        app.start()

        // What MuvissApp's AutoSyncOnForeground does on ON_START.
        app.coordinator.onForeground()
        quiesce()

        assertEquals(emptyList(), server.requests, "the hook that used to sync unconditionally is gated inside the engine")
    }

    @Test
    fun with_the_switch_on_a_write_is_sent_within_the_debounce_window() = runTest {
        val server = FakeSupabaseServer()
        val app = newApp(server)
        app.signIn()
        app.flags.setSyncAutomatically(true)
        app.start()
        runCurrent()

        app.addMovie()
        advanceTimeBy(4.seconds)
        quiesce()
        assertEquals(0, server.upserts(), "not before the 5 s debounce")

        advanceTimeBy(2.seconds)
        settle { server.rows("collection_entry", "alice").isNotEmpty() }

        settle { app.database.syncStateQueries.countDirtyRows().executeAsOne() == 0L }
        assertEquals(1, server.rows("collection_entry", "alice").size)
        assertEquals(0L, app.engine.observePendingChanges().first(), "and the device now has nothing waiting")
    }

    @Test
    fun fifty_writes_in_a_burst_are_one_push() = runTest {
        val server = FakeSupabaseServer()
        val app = newApp(server)
        app.signIn()
        app.flags.setSyncAutomatically(true)
        app.start()
        runCurrent()

        repeat(50) { index ->
            app.addMovie(id = "$index", title = "Movie $index")
            advanceTimeBy(100)
        }
        advanceTimeBy(6.seconds)
        settle { server.rows("collection_entry", "alice").size == 50 }

        assertEquals(50, server.rows("collection_entry", "alice").size)
        assertEquals(1, server.requestsTo("collection_entry", "POST").size, "one request carried all fifty")
    }

    @Test
    fun a_change_made_on_another_device_appears_in_the_library_flow() = runTest {
        val server = FakeSupabaseServer()
        // The other device's write, as the server holds it. (A second Koin graph
        // would share its singletons with this one: Koin modules keep their
        // instances, and `appModules` is one set of module objects.)
        server.signUp("alice")
        server.seed(
            "collection_entry",
            "alice",
            buildJsonObject {
                put("media_id", "tmdb:movie:27205")
                put("media_type", "MOVIE")
                put("title", "Inception")
                put("production_status", "RELEASED")
                put("added_at_epoch_ms", CLOCK_BASE)
                put("updated_at_epoch_ms", CLOCK_BASE)
            },
        )
        val app = newApp(server)
        app.signIn()
        app.flags.setSyncAutomatically(true)
        app.start()
        runCurrent()

        app.collection.observeSummaries().test {
            assertEquals(emptyList(), awaitItem())
            app.coordinator.onForeground()
            // The first cycle's own writes (adopting the account marks every row
            // dirty) re-emit the still-empty list before the pull lands.
            var titles = emptyList<String>()
            while (titles.isEmpty()) titles = awaitItem().map { it.title }
            assertEquals(listOf("Inception"), titles)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun turning_the_switch_off_mid_run_stops_every_later_run() = runTest {
        val server = FakeSupabaseServer()
        val app = newApp(server)
        app.signIn()
        app.flags.setSyncAutomatically(true)
        app.start()
        runCurrent()
        val release = CompletableDeferred<Unit>()
        server.onRequest = { request -> if (request.isUpsert) release.await() }

        app.addMovie()
        advanceTimeBy(6.seconds)
        settle { server.upserts() == 1 }
        assertEquals(1, server.upserts(), "the run is in flight, held open")

        app.flags.setSyncAutomatically(false)
        runCurrent()
        release.complete(Unit)
        app.addMovie(id = "27205", title = "Inception")
        advanceTimeBy(30.minutes)
        quiesce()

        assertEquals(1, server.upserts(), "no run after the switch went off")
    }

    // --- ADR 0018: never gate in the UI alone -------------------------------------

    @Test
    fun an_unentitled_user_never_reaches_the_server_whatever_the_switch_says() = runTest {
        val server = FakeSupabaseServer()
        val app = newApp(server, entitlement = Entitlement.Inactive)
        app.signIn()
        app.flags.setSyncAutomatically(true)
        app.start()
        runCurrent()

        app.addMovie()
        app.coordinator.onForeground()
        advanceTimeBy(30.minutes)
        quiesce()
        val manual = app.engine.syncNow()

        assertEquals(SyncOutcome.NotEntitled, manual)
        assertEquals(emptyList(), server.requests)
    }

    @Test
    fun a_signed_out_user_never_reaches_the_server_whatever_the_switch_says() = runTest {
        val server = FakeSupabaseServer()
        val app = newApp(server)
        // No app.signIn(): there is no session.
        app.flags.setSyncAutomatically(true)
        app.start()
        runCurrent()

        app.addMovie()
        app.coordinator.onForeground()
        advanceTimeBy(30.minutes)
        quiesce()
        val manual = app.engine.syncNow()

        assertEquals(SyncOutcome.NotSignedIn, manual)
        assertEquals(emptyList(), server.requests)
    }

    @Test
    fun a_build_without_background_sync_never_syncs_by_itself_even_with_the_switch_stored_on() = runTest {
        val server = FakeSupabaseServer()
        val app = newApp(server, backgroundAvailable = false)
        app.signIn()
        app.flags.setSyncAutomatically(true)
        app.start()
        runCurrent()

        app.addMovie()
        app.coordinator.onForeground()
        advanceTimeBy(30.minutes)
        quiesce()

        assertEquals(emptyList(), server.requests)
    }
}
