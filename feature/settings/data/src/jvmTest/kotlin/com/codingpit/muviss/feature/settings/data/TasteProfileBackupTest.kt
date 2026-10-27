package com.codingpit.muviss.feature.settings.data

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import com.codingpit.muviss.core.common.AppDispatchers
import com.codingpit.muviss.core.common.AppVersion
import com.codingpit.muviss.core.database.MuvissDatabase
import com.codingpit.muviss.core.testing.FakeClock
import com.codingpit.muviss.core.testing.inMemoryDatabase
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Taste Profile's round trip through a backup (EPIC 51, #402). The rule it pins
 * is the one restore promises for every table: per-row last-write-wins on the item's
 * stamp, a restored item goes out dirty, and a removed item is simply absent from
 * the file.
 */
class TasteProfileBackupTest {

    private val dispatchers = object : AppDispatchers {
        override val default = UnconfinedTestDispatcher()
        override val io = default
    }

    private fun exporter(database: MuvissDatabase) = SqlDelightSettingsRepository(
        database.appSettingsQueries,
        ExportQueries(database),
        dispatchers,
        FakeClock(9_000L),
        AppVersion("1.2.3", 42L),
    )

    private fun restorer(database: MuvissDatabase) = SqlDelightBackupRestorer(database, dispatchers)

    private fun write(database: MuvissDatabase, id: String, updatedAt: Long, deleted: Boolean = false) {
        runTest {
            database.tasteProfileQueries.upsert(
                id = id,
                kind = "GENRE",
                itemValue = "movie:28",
                label = "Action",
                updatedAtEpochMs = updatedAt,
                isDirty = false,
                deleted = deleted,
            )
        }
    }

    @Test
    fun `a liked item is in the export, with its label and key`() = runTest {
        val source = inMemoryDatabase()
        write(source, ACTION, updatedAt = 1_000L)

        val exported = Json.decodeFromString<MuvissDataExport>(exporter(source).exportData())

        val item = exported.tasteProfile.single()
        assertEquals(ACTION, item.id)
        assertEquals("GENRE", item.kind)
        assertEquals("movie:28", item.value)
        assertEquals("Action", item.label)
    }

    @Test
    fun `export, wipe, restore, export again gives the same taste profile`() = runTest {
        val source = inMemoryDatabase()
        write(source, ACTION, updatedAt = 1_000L)
        val first = exporter(source).exportData()

        val fresh = inMemoryDatabase()
        restorer(fresh).restore(first)

        assertEquals(
            Json.decodeFromString<MuvissDataExport>(first).tasteProfile,
            Json.decodeFromString<MuvissDataExport>(exporter(fresh).exportData()).tasteProfile,
        )
    }

    @Test
    fun `a restored item is written dirty so the next sync carries it`() = runTest {
        val source = inMemoryDatabase()
        write(source, ACTION, updatedAt = 1_000L)
        val backup = exporter(source).exportData()

        val fresh = inMemoryDatabase()
        restorer(fresh).restore(backup)

        assertTrue(fresh.tasteProfileQueries.selectById(ACTION).awaitAsOneOrNull()!!.isDirty)
    }

    @Test
    fun `an older backup item never overwrites a newer local one`() = runTest {
        val source = inMemoryDatabase()
        write(source, ACTION, updatedAt = 1_000L)
        val backup = exporter(source).exportData()

        val local = inMemoryDatabase()
        write(local, ACTION, updatedAt = 5_000L)
        restorer(local).restore(backup)

        assertEquals(5_000L, local.tasteProfileQueries.selectById(ACTION).awaitAsOneOrNull()!!.updatedAtEpochMs)
    }

    @Test
    fun `a newer local removal is not undone by an older backup that still has the item`() = runTest {
        val source = inMemoryDatabase()
        write(source, ACTION, updatedAt = 1_000L)
        val backup = exporter(source).exportData()

        val local = inMemoryDatabase()
        write(local, ACTION, updatedAt = 5_000L, deleted = true)
        restorer(local).restore(backup)

        assertTrue(local.tasteProfileQueries.selectById(ACTION).awaitAsOneOrNull()!!.deleted)
        assertTrue(local.tasteProfileQueries.selectAll().awaitAsList().isEmpty())
    }

    @Test
    fun `a backup from before the taste profile existed restores with none`() = runTest {
        val oldBackup = """{"formatVersion":2,"exportedAtEpochMs":1,"collection":[],"progress":[]}"""

        val database = inMemoryDatabase()
        restorer(database).restore(oldBackup)

        assertTrue(database.tasteProfileQueries.selectAll().awaitAsList().isEmpty())
        assertNull(database.tasteProfileQueries.selectById("missing").awaitAsOneOrNull())
        assertFalse(exporter(database).exportData().contains("\"tasteProfile\":[{"))
    }

    private companion object {
        const val ACTION = "GENRE:movie:28"
    }
}
