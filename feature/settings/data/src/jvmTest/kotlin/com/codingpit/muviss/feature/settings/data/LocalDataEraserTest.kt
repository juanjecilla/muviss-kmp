package com.codingpit.muviss.feature.settings.data

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import com.codingpit.muviss.core.common.AppDispatchers
import com.codingpit.muviss.core.common.AppVersion
import com.codingpit.muviss.core.common.secrets.InMemorySecretStore
import com.codingpit.muviss.core.network.tmdb.OwnTmdbKey
import com.codingpit.muviss.core.testing.FakeClock
import com.codingpit.muviss.core.testing.inMemoryDatabase
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
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
        database.remoteConfigCacheQueries.upsert("config", "{}")
        val secrets = InMemorySecretStore()
        val ownKey = OwnTmdbKey(secrets, HttpClient(MockEngine { respond("{}", HttpStatusCode.OK) }))
        secrets.put(OwnTmdbKey.STORE_NAME, "c".repeat(32))
        secrets.put("something-else", "x")

        SqlDelightLocalDataEraser(database, dispatchers, ownKey, secrets).deleteAllData()

        val export = Json.decodeFromString<MuvissDataExport>(repository.exportData())
        assertTrue(export.collection.isEmpty() && export.progress.isEmpty() && export.plays.isEmpty())
        assertTrue(export.triage.isEmpty() && export.lists.isEmpty() && export.snoozes.isEmpty())
        assertEquals(null, export.profile, "the profile row is gone until something reads it again")
        assertTrue(database.syncCursorQueries.selectAll().awaitAsList().isEmpty())
        assertEquals(null, database.remoteConfigCacheQueries.selectByKey("config").awaitAsOneOrNull(), "the cached remote config is a cache of this install")
        assertEquals(null, secrets.get(OwnTmdbKey.STORE_NAME), "the Own TMDB Key goes with everything else")
        assertEquals(null, secrets.get("something-else"), "and so does any other secret: the store is wiped, not one entry")
        database.appSettingsQueries.ensureRow()
        assertEquals("SYSTEM", database.appSettingsQueries.selectSettings().awaitAsOne().theme)
    }
}
