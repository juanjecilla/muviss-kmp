package com.codingpit.muviss.core.database

import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers `15.sqm` — EPIC 28's (#70) indexes. `verifyMigrations` proves the
 * chain reproduces the `.sq` schema; this proves an upgraded install keeps its
 * rows and that SQLite actually plans the reads through the new indexes.
 */
class QueryIndexMigrationTest {

    // The one place this suite knows the migration's number; see the note in
    // TriageSnoozeMigrationTest about renumbering.
    private val migratesFrom = 15L
    private val producesVersion = migratesFrom + 1

    private val fixtures = File("src/commonMain/sqldelight/databases")

    private fun upgraded(): JdbcSqliteDriver {
        val working = File(createTempDirectory("muviss-index-migration").toFile(), "muviss.db")
        fixtures.resolve("$migratesFrom.db").copyTo(working)
        val driver = JdbcSqliteDriver("jdbc:sqlite:${working.absolutePath}")
        driver.execute(null, "INSERT INTO listEntry(listId, mediaId, addedAtEpochMs, isDirty, deleted, updatedAtEpochMs) VALUES ('l', 'tmdb:movie:1', 0, 1, 0, 0)", 0)
        runBlocking { MuvissDatabase.Schema.synchronous().migrate(driver, oldVersion = migratesFrom, newVersion = MuvissDatabase.Schema.version) }
        return driver
    }

    private fun JdbcSqliteDriver.plan(sql: String): String = executeQuery(
        null,
        "EXPLAIN QUERY PLAN $sql",
        { cursor ->
            val rows = buildList { while (cursor.next().value) add(cursor.getString(3).orEmpty()) }
            QueryResult.Value(rows.joinToString(" | "))
        },
        0,
    ).value

    @Test
    fun `15_sqm produced its own fixture`() {
        assertTrue(MuvissDatabase.Schema.version >= producesVersion)
        assertTrue(fixtures.resolve("$producesVersion.db").exists(), "$producesVersion.db fixture is missing — run generateCommonMainMuvissDatabaseSchema")
    }

    @Test
    fun `an upgrade keeps its rows`() {
        val driver = upgraded()
        assertEquals(1, MuvissDatabase(driver).mediaListQueries.selectDirtyEntries().executeAsList().size)
        driver.close()
    }

    @Test
    fun `the lookup by title and every dirty read use the new indexes`() {
        val driver = upgraded()
        assertTrue("listEntry_mediaId" in driver.plan("SELECT * FROM listEntry WHERE mediaId = 'x'"))
        listOf(
            "collectionEntry", "episodeProgress", "episodePlay", "triageDecision", "triageSnooze",
            "mediaList", "listEntry", "companionLink", "companionPoolOut",
        ).forEach { table ->
            val plan = driver.plan("SELECT * FROM $table WHERE isDirty = 1")
            assertTrue("${table}_dirty" in plan, "$table: $plan")
        }
        driver.close()
    }
}
