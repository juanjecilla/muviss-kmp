package com.codingpit.muviss.core.database

import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import java.io.File
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * A desktop open that stops part-way through a migration must leave the file as it was (#1026's review).
 *
 * `30.sqm` rebuilds `appSettings` in four statements. On a plain driver each one committed on its own, so a stop
 * between them left a half-built schema that failed the next launch. The factory now runs create-or-migrate and the
 * version stamp in one transaction. These tests use a schema that creates a table and then fails, because a real
 * migration cannot be made to fail on demand.
 */
class MigrationAtomicityTest {

    /** Version 1: one table. */
    private object V1 : SqlSchema<QueryResult.Value<Unit>> {
        override val version: Long = 1L
        override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
            driver.execute(null, "CREATE TABLE marker (id INTEGER NOT NULL)", 0, null)
            return QueryResult.Unit
        }
        override fun migrate(driver: SqlDriver, oldVersion: Long, newVersion: Long, vararg callbacks: AfterVersion): QueryResult.Value<Unit> = QueryResult.Unit
    }

    /** Version 2: the migration creates a second table, then fails the way a stopped or broken statement would. */
    private object V2Failing : SqlSchema<QueryResult.Value<Unit>> {
        override val version: Long = 2L
        override fun create(driver: SqlDriver): QueryResult.Value<Unit> = V1.create(driver)
        override fun migrate(driver: SqlDriver, oldVersion: Long, newVersion: Long, vararg callbacks: AfterVersion): QueryResult.Value<Unit> {
            driver.execute(null, "CREATE TABLE second (id INTEGER NOT NULL)", 0, null)
            throw SQLException("stopped part-way through the migration")
        }
    }

    /** Version 2, the same migration without the failure. */
    private object V2Ok : SqlSchema<QueryResult.Value<Unit>> {
        override val version: Long = 2L
        override fun create(driver: SqlDriver): QueryResult.Value<Unit> = V1.create(driver)
        override fun migrate(driver: SqlDriver, oldVersion: Long, newVersion: Long, vararg callbacks: AfterVersion): QueryResult.Value<Unit> {
            driver.execute(null, "CREATE TABLE second (id INTEGER NOT NULL)", 0, null)
            return QueryResult.Unit
        }
    }

    private fun freshDirectory(): File = createTempDirectory("muviss-migration-atomicity").toFile()

    private fun File.tables(): Set<String> = DriverManager.getConnection("jdbc:sqlite:$absolutePath").use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table'").use { rows ->
                buildSet { while (rows.next()) add(rows.getString(1)) }
            }
        }
    }

    private fun File.userVersion(): Long = DriverManager.getConnection("jdbc:sqlite:$absolutePath").use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA user_version").use { rows ->
                rows.next()
                rows.getLong(1)
            }
        }
    }

    @Test
    fun `a migration that stops part-way leaves the old schema and version, and the next open runs it again`() {
        val directory = freshDirectory()
        val file = File(directory, "muviss.db")
        createFileDriver(directory, V1).close()

        assertFailsWith<IllegalStateException> { createFileDriver(directory, V2Failing) }

        assertEquals(setOf("marker"), file.tables(), "the table the failed migration created must be rolled back")
        assertEquals(1L, file.userVersion(), "the version stamp is part of the same transaction, so it stays at 1")

        createFileDriver(directory, V2Ok).close()

        assertEquals(setOf("marker", "second"), file.tables())
        assertEquals(2L, file.userVersion())
    }
}
