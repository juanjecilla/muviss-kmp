package com.codingpit.muviss.core.database

import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers `16.sqm` — EPIC 45's (#297) config cache. `verifyMigrations` proves the
 * chain reproduces the `.sq` schema; this proves an upgraded install keeps its
 * rows and gets a usable, empty table.
 */
class RemoteConfigCacheMigrationTest {

    // The one place this suite knows the migration's number; renumber it with the file.
    private val migratesFrom = 16L
    private val producesVersion = migratesFrom + 1

    private val fixtures = File("src/commonMain/sqldelight/databases")

    private fun upgraded(): JdbcSqliteDriver {
        val working = File(createTempDirectory("muviss-remote-config-migration").toFile(), "muviss.db")
        fixtures.resolve("$migratesFrom.db").copyTo(working)
        val driver = JdbcSqliteDriver("jdbc:sqlite:${working.absolutePath}")
        driver.execute(null, "INSERT INTO listEntry(listId, mediaId, addedAtEpochMs, isDirty, deleted, updatedAtEpochMs) VALUES ('l', 'tmdb:movie:1', 0, 1, 0, 0)", 0)
        runBlocking { MuvissDatabase.Schema.synchronous().migrate(driver, oldVersion = migratesFrom, newVersion = MuvissDatabase.Schema.version) }
        return driver
    }

    @Test
    fun `16_sqm produced its own fixture`() {
        assertTrue(MuvissDatabase.Schema.version >= producesVersion)
        assertTrue(fixtures.resolve("$producesVersion.db").exists(), "$producesVersion.db fixture is missing — run generateCommonMainMuvissDatabaseSchema")
    }

    @Test
    fun `an upgrade keeps its rows and starts with an empty cache`() {
        val driver = upgraded()
        val db = MuvissDatabase(driver)
        assertEquals(1, db.mediaListQueries.selectDirtyEntries().executeAsList().size)
        assertNull(db.remoteConfigCacheQueries.selectByKey("k").executeAsOneOrNull())
        driver.close()
    }

    @Test
    fun `a stored value is replaced, not duplicated, and deleteAll empties the table`() = runBlocking {
        val driver = upgraded()
        val queries = MuvissDatabase(driver).remoteConfigCacheQueries
        queries.upsert("k", "one")
        queries.upsert("k", "two")
        assertEquals("two", queries.selectByKey("k").executeAsOne())
        queries.deleteAll()
        assertNull(queries.selectByKey("k").executeAsOneOrNull())
        driver.close()
    }
}
