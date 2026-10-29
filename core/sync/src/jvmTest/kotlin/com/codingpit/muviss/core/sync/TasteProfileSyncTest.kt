package com.codingpit.muviss.core.sync

import com.codingpit.muviss.core.database.MuvissDatabase
import com.codingpit.muviss.core.testing.FakeClock
import com.codingpit.muviss.core.testing.inMemoryDatabase
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Sync coverage for `tasteProfile` (EPIC 51, #402).
 *
 * One row per item, so two devices that each add an item keep both, and an
 * unpicked item is a tombstone that cannot be pushed back. Each test names the
 * rule it pins: the derived key, the soft delete, and last-write-wins.
 */
class TasteProfileSyncTest {

    private lateinit var database: MuvissDatabase
    private lateinit var backend: FakeSyncBackend
    private lateinit var engine: SyncEngine

    private val action = "GENRE:movie:28"

    @BeforeTest
    fun setUp() {
        database = inMemoryDatabase()
        backend = FakeSyncBackend()
        engine = SyncEngine(backend, database, ImmediateDispatchers(UnconfinedTestDispatcher()), FakeClock(10_000))
    }

    private suspend fun writeLocal(id: String = action, updatedAt: Long = 1_000, isDirty: Boolean = true, deleted: Boolean = false) {
        database.tasteProfileQueries.upsert(
            id = id,
            kind = "GENRE",
            itemValue = "movie:28",
            label = "Action",
            updatedAtEpochMs = updatedAt,
            isDirty = isDirty,
            deleted = deleted,
        )
    }

    private fun remoteChange(id: String = action, label: String = "Action", updatedAt: Long, deleted: Boolean = false) = TasteProfileItemChange(
        id = id,
        kind = "GENRE",
        value = "movie:28",
        label = label,
        updatedAtEpochMs = updatedAt,
        deleted = deleted,
    )

    @Test
    fun `a dirty item is pushed and then marked clean`() = runTest {
        writeLocal()

        val outcome = engine.syncNow()

        assertTrue(outcome is SyncOutcome.Success)
        assertEquals("Action", backend.remoteTasteProfileItem(action)?.label)
        assertEquals(emptyList(), database.tasteProfileQueries.selectDirty().executeAsList())
    }

    @Test
    fun `the label rides along so another device can name the item offline`() = runTest {
        writeLocal()

        engine.syncNow()

        assertEquals("GENRE", backend.remoteTasteProfileItem(action)?.kind)
        assertEquals("movie:28", backend.remoteTasteProfileItem(action)?.value)
    }

    @Test
    fun `a remote item arrives clean and is not marked dirty again`() = runTest {
        backend.seedRemoteTasteProfileItem(remoteChange(updatedAt = 2_000))

        engine.syncNow()

        val local = database.tasteProfileQueries.selectById(action).executeAsOne()
        assertEquals(false, local.isDirty)
        assertEquals(false, local.deleted)
    }

    @Test
    fun `a stale remote item never overwrites a newer local one`() = runTest {
        writeLocal(updatedAt = 5_000, isDirty = false)
        backend.seedRemoteTasteProfileItem(remoteChange(label = "Old name", updatedAt = 4_999))

        engine.syncNow()

        assertEquals("Action", database.tasteProfileQueries.selectById(action).executeAsOne().label)
    }

    @Test
    fun `a tombstone propagates so an unpick on one device reaches the others`() = runTest {
        writeLocal(updatedAt = 1_000, isDirty = false)
        backend.seedRemoteTasteProfileItem(remoteChange(updatedAt = 3_000, deleted = true))

        engine.syncNow()

        assertTrue(database.tasteProfileQueries.selectById(action).executeAsOne().deleted)
        assertEquals(emptyList(), database.tasteProfileQueries.selectAll().executeAsList())
    }

    @Test
    fun `two devices adding different items keep both`() = runTest {
        writeLocal(id = action)
        writeLocal(id = "SERVICE:8", updatedAt = 1_500)
        backend.seedRemoteTasteProfileItem(
            TasteProfileItemChange(id = "COMPANY:FAMILY", kind = "COMPANY", value = "FAMILY", label = "Family", updatedAtEpochMs = 900, deleted = false),
        )

        engine.syncNow()

        val ids = database.tasteProfileQueries.selectAll().executeAsList().map { it.id }
        assertEquals(listOf(action, "COMPANY:FAMILY", "SERVICE:8").sorted(), ids.sorted())
    }

    @Test
    fun `resync everything puts taste items back on the change log`() = runTest {
        writeLocal(isDirty = false)

        LocalChangeLog(database).markAllDirty()

        assertEquals(1, database.tasteProfileQueries.selectDirty().executeAsList().size)
    }

    @Test
    fun `discarding local data clears the taste profile with everything else`() = runTest {
        writeLocal()

        LocalChangeLog(database).deleteAllUserData()

        assertEquals(emptyList(), database.tasteProfileQueries.selectAll().executeAsList())
    }
}
