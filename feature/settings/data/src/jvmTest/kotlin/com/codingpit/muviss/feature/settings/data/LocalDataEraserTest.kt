package com.codingpit.muviss.feature.settings.data

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import com.codingpit.muviss.core.common.AppDispatchers
import com.codingpit.muviss.core.common.AppVersion
import com.codingpit.muviss.core.testing.FakeClock
import com.codingpit.muviss.core.testing.inMemoryDatabase
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** EPIC 29 (#72): Settings > Delete all data leaves a fresh install behind. */
class LocalDataEraserTest {

    private val dispatchers = object : AppDispatchers {
        override val default = UnconfinedTestDispatcher()
        override val io = default
    }

    @Test
    fun `after deleting everything the export is a fresh install's`() = runTest {
        val database = inMemoryDatabase()
        val repository = SqlDelightSettingsRepository(
            database.appSettingsQueries,
            ExportQueries(database),
            dispatchers,
            FakeClock(1L),
            AppVersion("1", 1L),
        )
        val fixture = javaClass.getResource("/backups/v1-export.json")!!.readText()
        SqlDelightBackupRestorer(database, dispatchers).restore(fixture)
        database.mediaListQueries.upsertList("list-1", "Rainy day", 1L, 1L, false, false)
        database.appSettingsQueries.ensureRow()
        database.appSettingsQueries.updateTheme("DARK")
        database.profileQueries.ensureRow()
        database.profileQueries.updateDisplayName("Juanje")
        database.syncCursorQueries.upsert("collectionEntry", 42L)

        SqlDelightLocalDataEraser(database, dispatchers).deleteAllData()

        val export = Json.decodeFromString<MuvissDataExport>(repository.exportData())
        assertTrue(export.collection.isEmpty() && export.progress.isEmpty() && export.plays.isEmpty())
        assertTrue(export.triage.isEmpty() && export.lists.isEmpty() && export.snoozes.isEmpty())
        assertEquals(null, export.profile, "the profile row is gone until something reads it again")
        assertTrue(database.syncCursorQueries.selectAll().awaitAsList().isEmpty())
        database.appSettingsQueries.ensureRow()
        assertEquals("SYSTEM", database.appSettingsQueries.selectSettings().awaitAsOne().theme)
    }
}
