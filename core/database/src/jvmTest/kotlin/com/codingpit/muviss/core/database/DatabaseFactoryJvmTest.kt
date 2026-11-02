package com.codingpit.muviss.core.database

import app.cash.sqldelight.TransacterImpl
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.runBlocking
import java.sql.DriverManager
import java.sql.SQLException
import java.util.Properties
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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

    /**
     * The file driver gives each thread its own connection, and two ticks on two IO threads
     * each run a read-then-write transaction (`playCountOf`, then `insertPlay`). Deferred,
     * both take a read lock first; whichever writes second then cannot upgrade without a
     * deadlock, so SQLite answers SQLITE_BUSY at once instead of waiting, and that tick is
     * rolled back. `BEGIN IMMEDIATE` makes the second transaction wait at its start instead.
     */
    @Test
    fun `two read-then-write transactions on two threads both commit`() {
        val directory = createTempDirectory("muviss-db-test").toFile()
        val driver = createFileDriver(directory)
        driver.execute(null, "CREATE TABLE tick (n INTEGER NOT NULL);", 0)
        val transacter = object : TransacterImpl(driver) {}
        val firstHasRead = CountDownLatch(1)
        val failures = CopyOnWriteArrayList<Throwable>()

        fun readThenWrite(afterRead: () -> Unit) {
            transacter.transaction {
                driver.executeQuery(null, "SELECT count(*) FROM tick;", { QueryResult.Value(it.next().value) }, 0)
                afterRead()
                driver.execute(null, "INSERT INTO tick (n) VALUES (1);", 0)
            }
        }
        val first = thread {
            runCatching {
                readThenWrite {
                    firstHasRead.countDown()
                    Thread.sleep(300)
                }
            }.onFailure(failures::add)
        }
        val second = thread {
            runCatching {
                check(firstHasRead.await(WAIT_SECONDS, TimeUnit.SECONDS)) { "the first transaction never read" }
                readThenWrite {}
            }.onFailure(failures::add)
        }
        first.join(WAIT_SECONDS * 1_000)
        second.join(WAIT_SECONDS * 1_000)
        assertFalse(first.isAlive || second.isAlive, "a transaction was still running after $WAIT_SECONDS s")

        assertEquals(emptyList(), failures.map { it.message })
        val rows = driver.executeQuery(null, "SELECT count(*) FROM tick;", { QueryResult.Value(if (it.next().value) it.getLong(0) else null) }, 0).value
        assertEquals(2L, rows)
        driver.close()
    }

    /**
     * `BEGIN IMMEDIATE` can fail where a deferred `BEGIN` could not: SQLITE_BUSY once another
     * writer holds the lock past the busy timeout. SQLDelight records the transaction before it
     * begins, so a failed begin must not leave it behind: the thread's next transaction would
     * count as nested, skip its own `BEGIN`, and run in autocommit, so throwing out of it would
     * keep its writes.
     */
    @Test
    fun `a transaction that could not begin leaves the next one able to roll back`() {
        val file = java.io.File(createTempDirectory("muviss-db-test").toFile(), "busy.db")
        val url = "jdbc:sqlite:${file.absolutePath}"
        val driver = ImmediateTransactionSqliteDriver(url, Properties().apply { setProperty("busy_timeout", "100") })
        driver.execute(null, "CREATE TABLE tick (n INTEGER NOT NULL);", 0)
        val transacter = object : TransacterImpl(driver) {}

        DriverManager.getConnection(url).use { other ->
            other.createStatement().use { it.execute("BEGIN IMMEDIATE") }
            assertFailsWith<SQLException> { transacter.transaction {} }
            other.createStatement().use { it.execute("ROLLBACK") }
        }
        assertFailsWith<IllegalStateException> {
            transacter.transaction {
                driver.execute(null, "INSERT INTO tick (n) VALUES (1);", 0)
                error("roll back")
            }
        }

        val rows = driver.executeQuery(null, "SELECT count(*) FROM tick;", { QueryResult.Value(if (it.next().value) it.getLong(0) else null) }, 0).value
        assertEquals(0L, rows)
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
     * #453: a file stamped behind its real schema (here: stamped 9 while `syncCursor`, which
     * `9.sqm` creates, already exists) used to surface as SQLite's "table syncCursor already
     * exists" from deep inside Koin. It now says which file, which versions, and what to do —
     * and never the path.
     */
    @Test
    fun `a database stamped behind its schema fails with a message that says what to do`() {
        val directory = createTempDirectory("muviss-db-test").toFile()
        createFileDriver(directory).also {
            it.execute(identifier = null, sql = "PRAGMA user_version = 9;", parameters = 0, binders = null)
            it.close()
        }

        val error = assertFailsWith<IllegalStateException> { createFileDriver(directory) }

        val message = error.message.orEmpty()
        assertTrue("muviss.db from schema version 9" in message, message)
        assertTrue("already exists" in message, message)
        assertTrue("move it aside" in message, message)
        assertTrue(directory.absolutePath !in message, "the message must not carry the path: $message")
        assertTrue(error.cause is java.sql.SQLException)
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

        val firstDriver = createFileDriver(directory)
        assertEquals(MuvissDatabase.Schema.version, firstDriver.userVersion())
        firstDriver.close()

        // Reopening a database already stamped at the current schema version
        // must be a no-op: it must not attempt Schema.migrate(driver, 0, N)
        // again, which would replay every `.sqm` file against a database
        // that already has those columns/tables (and blow up on the first
        // non-idempotent statement, e.g. `ALTER TABLE ADD COLUMN`).
        val secondDriver = createFileDriver(directory)
        assertEquals(MuvissDatabase.Schema.version, secondDriver.userVersion())
        secondDriver.close()
    }

    private fun SqlDriver.userVersion(): Long = executeQuery(
        identifier = null,
        sql = "PRAGMA user_version;",
        mapper = { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getLong(0) ?: 0L else 0L) },
        parameters = 0,
        binders = null,
    ).value

    private companion object {
        const val WAIT_SECONDS = 10L
    }
}
