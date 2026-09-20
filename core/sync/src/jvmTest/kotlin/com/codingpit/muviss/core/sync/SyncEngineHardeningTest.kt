@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.core.sync

import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.codingpit.muviss.core.database.MuvissDatabase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The failure modes that made sync unsafe to actually turn on: a pull cursor
 * taken from the wrong clock, two cycles running at once, and an entitlement
 * gate that the app's foreground hook could walk straight past. See ADR 0018.
 */
class SyncEngineHardeningTest {

    private lateinit var database: MuvissDatabase
    private lateinit var clock: FakeClock
    private lateinit var backend: FakeSyncBackend

    private fun newDatabase(): MuvissDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MuvissDatabase.Schema.synchronous().create(driver)
        return MuvissDatabase(driver)
    }

    private fun engine(gate: EntitlementGate = EntitlementGate.AlwaysEntitled) = SyncEngine(backend, database, ImmediateDispatchers(UnconfinedTestDispatcher()), clock, gate)

    @BeforeTest
    fun setUp() {
        database = newDatabase()
        clock = FakeClock(1_000L)
        backend = FakeSyncBackend()
    }

    private fun remotePlay(id: String, watchedAt: Long, updatedAt: Long, deleted: Boolean = false) = EpisodePlayChange(
        id = id,
        episodeId = "tmdb:tv:1399/1/1",
        mediaId = "tmdb:tv:1399",
        watchedAtEpochMs = watchedAt,
        updatedAtEpochMs = updatedAt,
        deleted = deleted,
    )

    // --- Entitlement gate ------------------------------------------------

    @Test
    fun an_unentitled_user_does_not_sync_even_though_they_are_signed_in() = runTest {
        val outcome = engine(gate = EntitlementGate { false }).syncNow()

        // The distinction matters to the UI: NotSignedIn asks for a sign-in,
        // NotEntitled asks for a purchase.
        assertEquals(SyncOutcome.NotEntitled, outcome)
        assertTrue(backend.pushes.isEmpty(), "a refused gate must not reach the backend at all")
    }

    @Test
    fun the_gate_is_checked_inside_the_engine_so_the_foreground_hook_cannot_bypass_it() = runTest {
        // MuvissApp's AutoSyncOnForeground calls syncNow() directly, with no
        // profile screen involved — this is the only place a check can cover it.
        var asked = false
        engine(
            gate = EntitlementGate {
                asked = true
                false
            },
        ).syncNow()

        assertTrue(asked, "syncNow must consult the gate itself, not rely on a caller having done so")
    }

    @Test
    fun an_entitled_user_syncs_normally() = runTest {
        val outcome = engine(gate = EntitlementGate { true }).syncNow()

        assertTrue(outcome is SyncOutcome.Success)
    }

    // --- Pull cursor -----------------------------------------------------

    private fun storedCursors(): Map<String, Long> = database.syncCursorQueries.selectAll().executeAsList().associate { it.tableName to it.seq }

    @Test
    fun the_cursor_is_the_backends_position_not_any_clock() = runTest {
        // The writing device's clock is ahead of this one's: its row is
        // stamped 9_000 while this device believes it is 1_000. A cursor taken
        // from either clock would sit on the wrong side of some row. The cursor
        // is whatever position the backend said the feed had reached — here the
        // fake's first sequence value, which has nothing to do with 9_000.
        backend.seedRemoteEpisodePlay(remotePlay(id = "play-a", watchedAt = 9_000L, updatedAt = 9_000L))

        engine().syncNow()

        assertEquals(mapOf("episodePlay" to 1L), storedCursors())
    }

    @Test
    fun the_last_synced_label_still_follows_the_local_clock() = runTest {
        // The cursor is the backend's and "last synced" is local, so they are
        // separate values (they used to share one column).
        backend.seedRemoteEpisodePlay(remotePlay(id = "play-a", watchedAt = 9_000L, updatedAt = 9_000L))

        engine().syncNow()

        val settings = database.appSettingsQueries.selectSettings().awaitAsOneOrNull()
        assertEquals(1_000L, settings?.lastSyncedAtEpochMs)
    }

    @Test
    fun an_empty_pull_advances_the_label_but_never_rewinds_the_cursor() = runTest {
        backend.seedRemoteEpisodePlay(remotePlay(id = "play-a", watchedAt = 9_000L, updatedAt = 9_000L))
        engine().syncNow()

        clock.advanceTo(2_000L)
        engine().syncNow()

        val settings = database.appSettingsQueries.selectSettings().awaitAsOneOrNull()
        assertEquals(mapOf("episodePlay" to 1L), storedCursors(), "nothing new came back, so the position stands")
        assertEquals(2_000L, settings?.lastSyncedAtEpochMs, "but a cycle did run, and the label has to say so")
    }

    @Test
    fun the_first_sync_pulls_everything() = runTest {
        backend.seedRemoteEpisodePlay(remotePlay(id = "play-old", watchedAt = 10L, updatedAt = 10L))

        val outcome = engine().syncNow()

        assertEquals(1, (outcome as SyncOutcome.Success).pulledCount, "a null cursor means no cursor, not a cursor of zero")
    }

    // --- Concurrency -----------------------------------------------------

    @Test
    fun a_second_cycle_waits_for_the_one_in_flight_instead_of_interleaving() = runTest {
        seedDirtyPlay(watchedAt = 500L)
        val engine = engine()
        // Hold the first cycle open inside push(). Without this the two calls
        // simply run one after the other and the test passes whether or not
        // SyncEngine excludes them.
        val gate = CompletableDeferred<Unit>()
        backend.pushGate = gate

        val first = async { engine.syncNow() }
        advanceUntilIdle()
        assertEquals(1, backend.pushes.size, "the first cycle should be suspended inside push")

        val second = async { engine.syncNow() }
        advanceUntilIdle()
        assertEquals(
            1,
            backend.pushes.size,
            "the second cycle must be waiting on the first, not reading a dirty set that is already mid-push",
        )

        gate.complete(Unit)
        backend.pushGate = null
        listOf(first, second).awaitAll()

        assertEquals(1, backend.pushes.sumOf { it.episodePlays.size }, "the row is pushed exactly once")
    }

    // --- Rewatch history over the wire -----------------------------------

    @Test
    fun a_local_play_is_pushed_and_then_left_clean() = runTest {
        seedDirtyPlay(watchedAt = 500L)

        engine().syncNow()

        assertEquals(500L, backend.remoteEpisodePlay(PLAY_ID)?.watchedAtEpochMs)
        assertFalse(
            database.episodePlayQueries.selectById(PLAY_ID).awaitAsOneOrNull()!!.isDirty,
            "a pushed row is clean until it changes again",
        )
    }

    @Test
    fun a_remote_play_arrives_as_history_on_this_device() = runTest {
        backend.seedRemoteEpisodePlay(remotePlay(id = "tmdb:tv:1399/1/1@700", watchedAt = 700L, updatedAt = 700L))

        engine().syncNow()

        assertEquals(1, database.episodePlayQueries.countForEpisode("tmdb:tv:1399/1/1").awaitAsOneOrNull()?.toInt())
    }

    @Test
    fun clearing_a_rewatch_on_one_device_clears_it_on_the_other() = runTest {
        // The reason plays are soft-deleted at all: last-write-wins has nothing
        // to compare against once a row is physically gone, so the other
        // device's older copy would simply be pushed back.
        backend.seedRemoteEpisodePlay(remotePlay(id = PLAY_ID, watchedAt = 500L, updatedAt = 500L))
        engine().syncNow()
        assertEquals(1, database.episodePlayQueries.countForEpisode(EPISODE_ID).awaitAsOneOrNull()?.toInt())

        backend.seedRemoteEpisodePlay(remotePlay(id = PLAY_ID, watchedAt = 500L, updatedAt = 900L, deleted = true))
        clock.advanceTo(2_000L)
        engine().syncNow()

        assertEquals(0, database.episodePlayQueries.countForEpisode(EPISODE_ID).awaitAsOneOrNull()?.toInt())
        assertTrue(database.episodePlayQueries.selectById(PLAY_ID).awaitAsOneOrNull()!!.deleted)
    }

    @Test
    fun a_stale_remote_play_never_resurrects_a_newer_local_tombstone() = runTest {
        database.episodePlayQueries.upsert(
            id = PLAY_ID,
            episodeId = EPISODE_ID,
            mediaId = "tmdb:tv:1399",
            watchedAtEpochMs = 500L,
            updatedAtEpochMs = 900L,
            isDirty = false,
            deleted = true,
        )
        backend.seedRemoteEpisodePlay(remotePlay(id = PLAY_ID, watchedAt = 500L, updatedAt = 500L))

        engine().syncNow()

        assertTrue(
            database.episodePlayQueries.selectById(PLAY_ID).awaitAsOneOrNull()!!.deleted,
            "an older remote write loses to a newer local tombstone, exactly like any other field (ADR 0009)",
        )
    }

    @Test
    fun a_clean_play_is_not_pushed() = runTest {
        database.alreadyOwnedBy()
        seedDirtyPlay(watchedAt = 500L, isDirty = false)

        val outcome = engine().syncNow()

        assertEquals(0, (outcome as SyncOutcome.Success).pushedCount)
        assertNull(backend.remoteEpisodePlay(PLAY_ID))
    }

    private suspend fun seedDirtyPlay(watchedAt: Long, isDirty: Boolean = true) {
        database.episodePlayQueries.upsert(
            id = "$EPISODE_ID@$watchedAt",
            episodeId = EPISODE_ID,
            mediaId = "tmdb:tv:1399",
            watchedAtEpochMs = watchedAt,
            updatedAtEpochMs = watchedAt,
            isDirty = isDirty,
            deleted = false,
        )
    }

    private companion object {
        const val EPISODE_ID = "tmdb:tv:1399/1/1"
        const val PLAY_ID = "tmdb:tv:1399/1/1@500"
    }
}
