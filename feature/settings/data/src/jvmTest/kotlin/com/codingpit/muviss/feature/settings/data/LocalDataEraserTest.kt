package com.codingpit.muviss.feature.settings.data

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import com.codingpit.muviss.core.common.AppDispatchers
import com.codingpit.muviss.core.common.AppVersion
import com.codingpit.muviss.core.common.secrets.InMemorySecretStore
import com.codingpit.muviss.core.common.widget.WidgetRefresher
import com.codingpit.muviss.core.database.MuvissDatabase
import com.codingpit.muviss.core.network.tmdb.OwnTmdbKey
import com.codingpit.muviss.core.sync.NoOpSyncBackend
import com.codingpit.muviss.core.sync.OAuthProvider
import com.codingpit.muviss.core.sync.SyncBackend
import com.codingpit.muviss.core.sync.SyncBackendId
import com.codingpit.muviss.core.sync.SyncChangeSet
import com.codingpit.muviss.core.sync.SyncCursor
import com.codingpit.muviss.core.sync.SyncIdleLock
import com.codingpit.muviss.core.sync.SyncPage
import com.codingpit.muviss.core.sync.SyncSession
import com.codingpit.muviss.core.sync.SyncTable
import com.codingpit.muviss.core.testing.FakeClock
import com.codingpit.muviss.core.testing.inMemoryDatabase
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.coroutines.cancellation.CancellationException
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
        database.remoteConfigCacheQueries.updateIfChanged(value = "{}", key = "config")
        database.remoteConfigCacheQueries.insertIfAbsent(key = "config", value = "{}")
        database.mutedGenreQueries.upsert("movie", 27L, "Horror", deleted = false, isDirty = true, updatedAtEpochMs = 1L)
        database.mutedGenreChoiceQueries.upsert(defaultsAtChoice = "", updatedAtEpochMs = 1L, isDirty = true)
        database.triageDeckCursorQueries.upsert("movie|any|-|en-US", 2L, 2L, 2L, 2L, 1L)
        database.discoverCacheQueries.upsert(cacheKey = "genres:tv|en-US", json = "[]", fetchedAtEpochMs = 1L)
        val secrets = InMemorySecretStore()
        val ownKey = OwnTmdbKey(secrets, HttpClient(MockEngine { respond("{}", HttpStatusCode.OK) }))
        secrets.put(OwnTmdbKey.STORE_NAME, "c".repeat(32))
        secrets.put("something-else", "x")

        var widgetRefreshes = 0
        SqlDelightLocalDataEraser(
            database,
            dispatchers,
            ownKey,
            secrets,
            widgetRefresher = WidgetRefresher { widgetRefreshes++ },
            syncBackend = NoOpSyncBackend(),
        ).deleteAllData()

        val export = Json.decodeFromString<MuvissDataExport>(repository.exportData())
        assertTrue(export.collection.isEmpty() && export.progress.isEmpty() && export.plays.isEmpty())
        assertTrue(export.triage.isEmpty() && export.lists.isEmpty() && export.snoozes.isEmpty())
        assertTrue(export.mutedGenres.isEmpty() && export.mutedGenreChoice == null, "Muted Genres decisions and the choice are the person's own data")
        assertEquals(null, database.triageDeckCursorQueries.selectByKey("movie|any|-|en-US").awaitAsOneOrNull(), "the deck's saved position is this device's, and goes too")
        assertEquals(null, database.discoverCacheQueries.selectByKey("genres:tv|en-US").awaitAsOneOrNull(), "stored Discover answers are this install's cache, and go too")
        assertEquals(null, export.profile, "the profile row is gone until something reads it again")
        assertTrue(database.syncCursorQueries.selectAll().awaitAsList().isEmpty())
        assertEquals(null, database.remoteConfigCacheQueries.selectByKey("config").awaitAsOneOrNull(), "the cached remote config is a cache of this install")
        assertEquals(null, secrets.get(OwnTmdbKey.STORE_NAME), "the Own TMDB Key goes with everything else")
        assertEquals(null, secrets.get("something-else"), "and so does any other secret: the store is wiped, not one entry")
        assertEquals(1, widgetRefreshes, "the home-screen widgets are told, or they keep showing the old library (#317)")
        database.appSettingsQueries.ensureRow()
        assertEquals("SYSTEM", database.appSettingsQueries.selectSettings().awaitAsOne().theme)
    }

    @Test
    fun `a backup choice made before deleting everything does not come back after it`() = runTest {
        val database = inMemoryDatabase()
        val writes = DeviceBackupWrites()
        val queuedIo = object : AppDispatchers {
            override val default = StandardTestDispatcher(testScheduler)
            override val io = default
        }
        val repository = SqlDelightSettingsRepository(
            database.appSettingsQueries,
            ExportQueries(database),
            queuedIo,
            FakeClock(1L),
            AppVersion("1", 1L),
            writes,
        )
        val secrets = InMemorySecretStore()
        val ownKey = OwnTmdbKey(secrets, HttpClient(MockEngine { respond("{}", HttpStatusCode.OK) }))

        // The tap parks at the hop to io; Delete all data then runs to completion before it lands.
        launch(start = CoroutineStart.UNDISPATCHED) { repository.setDeviceBackupEnabled(true) }
        SqlDelightLocalDataEraser(
            database,
            dispatchers,
            ownKey,
            secrets,
            writes,
            widgetRefresher = WidgetRefresher {},
            syncBackend = NoOpSyncBackend(),
        ).deleteAllData()
        advanceUntilIdle()

        assertEquals(null, database.appSettingsQueries.selectSettings().awaitAsOneOrNull())
    }

    @Test
    fun `erasing while signed in signs out before anything is erased and clears the owner`() = runTest {
        val database = inMemoryDatabase()
        signedIn(database)
        var ownerWhenSigningOut: String? = null
        val backend = RecordingSyncBackend {
            ownerWhenSigningOut = database.syncStateQueries.selectState().awaitAsOneOrNull()?.ownerAccountId
        }

        eraser(database, backend).deleteAllData()

        assertEquals(1, backend.signOuts)
        assertEquals("account-1", ownerWhenSigningOut, "the owner is still there at sign-out, so the erase has not started yet")
        assertEquals(null, database.syncStateQueries.selectState().awaitAsOneOrNull(), "the owner is cleared with the rest of the sync state")
        assertEquals(null, database.syncAccountQueries.selectAccount().awaitAsOne().userId, "the persisted session is gone")
    }

    @Test
    fun `a sign-out that fails offline still erases everything and clears the session`() = runTest {
        val database = inMemoryDatabase()
        signedIn(database)
        database.mediaListQueries.upsertList("list-1", "Rainy day", 1L, 1L, false, false)
        val backend = RecordingSyncBackend(failure = IllegalStateException("offline"))

        eraser(database, backend).deleteAllData()

        assertEquals(1, backend.signOuts)
        assertEquals(0, database.mediaListQueries.selectAllListsForExport().awaitAsList().size, "the library is erased although the server did not confirm the sign-out")
        assertEquals(null, database.syncStateQueries.selectState().awaitAsOneOrNull())
        assertEquals(null, database.syncAccountQueries.selectAccount().awaitAsOne().userId, "a restart must not restore the session")
    }

    @Test
    fun `the sign-out and the erase both run with no sync in flight`() = runTest {
        val database = inMemoryDatabase()
        signedIn(database)
        val events = mutableListOf<String>()
        val idle = object : SyncIdleLock {
            override suspend fun runIdle(block: suspend () -> Unit) {
                events += "idle"
                block()
                events += "released"
            }
        }
        val backend = RecordingSyncBackend { events += "sign-out" }

        eraser(database, backend, idle).deleteAllData()

        assertEquals(listOf("idle", "sign-out", "released"), events)
        assertEquals(null, database.syncStateQueries.selectState().awaitAsOneOrNull(), "erased inside the lock")
    }

    @Test
    fun `an erase whose caller is cancelled during sign-out still finishes`() = runTest {
        val database = inMemoryDatabase()
        signedIn(database)
        database.mediaListQueries.upsertList("list-1", "Rainy day", 1L, 1L, false, false)
        val backend = RecordingSyncBackend { throw CancellationException("screen closed") }

        val erase = launch { eraser(database, backend).deleteAllData() }
        erase.join()

        assertEquals(0, database.mediaListQueries.selectAllListsForExport().awaitAsList().size, "a closed screen does not leave a half-erased library")
        assertEquals(null, database.syncAccountQueries.selectAccount().awaitAsOne().userId)
    }

    private fun eraser(database: MuvissDatabase, backend: SyncBackend, idle: SyncIdleLock = SyncIdleLock.None) = SqlDelightLocalDataEraser(
        database,
        dispatchers,
        OwnTmdbKey(InMemorySecretStore(), HttpClient(MockEngine { respond("{}", HttpStatusCode.OK) })),
        InMemorySecretStore(),
        widgetRefresher = WidgetRefresher {},
        syncBackend = backend,
        syncIdle = idle,
    )

    private suspend fun signedIn(database: MuvissDatabase) {
        database.syncStateQueries.ensureRow()
        database.syncStateQueries.setOwner("account-1")
        database.syncAccountQueries.ensureRow()
        database.syncAccountQueries.saveSession("SUPABASE", "account-1", null, "token", "refresh", 1L)
    }

    /** Records each sign-out; [failure] makes it throw, as a backend would on a local failure. */
    private class RecordingSyncBackend(
        private val failure: Throwable? = null,
        private val onSignOut: suspend () -> Unit = {},
    ) : SyncBackend {
        var signOuts = 0
        override val id = SyncBackendId.SUPABASE
        override val session: Flow<SyncSession?> = flowOf(null)
        override suspend fun signInAnonymously(): Result<SyncSession> = Result.failure(UnsupportedOperationException())
        override suspend fun beginOAuth(provider: OAuthProvider, redirectUri: String): Result<String> = Result.failure(UnsupportedOperationException())
        override suspend fun completeOAuth(authCode: String): Result<SyncSession> = Result.failure(UnsupportedOperationException())
        override suspend fun signOut() {
            onSignOut()
            signOuts++
            failure?.let { throw it }
        }
        override suspend fun push(changes: SyncChangeSet): Result<Unit> = Result.success(Unit)
        override suspend fun pull(after: Map<SyncTable, SyncCursor>, onPage: suspend (SyncPage) -> Unit): Result<Unit> = Result.success(Unit)
    }
}
