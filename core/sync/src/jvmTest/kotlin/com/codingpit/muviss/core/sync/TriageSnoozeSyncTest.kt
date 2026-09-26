package com.codingpit.muviss.core.sync

import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.codingpit.muviss.core.database.MuvissDatabase
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Sync coverage for `triageSnooze` (EPIC 42, ADR 0023).
 *
 * Postponing a title is user intent for the same reason skipping one is, and
 * replicates for the same reason: being asked again on the desktop about
 * something already postponed on the phone is the failure, not the feature.
 *
 * The card snapshot has to survive the round trip too — the receiving device
 * renders from it when the Snooze comes due and has no cheap way to rebuild it.
 */
class TriageSnoozeSyncTest {

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
        dueAtEpochDay: Long = 20_007,
        updatedAt: Long = 1_000,
        isDirty: Boolean = true,
        deleted: Boolean = false,
    ) {
        database.triageSnoozeQueries.upsert(
            mediaId = mediaId,
            mediaType = "movie",
            title = "The Matrix",
            year = 1999,
            posterUrl = null,
            overview = "A hacker learns the truth.",
            snoozedAtEpochMs = updatedAt,
            dueAtEpochDay = dueAtEpochDay,
            updatedAtEpochMs = updatedAt,
            isDirty = isDirty,
            deleted = deleted,
        )
    }

    private fun remoteChange(
        mediaId: String = matrix,
        dueAtEpochDay: Long = 20_030,
        updatedAt: Long,
        deleted: Boolean = false,
    ) = TriageSnoozeChange(
        mediaId = mediaId,
        mediaType = "movie",
        title = "The Matrix",
        year = 1999,
        posterUrl = null,
        overview = "A hacker learns the truth.",
        snoozedAtEpochMs = updatedAt,
        dueAtEpochDay = dueAtEpochDay,
        updatedAtEpochMs = updatedAt,
        deleted = deleted,
    )

    @Test
    fun `a dirty snooze is pushed and then marked clean`() = runTest {
        writeLocal()

        val outcome = engine.syncNow()

        assertTrue(outcome is SyncOutcome.Success)
        assertEquals(20_007L, backend.remoteTriageSnooze(matrix)?.dueAtEpochDay)
        assertEquals(emptyList(), database.triageSnoozeQueries.selectDirty().executeAsList())
    }

    @Test
    fun `the card snapshot survives the round trip`() = runTest {
        writeLocal()

        engine.syncNow()

        val pushed = backend.remoteTriageSnooze(matrix)
        // Without these the receiving device has a date and no card to show.
        assertEquals("The Matrix", pushed?.title)
        assertEquals(1999L, pushed?.year)
        assertEquals("A hacker learns the truth.", pushed?.overview)
    }

    @Test
    fun `a remote snooze arrives and is not marked dirty again`() = runTest {
        backend.seedRemoteTriageSnooze(remoteChange(updatedAt = 2_000))

        engine.syncNow()

        val local = database.triageSnoozeQueries.selectById(matrix).executeAsOne()
        assertEquals(20_030L, local.dueAtEpochDay)
        assertEquals(false, local.isDirty)
    }

    @Test
    fun `a stale remote row never overwrites a newer local snooze`() = runTest {
        writeLocal(dueAtEpochDay = 20_007, updatedAt = 5_000, isDirty = false)
        backend.seedRemoteTriageSnooze(remoteChange(dueAtEpochDay = 20_090, updatedAt = 4_999))

        engine.syncNow()

        assertEquals(20_007L, database.triageSnoozeQueries.selectById(matrix).executeAsOne().dueAtEpochDay)
    }

    @Test
    fun `a tombstone propagates so an unsnooze on one device reaches the others`() = runTest {
        writeLocal(updatedAt = 1_000, isDirty = false)
        backend.seedRemoteTriageSnooze(remoteChange(updatedAt = 3_000, deleted = true))

        engine.syncNow()

        val local = database.triageSnoozeQueries.selectById(matrix).executeAsOne()
        assertEquals(true, local.deleted)
        // Deck-eligible again on this device too.
        assertEquals(emptyList(), database.triageSnoozeQueries.selectSnoozedIds().executeAsList())
    }

    @Test
    fun `resync everything puts snoozes back on the change log`() = runTest {
        writeLocal(isDirty = false)

        LocalChangeLog(database).markAllDirty()

        assertEquals(1, database.triageSnoozeQueries.selectDirty().executeAsList().size)
    }

    @Test
    fun `discarding local data clears snoozes with everything else`() = runTest {
        writeLocal()

        LocalChangeLog(database).deleteAllUserData()

        assertEquals(emptyList(), database.triageSnoozeQueries.selectAll().executeAsList())
    }
}
