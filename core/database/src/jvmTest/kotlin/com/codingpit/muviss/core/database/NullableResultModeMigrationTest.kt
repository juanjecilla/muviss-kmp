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
 * Covers `30.sqm`: `appSettings.nextPickResultMode` becomes nullable, so NULL is "never chose" (#853).
 *
 * The migration rebuilds the table (SQLite cannot drop NOT NULL with ALTER), so what matters beyond the
 * fixture is that every stored value and every neighbouring column survives, and that a fresh row from any
 * `ensureRow` caller is NULL rather than a seeded mode.
 */
class NullableResultModeMigrationTest {

    // The one place this suite knows the migration's number; see the note in NextPickSettingsMigrationTest.
    private val migratesFrom = 30L
    private val producesVersion = migratesFrom + 1

    private val fixtures = File("src/commonMain/sqldelight/databases")

    private fun oldDriver(): JdbcSqliteDriver {
        val working = File(createTempDirectory("muviss-result-mode-migration").toFile(), "muviss.db")
        fixtures.resolve("$migratesFrom.db").copyTo(working)
        return JdbcSqliteDriver("jdbc:sqlite:${working.absolutePath}")
    }

    private fun migrate(driver: JdbcSqliteDriver) {
        runBlocking { MuvissDatabase.Schema.synchronous().migrate(driver, oldVersion = migratesFrom, newVersion = MuvissDatabase.Schema.version) }
    }

    @Test
    fun `30_sqm produced its own fixture`() {
        assertTrue(MuvissDatabase.Schema.version >= producesVersion)
        assertTrue(fixtures.resolve("$producesVersion.db").exists(), "$producesVersion.db fixture is missing — run generateCommonMainMuvissDatabaseSchema")
    }

    @Test
    fun `an existing row keeps a stored One Pick, Top Picks or Deck choice`() {
        for (stored in listOf("ONE_PICK", "TOP_PICKS", "DECK")) {
            val driver = oldDriver()
            runBlocking {
                val queries = MuvissDatabase(driver).appSettingsQueries
                queries.ensureRow()
                queries.updateNextPickResultMode(stored)
            }
            migrate(driver)

            val row = MuvissDatabase(driver).appSettingsQueries.selectSettings().executeAsOne()
            driver.close()

            assertEquals(stored, row.nextPickResultMode, "a stored $stored must survive the rebuild")
        }
    }

    @Test
    fun `a row written under the old default keeps One Pick, which the header accepts`() {
        val driver = oldDriver()
        runBlocking { MuvissDatabase(driver).appSettingsQueries.ensureRow() }
        migrate(driver)

        // The v30 row was created under the old NOT NULL DEFAULT, so it reads as a stored ONE_PICK: the accepted
        // outcome in 30.sqm's header. What matters here is that the migration does not invent a value of its own.
        val row = MuvissDatabase(driver).appSettingsQueries.selectSettings().executeAsOne()
        driver.close()

        assertEquals("ONE_PICK", row.nextPickResultMode)
    }

    @Test
    fun `a fresh row from ensureRow is never-chose, NULL, after the migration`() {
        val driver = oldDriver()
        migrate(driver)
        runBlocking { MuvissDatabase(driver).appSettingsQueries.ensureRow() }

        val row = MuvissDatabase(driver).appSettingsQueries.selectSettings().executeAsOne()
        driver.close()

        assertNull(row.nextPickResultMode)
        assertEquals("STANDARD", row.nextPickSessionLength)
    }

    @Test
    fun `a later ensureRow never overwrites a stored choice`() {
        val driver = oldDriver()
        migrate(driver)
        runBlocking {
            val queries = MuvissDatabase(driver).appSettingsQueries
            queries.ensureRow()
            queries.updateNextPickResultMode("TOP_PICKS")
            queries.ensureRow()
        }

        val row = MuvissDatabase(driver).appSettingsQueries.selectSettings().executeAsOne()
        driver.close()

        assertEquals("TOP_PICKS", row.nextPickResultMode)
    }

    @Test
    fun `the rebuild keeps the neighbouring columns of an installed row`() {
        val driver = oldDriver()
        runBlocking {
            val queries = MuvissDatabase(driver).appSettingsQueries
            queries.ensureRow()
            queries.updateMutedGenresInSearch(true)
            queries.updateNextPickSessionLength("NONE")
            queries.updateCompletionPromptEnabled(false)
        }
        migrate(driver)

        val row = MuvissDatabase(driver).appSettingsQueries.selectSettings().executeAsOne()
        driver.close()

        assertTrue(row.mutedGenresInSearch)
        assertEquals("NONE", row.nextPickSessionLength)
        assertEquals(false, row.completionPromptEnabled)
        assertEquals(0L, row.id)
    }
}
