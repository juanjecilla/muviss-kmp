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
 * Covers `23.sqm`: the three Next Pick settings on `appSettings` (EPIC 51, #403).
 *
 * What `verifyMigrations` cannot see: that an install which already has the app comes out with the
 * prompt on, Standard and One Pick (the column DEFAULTs, with no UPDATE), and that a fresh row matches.
 */
class NextPickSettingsMigrationTest {

    // The one place this suite knows the migration's number; see the note in DeviceBackupMigrationTest.
    private val migratesFrom = 23L
    private val producesVersion = migratesFrom + 1

    private val fixtures = File("src/commonMain/sqldelight/databases")

    private fun oldDriver(): JdbcSqliteDriver {
        val working = File(createTempDirectory("muviss-next-pick-migration").toFile(), "muviss.db")
        fixtures.resolve("$migratesFrom.db").copyTo(working)
        return JdbcSqliteDriver("jdbc:sqlite:${working.absolutePath}")
    }

    private fun migrate(driver: JdbcSqliteDriver) {
        runBlocking { MuvissDatabase.Schema.synchronous().migrate(driver, oldVersion = migratesFrom, newVersion = MuvissDatabase.Schema.version) }
    }

    @Test
    fun `23_sqm produced its own fixture`() {
        assertTrue(MuvissDatabase.Schema.version >= producesVersion)
        assertTrue(fixtures.resolve("$producesVersion.db").exists(), "$producesVersion.db fixture is missing — run generateCommonMainMuvissDatabaseSchema")
    }

    @Test
    fun `an upgraded install has the prompt on, Standard and One Pick, and neighbours untouched`() {
        val driver = oldDriver()
        runBlocking {
            val queries = MuvissDatabase(driver).appSettingsQueries
            queries.ensureRow() // a v23 row, as an installed app has
            queries.updateMutedGenresInSearch(true)
        }
        migrate(driver)

        val row = MuvissDatabase(driver).appSettingsQueries.selectSettings().executeAsOne()
        driver.close()

        assertTrue(row.completionPromptEnabled)
        assertEquals("STANDARD", row.nextPickSessionLength)
        assertEquals("ONE_PICK", row.nextPickResultMode)
        assertTrue(row.mutedGenresInSearch, "the migration must not reset a neighbouring preference")
    }

    @Test
    fun `a row created after the migration has the same defaults`() {
        val driver = oldDriver()
        migrate(driver)
        runBlocking { MuvissDatabase(driver).appSettingsQueries.ensureRow() }

        val row = MuvissDatabase(driver).appSettingsQueries.selectSettings().executeAsOne()
        driver.close()

        assertTrue(row.completionPromptEnabled)
        assertEquals("STANDARD", row.nextPickSessionLength)
        assertEquals("ONE_PICK", row.nextPickResultMode)
    }

    @Test
    fun `each setting persists and survives a reopen`() {
        val driver = oldDriver()
        migrate(driver)
        runBlocking {
            val queries = MuvissDatabase(driver).appSettingsQueries
            queries.ensureRow()
            queries.updateCompletionPromptEnabled(false)
            queries.updateNextPickSessionLength("NONE")
            queries.updateNextPickResultMode("DECK")
        }

        val row = MuvissDatabase(driver).appSettingsQueries.selectSettings().executeAsOne()
        driver.close()

        assertFalse(row.completionPromptEnabled)
        assertEquals("NONE", row.nextPickSessionLength)
        assertEquals("DECK", row.nextPickResultMode)
    }
}
