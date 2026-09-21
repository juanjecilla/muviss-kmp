@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.core.sync

import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.turbine.test
import com.codingpit.muviss.core.database.MuvissDatabase
import com.codingpit.muviss.core.sync.supabase.SupabaseHttpException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val DAY_MS = 86_400_000L

/**
 * ADR 0021's gating, at the only place that can enforce it: the engine. Each
 * test builds the real [SyncEngine] over a real database and asks it for a
 * cycle the way a trigger would; what is asserted is what reached the backend
 * and what was left in the database, never what a caller chose to skip.
 */
class SyncEngineAutomaticTest {

    private lateinit var database: MuvissDatabase
    private lateinit var clock: FakeClock
    private lateinit var backend: FakeSyncBackend
    private val switch = MutableStateFlow(false)
    private var backgroundAvailable = true

    private val automatic = SyncTrigger.entries.filter { it.isAutomatic }

    @BeforeTest
    fun setUp() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MuvissDatabase.Schema.synchronous().create(driver)
        database = MuvissDatabase(driver)
        clock = FakeClock(1_000L)
        backend = FakeSyncBackend()
    }

    private fun engine(gate: EntitlementGate = EntitlementGate.AlwaysEntitled) = SyncEngine(
        backend = backend,
        database = database,
        dispatchers = ImmediateDispatchers(UnconfinedTestDispatcher()),
        clock = clock,
        entitlementGate = gate,
        automatic = AutomaticSyncSettings(
            availability = object : SyncAvailability {
                override fun isConfigured() = true
                override fun isBackgroundAvailable() = backgroundAvailable
            },
            switch = switch,
        ),
    )

    private suspend fun dirtyRow(mediaId: String = "tmdb:movie:603") {
        database.alreadyOwnedBy()
        database.collectionEntryQueries.upsert(
            mediaId = mediaId,
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
            updatedAtEpochMs = clock.nowEpochMs(),
            isDirty = true,
            deleted = false,
            notificationsMuted = false,
            rating = null,
            note = null,
            revisitWillingness = null,
            coWatchPinned = false,
        )
    }

    private fun assertBackendUntouched(label: String) {
        assertTrue(backend.pushes.isEmpty(), "$label: nothing may be pushed")
        assertTrue(backend.pullRequests.isEmpty(), "$label: nothing may be pulled")
    }

    // --- the switch ----------------------------------------------------------

    @Test
    fun every_automatic_trigger_is_disabled_with_the_switch_off_and_touches_nothing() = runTest {
        dirtyRow()
        switch.value = false

        automatic.forEach { trigger ->
            assertEquals(SyncOutcome.Disabled, engine().syncNow(trigger), "$trigger")
        }

        assertBackendUntouched("switch off")
        assertTrue(database.collectionEntryQueries.selectById("tmdb:movie:603").awaitAsOneOrNull()!!.isDirty, "the row was never sent")
        assertNull(database.syncStateQueries.selectState().awaitAsOneOrNull()?.lastAttemptAtEpochMs, "not even an attempt is recorded")
    }

    @Test
    fun a_switch_left_on_cannot_outvote_a_build_without_background_sync() = runTest {
        dirtyRow()
        switch.value = true
        backgroundAvailable = false

        automatic.forEach { assertEquals(SyncOutcome.Disabled, engine().syncNow(it), "$it") }

        assertBackendUntouched("build flag off")
    }

    @Test
    fun manual_sync_works_with_the_switch_off() = runTest {
        dirtyRow()
        switch.value = false

        val outcome = engine().syncNow()

        assertTrue(outcome is SyncOutcome.Success, "$outcome")
        assertEquals(1, backend.pushes.sumOf { it.collectionEntries.size })
    }

    @Test
    fun manual_sync_works_in_a_build_without_background_sync() = runTest {
        dirtyRow()
        backgroundAvailable = false

        assertTrue(engine().syncNow(SyncTrigger.Manual) is SyncOutcome.Success)
    }

    @Test
    fun every_automatic_trigger_runs_with_the_switch_on() = runTest {
        switch.value = true

        automatic.forEach { trigger ->
            dirtyRow()
            assertTrue(engine().syncNow(trigger) is SyncOutcome.Success, "$trigger")
        }
    }

    // --- session and entitlement, whatever the switch says --------------------

    @Test
    fun a_signed_out_device_never_reaches_the_server_whatever_the_switch_says() = runTest {
        dirtyRow()
        switch.value = true
        backend.setSession(null)

        SyncTrigger.entries.forEach { assertEquals(SyncOutcome.NotSignedIn, engine().syncNow(it), "$it") }

        assertBackendUntouched("signed out")
    }

    @Test
    fun an_unentitled_device_never_reaches_the_server_whatever_the_switch_says() = runTest {
        dirtyRow()
        switch.value = true

        SyncTrigger.entries.forEach { assertEquals(SyncOutcome.NotEntitled, engine(EntitlementGate { false }).syncNow(it), "$it") }

        assertBackendUntouched("unentitled")
    }

    @Test
    fun a_disabled_automatic_run_does_not_even_ask_the_entitlement_gate() = runTest {
        // The gate can be a store lookup; an automatic trigger that is not
        // allowed to run has no business paying for one.
        var asked = false
        switch.value = false

        engine(
            EntitlementGate {
                asked = true
                false
            },
        ).syncNow(SyncTrigger.Periodic)

        assertEquals(false, asked)
    }

    @Test
    fun turning_the_switch_off_while_a_run_is_in_flight_stops_the_queued_one() = runTest {
        dirtyRow()
        switch.value = true
        val engine = engine()
        val release = CompletableDeferred<Unit>()
        backend.pushGate = release

        val running = async { engine.syncNow(SyncTrigger.Manual) }
        val queued = async { engine.syncNow(SyncTrigger.Change) }
        switch.value = false
        release.complete(Unit)

        assertTrue(running.await() is SyncOutcome.Success)
        assertEquals(SyncOutcome.Disabled, queued.await(), "the run waiting behind it re-reads the switch")
    }

    // --- finished-at and typed failures ----------------------------------------------

    @Test
    fun a_disabled_run_is_not_a_finished_cycle() = runTest {
        val engine = engine()

        engine.syncNow(SyncTrigger.Foreground)
        assertNull(engine.lastFinishedAtEpochMs)

        engine.syncNow()
        assertEquals(1_000L, engine.lastFinishedAtEpochMs)
    }

    @Test
    fun a_rejected_session_is_reported_as_unauthorised_and_remembered() = runTest {
        dirtyRow()
        backend.pushFailure = SupabaseHttpException(status = 401, what = "push", body = "JWT expired")
        val engine = engine()

        val outcome = engine.syncNow()

        assertTrue(outcome is SyncOutcome.Failed && outcome.reason == SyncFailureReason.Unauthorised, "$outcome")
        val stored = database.syncStateQueries.selectState().awaitAsOneOrNull()!!
        assertEquals(SyncFailureReason.Unauthorised, SyncFailureReason.fromStored(stored.lastError))
        engine.observeStatus().test {
            val status = awaitItem()
            assertEquals(true, status.lastAttemptFailed)
            assertEquals(SyncFailureReason.Unauthorised, status.lastFailure)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun an_unrecognised_failure_is_unknown_and_its_text_stays_out_of_the_reason() = runTest {
        dirtyRow()
        backend.pushFailure = IllegalStateException("kaboom")

        val outcome = engine().syncNow()

        assertEquals(SyncOutcome.Failed(SyncFailureReason.Unknown, "kaboom"), outcome)
    }

    @Test
    fun a_success_clears_the_remembered_failure() = runTest {
        dirtyRow()
        backend.pushFailure = IllegalStateException("kaboom")
        val engine = engine()
        engine.syncNow()

        backend.pushFailure = null
        engine.syncNow()

        engine.observeStatus().test {
            val status = awaitItem()
            assertEquals(false, status.lastAttemptFailed)
            assertNull(status.lastFailure)
            assertEquals(0, status.consecutiveFailures)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun forgetting_the_failure_clears_it_without_a_sync() = runTest {
        dirtyRow()
        backend.pushFailure = IllegalStateException("kaboom")
        val engine = engine()
        engine.syncNow()

        engine.forgetLastFailure()

        engine.observeStatus().test {
            assertEquals(false, awaitItem().lastAttemptFailed)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- pending changes ------------------------------------------------------------

    @Test
    fun pending_changes_counts_dirty_rows_and_drops_to_zero_after_a_push() = runTest {
        val engine = engine()
        assertEquals(0L, engine.observePendingChanges().first())

        dirtyRow("a")
        dirtyRow("b")
        assertEquals(2L, engine.observePendingChanges().first())

        engine.syncNow()
        assertEquals(0L, engine.observePendingChanges().first())
    }

    @Test
    fun a_pull_never_raises_the_pending_count() = runTest {
        // The reason the change push cannot feed itself: pulled rows are clean.
        database.alreadyOwnedBy()
        backend.seedRemoteEpisodePlay(
            EpisodePlayChange(id = "p@1", episodeId = "tmdb:tv:1/1/1", mediaId = "tmdb:tv:1", watchedAtEpochMs = 1L, updatedAtEpochMs = 1L, deleted = false),
        )
        val engine = engine()

        val outcome = engine.syncNow()

        assertTrue(outcome is SyncOutcome.Success && outcome.pulledCount == 1, "$outcome")
        assertEquals(0L, engine.observePendingChanges().first())
    }

    // --- the weekly reconcile ---------------------------------------------------------

    private fun seedRemote(id: String) = backend.seedRemoteEpisodePlay(
        EpisodePlayChange(id = id, episodeId = "tmdb:tv:1/1/1", mediaId = "tmdb:tv:1", watchedAtEpochMs = 1L, updatedAtEpochMs = 1L, deleted = false),
    )

    @Test
    fun the_first_sync_is_a_full_pull_and_records_when() = runTest {
        seedRemote("p@1")

        engine().syncNow()

        assertEquals(emptyMap(), backend.pullRequests.single())
        assertEquals(1_000L, database.syncCursorQueries.selectByName("_lastFullPullAt").awaitAsOneOrNull()?.seq)
    }

    @Test
    fun within_a_week_the_next_pull_continues_from_the_cursors() = runTest {
        seedRemote("p@1")
        val engine = engine()
        engine.syncNow()

        clock.advanceTo(1_000L + 6 * DAY_MS)
        engine.syncNow()

        assertTrue(backend.pullRequests.last().isNotEmpty(), "still incremental after six days")
    }

    @Test
    fun after_a_week_the_pull_starts_from_the_beginning_again_and_restamps() = runTest {
        seedRemote("p@1")
        val engine = engine()
        engine.syncNow()

        clock.advanceTo(1_000L + 7 * DAY_MS)
        engine.syncNow()

        assertEquals(emptyMap(), backend.pullRequests.last(), "a full reconcile")
        assertEquals(1_000L + 7 * DAY_MS, database.syncCursorQueries.selectByName("_lastFullPullAt").awaitAsOneOrNull()?.seq)

        clock.advanceTo(1_000L + 8 * DAY_MS)
        engine.syncNow()
        assertTrue(backend.pullRequests.last().isNotEmpty(), "and incremental again the day after")
    }

    @Test
    fun an_install_with_cursors_but_no_record_reconciles_once() = runTest {
        // Every install that synced before this stamp existed is in this state.
        seedRemote("p@1")
        val engine = engine()
        engine.syncNow()
        database.syncCursorQueries.upsert("episodePlay", 1L)
        database.syncCursorQueries.deleteAll()
        database.syncCursorQueries.upsert("episodePlay", 1L)

        engine.syncNow()

        assertEquals(emptyMap(), backend.pullRequests.last())
    }
}
