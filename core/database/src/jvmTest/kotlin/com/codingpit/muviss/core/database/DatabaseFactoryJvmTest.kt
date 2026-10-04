package com.codingpit.muviss.core.database

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.runBlocking
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers the desktop file-backed driver's create-or-open behaviour (EPIC 10)
 * against a throwaway temp directory — never the real per-user app-data
 * directory [appDataDirectory] resolves to, so this stays hermetic like every
 * other jvmTest in the repo.
 */
class DatabaseFactoryJvmTest {

    @Test
    fun `first open creates the database file and the schema`() {
        val directory = createTempDirectory("muviss-db-test").toFile()

        val driver = createFileDriver(directory)
        val database = MuvissDatabase(driver)

        assertTrue(java.io.File(directory, "muviss.db").exists())
        // Schema was created: a query against a real table succeeds with no rows yet.
        assertEquals(null, database.appSettingsQueries.selectSettings().executeAsOneOrNull())

        driver.close()
    }

    @Test
    fun `data survives closing and reopening the same directory`() {
        val directory = createTempDirectory("muviss-db-test").toFile()

        val firstDriver = createFileDriver(directory)
        runBlocking {
            MuvissDatabase(firstDriver).appSettingsQueries.apply {
                ensureRow()
                updateTheme("DARK")
            }
        }
        firstDriver.close()

        // A brand-new factory call against the same directory should open the
        // existing file rather than creating a fresh (empty) one.
        val secondDriver = createFileDriver(directory)
        val reopened = MuvissDatabase(secondDriver).appSettingsQueries.selectSettings().executeAsOne()

        assertEquals("DARK", reopened.theme)
        secondDriver.close()
    }

    /**
     * Desktop opens the database twice at startup, on two threads: crash
     * reporting reads its consent on a background dispatcher (#124) while
     * `main` builds the Koin graph. Both used to see no file, both ran
     * `Schema.create`, and the loser died with "table appSettings already
     * exists" — CI's packaged-app smoke launch, every time, once the timing
     * shifted (EPIC 31's PR). Repeated because one round can pass by luck.
     */
    @Test
    fun `concurrent first opens of one directory both succeed`() {
        repeat(20) {
            val directory = createTempDirectory("muviss-db-race").toFile()
            val start = java.util.concurrent.CountDownLatch(1)
            val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
            val opens = List(2) {
                pool.submit<SqlDriver> {
                    start.await()
                    createFileDriver(directory)
                }
            }
            start.countDown()
            val drivers = opens.map { it.get() }
            pool.shutdown()

            drivers.forEach { driver ->
                assertEquals(null, MuvissDatabase(driver).appSettingsQueries.selectSettings().executeAsOneOrNull())
                driver.close()
            }
        }
    }

    @Test
    fun `appDataDirectory always ends with the app name`() {
        assertEquals("Muviss", appDataDirectory().name)
    }

    @Test
    fun `create stamps PRAGMA user_version so reopening does not re-run migrate`() {
        val directory = createTempDirectory("muviss-db-test").toFile()

        val firstDriver = createFileDriver(directory) as JdbcSqliteDriver
        assertEquals(MuvissDatabase.Schema.version, firstDriver.userVersion())
        firstDriver.close()

        // Reopening a database already stamped at the current schema version
        // must be a no-op: it must not attempt Schema.migrate(driver, 0, N)
        // again, which would replay every `.sqm` file against a database
        // that already has those columns/tables (and blow up on the first
        // non-idempotent statement, e.g. `ALTER TABLE ADD COLUMN`).
        val secondDriver = createFileDriver(directory) as JdbcSqliteDriver
        assertEquals(MuvissDatabase.Schema.version, secondDriver.userVersion())
        secondDriver.close()
    }

    private fun JdbcSqliteDriver.userVersion(): Long = executeQuery(
        identifier = null,
        sql = "PRAGMA user_version;",
        mapper = { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getLong(0) ?: 0L else 0L) },
        parameters = 0,
        binders = null,
    ).value
}
