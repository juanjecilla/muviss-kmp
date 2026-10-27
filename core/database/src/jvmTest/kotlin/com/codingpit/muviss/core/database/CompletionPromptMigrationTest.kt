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
 * Covers `21.sqm`: `appSettings.completionPromptShownAtEpochMs` (Next Pick, EPIC 51 #400).
 *
 * What `verifyMigrations` cannot see: that an existing install reads "never shown" (null), so its first
 * Completion may open the sheet, and that the migration leaves neighbouring settings alone.
 */
class CompletionPromptMigrationTest {

    // The one place this suite knows the migration's number; see the note in MutedGenresMigrationTest.
    private val migratesFrom = 21L
    private val producesVersion = migratesFrom + 1

    private val fixtures = File("src/commonMain/sqldelight/databases")

    private fun oldDriver(): JdbcSqliteDriver {
        val working = File(createTempDirectory("muviss-completion-prompt-migration").toFile(), "muviss.db")
        fixtures.resolve("$migratesFrom.db").copyTo(working)
        return JdbcSqliteDriver("jdbc:sqlite:${working.absolutePath}")
    }

    private fun migrate(driver: JdbcSqliteDriver) {
        runBlocking { MuvissDatabase.Schema.synchronous().migrate(driver, oldVersion = migratesFrom, newVersion = MuvissDatabase.Schema.version) }
    }

    @Test
    fun `21_sqm produced its own fixture`() {
        assertTrue(MuvissDatabase.Schema.version >= producesVersion)
        assertTrue(fixtures.resolve("$producesVersion.db").exists(), "$producesVersion.db fixture is missing — run generateCommonMainMuvissDatabaseSchema")
    }

    @Test
    fun `an upgraded install has never shown the sheet and keeps its other settings`() {
        val driver = oldDriver()
        runBlocking {
            val queries = MuvissDatabase(driver).appSettingsQueries
            queries.ensureRow() // a v21 row, as an installed app has
            queries.updateMutedGenresInSearch(true)
        }
        migrate(driver)

        val row = MuvissDatabase(driver).appSettingsQueries.selectSettings().executeAsOne()
        driver.close()

        assertNull(row.completionPromptShownAtEpochMs)
        assertTrue(row.mutedGenresInSearch, "the migration must not reset a neighbouring preference")
    }

    @Test
    fun `a shown time persists and survives a reopen`() {
        val driver = oldDriver()
        migrate(driver)
        runBlocking {
            val queries = MuvissDatabase(driver).appSettingsQueries
            queries.ensureRow()
            queries.updateCompletionPromptShownAt(1_700_000_000_000L)
        }

        val row = MuvissDatabase(driver).appSettingsQueries.selectSettings().executeAsOne()
        driver.close()

        assertEquals(1_700_000_000_000L, row.completionPromptShownAtEpochMs)
    }
}
