package com.codingpit.muviss.core.database

import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers `21.sqm`, which EPIC 51 (#402, Taste Profile) ships: the `tasteProfile`
 * table. `verifyMigrations` proves the `.sqm` reproduces the `.sq`; this suite
 * proves what an upgraded person would notice: nothing is invented for them,
 * their settings are untouched, and an item written after the upgrade round-trips
 * with its soft delete intact.
 */
class TasteProfileMigrationTest {

    // `21.sqm` is named for the version it migrates FROM. If it is renumbered
    // (see its header), change this constant and rename the file.
    private val migratesFrom = 21L
    private val producesVersion = migratesFrom + 1

    private val fixtures = File("src/commonMain/sqldelight/databases")

    private fun oldDriver(): Pair<JdbcSqliteDriver, MuvissDatabase> {
        val working = File(createTempDirectory("muviss-taste-profile-migration").toFile(), "muviss.db")
        fixtures.resolve("$migratesFrom.db").copyTo(working)
        val driver = JdbcSqliteDriver("jdbc:sqlite:${working.absolutePath}")
        return driver to MuvissDatabase(driver)
    }

    private fun migrate(driver: JdbcSqliteDriver) = runBlocking {
        MuvissDatabase.Schema.synchronous().migrate(driver, oldVersion = migratesFrom, newVersion = MuvissDatabase.Schema.version)
    }

    @Test
    fun `21_sqm produced its own fixture`() {
        assertTrue(MuvissDatabase.Schema.version >= producesVersion)
        assertTrue(
            fixtures.resolve("$producesVersion.db").exists(),
            "$producesVersion.db fixture is missing — run generateCommonMainMuvissDatabaseSchema",
        )
    }

    @Test
    fun `an upgraded install has no taste profile and cannot have one`() {
        val (driver, _) = oldDriver()
        migrate(driver)

        // Nothing is backfilled: before this migration nobody could say what they like.
        assertEquals(0, MuvissDatabase(driver).tasteProfileQueries.selectAll().executeAsList().size)
        driver.close()
    }

    @Test
    fun `an upgraded install keeps its settings untouched`() {
        val (driver, database) = oldDriver()

        runBlocking {
            database.appSettingsQueries.ensureRow()
            database.appSettingsQueries.updateTheme("DARK")
            database.appSettingsQueries.updateLanguage("es-ES")
        }
        migrate(driver)

        val settings = MuvissDatabase(driver).appSettingsQueries.selectSettings().executeAsOne()
        assertEquals("DARK", settings.theme)
        assertEquals("es-ES", settings.language)
        driver.close()
    }

    @Test
    fun `an item written after migrating round-trips and a soft delete hides it`() {
        val (driver, _) = oldDriver()
        migrate(driver)
        val database = MuvissDatabase(driver)

        runBlocking {
            database.tasteProfileQueries.upsert(
                id = "GENRE:movie:28",
                kind = "GENRE",
                value = "movie:28",
                label = "Action",
                updatedAtEpochMs = 1_000L,
                isDirty = true,
                deleted = false,
            )
        }
        assertEquals("Action", database.tasteProfileQueries.selectAll().executeAsOne().label)

        // Soft, not hard: last-write-wins cannot express a hard delete.
        runBlocking { database.tasteProfileQueries.softDelete(now = 2_000L, id = "GENRE:movie:28") }
        assertEquals(0, database.tasteProfileQueries.selectAll().executeAsList().size)
        assertTrue(database.tasteProfileQueries.selectById("GENRE:movie:28").executeAsOne().deleted)
        driver.close()
    }
}
