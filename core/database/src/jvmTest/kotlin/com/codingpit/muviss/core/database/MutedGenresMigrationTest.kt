package com.codingpit.muviss.core.database

import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers `18.sqm`: `appSettings.mutedGenres` / `mutedGenresInSearch` (Muted Genres, ADR 0031).
 *
 * What `verifyMigrations` cannot see: that an install which already has the app comes out
 * with Talk, News and Reality muted, not an empty set, and that a fresh install gets the same.
 */
class MutedGenresMigrationTest {

    // The one place this suite knows the migration's number; see the note in
    // TriageSnoozeMigrationTest about renumbering.
    private val migratesFrom = 18L
    private val producesVersion = migratesFrom + 1

    private val fixtures = File("src/commonMain/sqldelight/databases")

    private val defaults = "tv:10767:Talk;tv:10763:News;tv:10764:Reality"

    private fun oldDriver(): JdbcSqliteDriver {
        val working = File(createTempDirectory("muviss-muted-genres-migration").toFile(), "muviss.db")
        fixtures.resolve("$migratesFrom.db").copyTo(working)
        return JdbcSqliteDriver("jdbc:sqlite:${working.absolutePath}")
    }

    private fun migrate(driver: JdbcSqliteDriver) {
        runBlocking { MuvissDatabase.Schema.synchronous().migrate(driver, oldVersion = migratesFrom, newVersion = MuvissDatabase.Schema.version) }
    }

    @Test
    fun `18_sqm produced its own fixture`() {
        assertTrue(MuvissDatabase.Schema.version >= producesVersion)
        assertTrue(fixtures.resolve("$producesVersion.db").exists(), "$producesVersion.db fixture is missing — run generateCommonMainMuvissDatabaseSchema")
    }

    @Test
    fun `an upgraded row starts with talk news and reality muted and search untouched`() {
        val driver = oldDriver()
        runBlocking { MuvissDatabase(driver).appSettingsQueries.ensureRow() } // a v18 row, as an installed app has
        migrate(driver)

        val row = MuvissDatabase(driver).appSettingsQueries.selectSettings().executeAsOne()
        driver.close()

        assertEquals(defaults, row.mutedGenres)
        assertFalse(row.mutedGenresInSearch)
    }

    @Test
    fun `a row created after the migration has the same defaults`() {
        val driver = oldDriver()
        migrate(driver)
        runBlocking { MuvissDatabase(driver).appSettingsQueries.ensureRow() }

        val row = MuvissDatabase(driver).appSettingsQueries.selectSettings().executeAsOne()
        driver.close()

        assertEquals(defaults, row.mutedGenres)
    }

    @Test
    fun `muting nothing is stored as an empty string and survives`() {
        val driver = oldDriver()
        runBlocking {
            val queries = MuvissDatabase(driver).appSettingsQueries
            queries.ensureRow()
            migrate(driver)
            queries.updateMutedGenres("")
        }

        val row = MuvissDatabase(driver).appSettingsQueries.selectSettings().executeAsOne()
        driver.close()

        assertEquals("", row.mutedGenres)
    }
}
