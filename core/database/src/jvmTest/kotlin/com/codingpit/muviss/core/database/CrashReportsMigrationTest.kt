package com.codingpit.muviss.core.database

import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers `10.sqm` — the migration EPIC 26 (crash reporting, #68) ships: the
 * `appSettings.crashReportsEnabled` column.
 *
 * `verifyMigrations` proves the `.sqm` chain reproduces what the `.sq` files
 * declare. What it cannot prove is the behaviour that matters to a person: that
 * an install which upgrades keeps reporting exactly as it did (default ON), and
 * that nothing already in the row is disturbed on the way.
 */
class CrashReportsMigrationTest {

    // The one place this suite knows the migration's number. `10.sqm` is named
    // for the version it migrates FROM, so it produces the next one, and its
    // fixture is named for that. EPIC 39 took `9.sqm` first; if this is ever
    // renumbered, change this constant, rename the `.sqm`, regenerate the
    // fixture, and nothing else here needs to move.
    private val migratesFrom = 10L
    private val producesVersion = migratesFrom + 1

    private val fixtures = File("src/commonMain/sqldelight/databases")

    private fun oldDriver(): Pair<JdbcSqliteDriver, MuvissDatabase> {
        val working = File(createTempDirectory("muviss-crash-reports-migration").toFile(), "muviss.db")
        fixtures.resolve("$migratesFrom.db").copyTo(working)
        val driver = JdbcSqliteDriver("jdbc:sqlite:${working.absolutePath}")
        return driver to MuvissDatabase(driver)
    }

    private fun migrate(driver: JdbcSqliteDriver) = runBlocking {
        MuvissDatabase.Schema.synchronous().migrate(driver, oldVersion = migratesFrom, newVersion = MuvissDatabase.Schema.version)
    }

    @Test
    fun `10_sqm produced its own fixture`() {
        // Deliberately `>=`: later migrations move the schema on, and only the
        // newest one's test pins the current number.
        assertTrue(MuvissDatabase.Schema.version >= producesVersion)
        assertTrue(
            fixtures.resolve("$producesVersion.db").exists(),
            "$producesVersion.db fixture is missing — run generateCommonMainMuvissDatabaseSchema",
        )
    }

    @Test
    fun `an install from before the setting keeps reporting`() {
        val (driver, database) = oldDriver()

        runBlocking { database.appSettingsQueries.ensureRow() }
        migrate(driver)

        // Reporting was on for every release build with a DSN before this
        // setting existed. Upgrading is not the moment to switch it off, and it
        // is not a consent shortcut either: the way to turn it off now exists.
        assertTrue(MuvissDatabase(driver).appSettingsQueries.selectSettings().executeAsOne().crashReportsEnabled)
        driver.close()
    }

    @Test
    fun `existing settings survive the upgrade untouched`() {
        val (driver, database) = oldDriver()

        runBlocking {
            database.appSettingsQueries.ensureRow()
            database.appSettingsQueries.updateTheme("DARK")
            database.appSettingsQueries.updateLanguage("es-ES")
            database.appSettingsQueries.updateNotificationsEnabled(false)
            database.appSettingsQueries.updateTriageControlScheme("THREE_WAY")
        }
        migrate(driver)

        val settings = MuvissDatabase(driver).appSettingsQueries.selectSettings().executeAsOne()
        assertEquals("DARK", settings.theme)
        assertEquals("es-ES", settings.language)
        assertFalse(settings.notificationsEnabled)
        assertEquals("THREE_WAY", settings.triageControlScheme)
        assertFalse(settings.syncAutomatically)
        driver.close()
    }

    @Test
    fun `the setting sits after the column that preceded it, where an ALTER TABLE puts it`() {
        val (driver, _) = oldDriver()
        migrate(driver)

        val columns = driver.executeQuery(
            identifier = null,
            sql = "PRAGMA table_info(appSettings)",
            mapper = { cursor ->
                val names = mutableListOf<String>()
                while (cursor.next().value) names += cursor.getString(1)!!
                QueryResult.Value(names.toList())
            },
            parameters = 0,
        ).value

        // Asserted relative to its predecessor rather than as `columns.last()`:
        // `verifyMigrations` compares ordinal position, so what matters is that
        // `10.sqm` appended this column after the one `9.sqm` added. It was the
        // last column when this test was written, and EPIC 41's `11.sqm` then
        // appended two more — which is correct, and which `columns.last()`
        // would have reported as this migration breaking.
        assertEquals(
            listOf("syncAutomatically", "crashReportsEnabled"),
            columns.dropWhile { it != "syncAutomatically" }.take(2),
        )
        driver.close()
    }

    @Test
    fun `the setting round-trips after migrating`() {
        val (driver, _) = oldDriver()
        migrate(driver)
        val database = MuvissDatabase(driver)

        runBlocking {
            database.appSettingsQueries.ensureRow()
            database.appSettingsQueries.updateCrashReportsEnabled(false)
        }
        assertFalse(database.appSettingsQueries.selectSettings().executeAsOne().crashReportsEnabled)

        runBlocking { database.appSettingsQueries.updateCrashReportsEnabled(true) }
        assertTrue(database.appSettingsQueries.selectSettings().executeAsOne().crashReportsEnabled)
        driver.close()
    }
}
