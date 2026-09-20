package com.codingpit.muviss.core.sync

import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.codingpit.muviss.core.database.MuvissDatabase
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Sync coverage for `triageDecision` (ADR 0010).
 *
 * Skipping a title is user intent, not a device preference, so unlike
 * `collectionEntry.notificationsMuted` it replicates: a title ruled on from a
 * phone must not come back around on a desktop, or the promise that triage
 * never asks twice only holds on one device.
 */
class TriageDecisionSyncTest {

    private lateinit var database: MuvissDatabase
    private lateinit var backend: FakeSyncBackend
    private lateinit var engine: SyncEngine
    private lateinit var clock: FakeClock

    private val matrix = "tmdb:movie:603"

    @BeforeTest
    fun setUp() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MuvissDatabase.Schema.synchronous().create(driver)
        database = MuvissDatabase(driver)
        backend = FakeSyncBackend()
        clock = FakeClock(10_000)
        engine = SyncEngine(backend, database, ImmediateDispatchers(UnconfinedTestDispatcher()), clock)
    }

    private suspend fun writeLocal(
        mediaId: String = matrix,
        verdict: String = "SKIP",
        updatedAt: Long = 1_000,
        isDirty: Boolean = true,
        resolved: Boolean = true,
    ) {
        database.triageDecisionQueries.upsert(
            mediaId = mediaId,
            mediaType = "movie",
            verdict = verdict,
            title = "The Matrix",
            posterUrl = null,
            decidedAtEpochMs = updatedAt,
            resolved = resolved,
            updatedAtEpochMs = updatedAt,
            isDirty = isDirty,
            deleted = false,
        )
    }

    private fun remoteChange(
        mediaId: String = matrix,
        verdict: String = "CAUGHT_UP",
        updatedAt: Long,
        deleted: Boolean = false,
    ) = TriageDecisionChange(
        mediaId = mediaId,
        mediaType = "movie",
        verdict = verdict,
        title = "The Matrix",
        posterUrl = null,
        decidedAtEpochMs = updatedAt,
        resolved = true,
        updatedAtEpochMs = updatedAt,
        deleted = deleted,
    )

    @Test
    fun `a dirty decision is pushed and then marked clean`() = runTest {
        writeLocal()

        val outcome = engine.syncNow()

        assertTrue(outcome is SyncOutcome.Success)
        assertEquals("SKIP", backend.remoteTriageDecision(matrix)?.verdict)
        assertEquals(emptyList(), database.triageDecisionQueries.selectDirty().executeAsList())
    }

    @Test
    fun `a remote decision arrives and is not marked dirty again`() = runTest {
        backend.seedRemoteTriageDecision(remoteChange(updatedAt = 2_000))

        engine.syncNow()

        val local = database.triageDecisionQueries.selectById(matrix).executeAsOne()
        assertEquals("CAUGHT_UP", local.verdict)
        // Applying a pulled row must not schedule it straight back for push.
        assertEquals(false, local.isDirty)
    }

    @Test
    fun `a stale remote row never overwrites a newer local decision`() = runTest {
        writeLocal(verdict = "SKIP", updatedAt = 5_000, isDirty = false)
        backend.seedRemoteTriageDecision(remoteChange(verdict = "LATER", updatedAt = 4_999))

        engine.syncNow()

        assertEquals("SKIP", database.triageDecisionQueries.selectById(matrix).executeAsOne().verdict)
    }

    @Test
    fun `a tombstone propagates so a restore on one device restores everywhere`() = runTest {
        writeLocal(updatedAt = 1_000, isDirty = false)
        backend.seedRemoteTriageDecision(remoteChange(updatedAt = 3_000, deleted = true))

        engine.syncNow()

        val local = database.triageDecisionQueries.selectById(matrix).executeAsOne()
        assertEquals(true, local.deleted)
        // Deck-eligible again on this device too.
        assertEquals(emptyList(), database.triageDecisionQueries.selectDecidedIds().executeAsList())
    }

    @Test
    fun `an unresolved decision syncs as unresolved`() = runTest {
        writeLocal(resolved = false)

        engine.syncNow()

        assertEquals(false, backend.remoteTriageDecision(matrix)?.resolved)
    }

    @Test
    fun `a caught-up verdict pushes its decision alongside the ticks it wrote`() = runTest {
        writeLocal(mediaId = "tmdb:tv:1399", verdict = "CAUGHT_UP")
        database.episodeProgressQueries.upsert(
            episodeId = "tmdb:tv:1399/1/1",
            mediaId = "tmdb:tv:1399",
            seasonNumber = 1,
            episodeNumber = 1,
            seen = true,
            updatedAtEpochMs = 1_000,
            isDirty = true,
        )

        val outcome = engine.syncNow() as SyncOutcome.Success

        // Both halves of the verdict travel together — a decision without its
        // progress would land on another device as a title that says
        // "caught up" but reads Not Started.
        assertEquals(2, outcome.pushedCount)
        assertEquals("CAUGHT_UP", backend.remoteTriageDecision("tmdb:tv:1399")?.verdict)
    }

    @Test
    fun `a clean decision is not pushed`() = runTest {
        database.alreadyOwnedBy()
        writeLocal(isDirty = false)

        engine.syncNow()

        assertNull(backend.remoteTriageDecision(matrix))
    }
}
