package com.codingpit.muviss.core.database

import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Covers `27.sqm`: the per-device `triageDeckCursor` table (#722). Nothing to backfill; a new table starts empty. */
class TriageDeckCursorMigrationTest {

    private val migratesFrom = 27L
    private val producesVersion = migratesFrom + 1

    private val fixtures = File("src/commonMain/sqldelight/databases")

    @Test
    fun `27_sqm produced its own fixture`() {
        assertTrue(MuvissDatabase.Schema.version >= producesVersion)
        assertTrue(fixtures.resolve("$producesVersion.db").exists(), "$producesVersion.db fixture is missing — run generateCommonMainMuvissDatabaseSchema")
    }

    @Test
    fun `an upgraded install has no saved deck position yet`() {
        val working = File(createTempDirectory("muviss-deck-cursor-migration").toFile(), "muviss.db")
        fixtures.resolve("$migratesFrom.db").copyTo(working)
        val driver = JdbcSqliteDriver("jdbc:sqlite:${working.absolutePath}")
        runBlocking { MuvissDatabase.Schema.synchronous().migrate(driver, oldVersion = migratesFrom, newVersion = MuvissDatabase.Schema.version) }

        val row = MuvissDatabase(driver).triageDeckCursorQueries.selectByKey("any").executeAsOneOrNull()
        driver.close()

        assertNull(row)
    }
}
