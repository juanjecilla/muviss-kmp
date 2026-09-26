package com.codingpit.muviss.core.database

import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers `9.sqm` — the migration EPIC 39 (sync correctness, ADR 0020) ships:
 * the `syncCursor` and `syncState` tables and the `appSettings.syncAutomatically`
 * column.
 *
 * `verifyMigrations` proves the `.sqm` chain reproduces what the `.sq` files
 * declare. It cannot prove that a library synced under the old cursor survives,
 * and that is this migration's risk: the point of the new tables is that an
 * upgraded install starts with *no* cursor and *no* owner, so its first sync
 * does a full reconcile. Backfilling either would defeat that — a cursor taken
 * from `syncCursorEpochMs` is exactly the clock-derived value that lost rows.
 */
class SyncSchemaMigrationTest {

    // The one place this suite knows the migration's number. `9.sqm` is named
    // for the version it migrates FROM, so it produces the next one, and its
    // fixture is named for that. If EPIC 26 or 28 merges first and this file is
    // renumbered, change this constant, rename the `.sqm`, regenerate the
    // fixture, and nothing else here needs to move.
    private val migratesFrom = 9L
    private val producesVersion = migratesFrom + 1

    private val fixtures = File("src/commonMain/sqldelight/databases")

    private fun v9Driver(): Pair<JdbcSqliteDriver, MuvissDatabase> {
        val working = File(createTempDirectory("muviss-sync-schema-migration").toFile(), "muviss.db")
        fixtures.resolve("$migratesFrom.db").copyTo(working)
        val driver = JdbcSqliteDriver("jdbc:sqlite:${working.absolutePath}")
        return driver to MuvissDatabase(driver)
    }

    private fun migrate(driver: JdbcSqliteDriver) = runBlocking {
        MuvissDatabase.Schema.synchronous().migrate(driver, oldVersion = migratesFrom, newVersion = MuvissDatabase.Schema.version)
    }

    private fun columnsOf(driver: JdbcSqliteDriver, table: String): List<String> = driver.executeQuery(
        identifier = null,
        sql = "PRAGMA table_info($table)",
        mapper = { cursor ->
            val names = mutableListOf<String>()
            while (cursor.next().value) names += cursor.getString(1)!!
            QueryResult.Value(names.toList())
        },
        parameters = 0,
    ).value

    private suspend fun seedSyncedLibrary(driver: SqlDriver, database: MuvissDatabase) {
        // Raw SQL, not the generated queries: these databases are still at an
        // older version. See `seedLegacyCollectionEntry`.
        driver.seedLegacyCollectionEntry(
            mediaId = "tmdb:movie:603", mediaType = "movie", title = "The Matrix", releaseYear = 1999L,
            productionStatus = "RELEASED", totalEpisodes = 1L, airedEpisodes = 1L, favorite = true, genres = "", runtimeMinutes = null,
            addedAtEpochMs = 500L, updatedAtEpochMs = 2_000L, isDirty = false, rating = 9L, note = "keep",
        )
        driver.seedLegacyCollectionEntry(
            mediaId = "tmdb:movie:604", mediaType = "movie", title = "Reloaded", releaseYear = 2003L,
            productionStatus = "RELEASED", totalEpisodes = 1L, airedEpisodes = 1L, favorite = false, genres = "", runtimeMinutes = null,
            addedAtEpochMs = 600L, updatedAtEpochMs = 3_000L, isDirty = true, rating = null, note = null,
        )
        database.appSettingsQueries.ensureRow()
        database.appSettingsQueries.updateSyncCursor(7_000L)
        database.appSettingsQueries.updateLastSyncedAt(8_000L)
    }

    @Test
    fun `the sync migration produced its own fixture`() {
        // The .sqm is named after the version it migrates FROM, so it produces
        // the next version (ADR 0008's 2026-07-11 amendment).
        assertTrue(MuvissDatabase.Schema.version >= producesVersion)
        assertTrue(fixtures.resolve("$producesVersion.db").exists(), "$producesVersion.db fixture is missing — run generateCommonMainMuvissDatabaseSchema")
    }

    @Test
    fun `the sync tables exist, empty, after upgrading — nothing is backfilled`() {
        val (driver, database) = v9Driver()
        runBlocking { seedSyncedLibrary(driver, database) }
        migrate(driver)

        val after = MuvissDatabase(driver)
        assertEquals(emptyList(), after.syncCursorQueries.selectAll().executeAsList(), "an upgraded install has no cursor, so its first pull asks for everything")
        assertNull(after.syncStateQueries.selectState().executeAsOneOrNull(), "no owner has ever synced from here")
        driver.close()
    }

    @Test
    fun `an upgraded install gets a syncState row with no owner and no attempts`() {
        val (driver, _) = v9Driver()
        migrate(driver)
        val after = MuvissDatabase(driver)

        runBlocking { after.syncStateQueries.ensureRow() }

        val state = after.syncStateQueries.selectState().executeAsOne()
        assertNull(state.ownerAccountId)
        assertNull(state.lastOutcome)
        assertNull(state.lastError)
        assertNull(state.lastAttemptAtEpochMs)
        assertEquals(0L, state.consecutiveFailures)
        driver.close()
    }

    @Test
    fun `the library and its dirty flags come through untouched`() {
        val (driver, database) = v9Driver()
        runBlocking { seedSyncedLibrary(driver, database) }
        migrate(driver)

        val after = MuvissDatabase(driver)
        val clean = after.collectionEntryQueries.selectById("tmdb:movie:603").executeAsOne()
        val dirty = after.collectionEntryQueries.selectById("tmdb:movie:604").executeAsOne()
        assertFalse(clean.isDirty, "the migration must not decide which rows to re-push; the first sync does that")
        assertTrue(dirty.isDirty)
        assertEquals(9L, clean.rating)
        assertEquals("keep", clean.note)
        driver.close()
    }

    @Test
    fun `the old clock-derived cursor is left where it was but is no longer read`() {
        val (driver, database) = v9Driver()
        runBlocking { seedSyncedLibrary(driver, database) }
        migrate(driver)

        val settings = MuvissDatabase(driver).appSettingsQueries.selectSettings().executeAsOne()
        assertEquals(7_000L, settings.syncCursorEpochMs, "dropping it would need a table rebuild for nothing")
        assertEquals(8_000L, settings.lastSyncedAtEpochMs)
        driver.close()
    }

    @Test
    fun `syncAutomatically is the column 9_sqm appended and defaults to off for existing rows`() {
        val (driver, database) = v9Driver()
        runBlocking { seedSyncedLibrary(driver, database) }
        migrate(driver)

        // Not `.last()`: later migrations append their own columns after it (10.sqm's
        // crashReportsEnabled). What 9.sqm owns is that it came straight after the
        // previous last column, because verifyMigrations compares ordinal position.
        val columns = columnsOf(driver, "appSettings")
        assertEquals("syncCursorEpochMs", columns[columns.indexOf("syncAutomatically") - 1], "verifyMigrations compares ordinal position, and ALTER TABLE ADD COLUMN appends")
        val settings = MuvissDatabase(driver).appSettingsQueries.selectSettings().executeAsOne()
        assertFalse(settings.syncAutomatically, "background sync stays off for everyone until they choose it")
        driver.close()
    }

    @Test
    fun `the new tables have the shape the engine writes`() {
        val (driver, _) = v9Driver()
        migrate(driver)

        assertEquals(listOf("tableName", "seq"), columnsOf(driver, "syncCursor"))
        assertEquals(
            listOf("id", "ownerAccountId", "lastOutcome", "lastError", "lastAttemptAtEpochMs", "consecutiveFailures"),
            columnsOf(driver, "syncState"),
        )
        driver.close()
    }

    @Test
    fun `a cursor is one row per table and replaces rather than duplicating`() {
        val (driver, _) = v9Driver()
        migrate(driver)
        val after = MuvissDatabase(driver)

        runBlocking {
            after.syncCursorQueries.upsert("collectionEntry", 10L)
            after.syncCursorQueries.upsert("collectionEntry", 25L)
            after.syncCursorQueries.upsert("episodePlay", 3L)
        }

        assertEquals(
            mapOf("collectionEntry" to 25L, "episodePlay" to 3L),
            after.syncCursorQueries.selectAll().executeAsList().associate { it.tableName to it.seq },
        )
        driver.close()
    }
}
