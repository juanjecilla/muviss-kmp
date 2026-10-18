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
 * Covers `19.sqm`: `appSettings.deviceBackupEnabled` (Device Backup, ADR 0034).
 *
 * What `verifyMigrations` cannot see: that an install which already has the app comes out with the
 * backup switch **off** (the OS backs nothing up until the person asks), and that a fresh install does too.
 */
class DeviceBackupMigrationTest {

    // The one place this suite knows the migration's number; see the note in MutedGenresMigrationTest.
    private val migratesFrom = 19L
    private val producesVersion = migratesFrom + 1

    private val fixtures = File("src/commonMain/sqldelight/databases")

    private fun oldDriver(): JdbcSqliteDriver {
        val working = File(createTempDirectory("muviss-device-backup-migration").toFile(), "muviss.db")
        fixtures.resolve("$migratesFrom.db").copyTo(working)
        return JdbcSqliteDriver("jdbc:sqlite:${working.absolutePath}")
    }

    private fun migrate(driver: JdbcSqliteDriver) {
        runBlocking { MuvissDatabase.Schema.synchronous().migrate(driver, oldVersion = migratesFrom, newVersion = MuvissDatabase.Schema.version) }
    }

    @Test
    fun `19_sqm produced its own fixture`() {
        assertTrue(MuvissDatabase.Schema.version >= producesVersion)
        assertTrue(fixtures.resolve("$producesVersion.db").exists(), "$producesVersion.db fixture is missing — run generateCommonMainMuvissDatabaseSchema")
    }

    @Test
    fun `an upgraded install has device backup off and every other setting untouched`() {
        val driver = oldDriver()
        runBlocking {
            val queries = MuvissDatabase(driver).appSettingsQueries
            queries.ensureRow() // a v19 row, as an installed app has
            queries.updateMutedGenresInSearch(true)
        }
        migrate(driver)

        val row = MuvissDatabase(driver).appSettingsQueries.selectSettings().executeAsOne()
        driver.close()

        assertFalse(row.deviceBackupEnabled)
        assertTrue(row.mutedGenresInSearch, "the migration must not reset a neighbouring preference")
    }

    @Test
    fun `a row created after the migration is off as well`() {
        val driver = oldDriver()
        migrate(driver)
        runBlocking { MuvissDatabase(driver).appSettingsQueries.ensureRow() }

        val row = MuvissDatabase(driver).appSettingsQueries.selectSettings().executeAsOne()
        driver.close()

        assertFalse(row.deviceBackupEnabled)
    }

    @Test
    fun `turning it on persists and survives a reopen`() {
        val driver = oldDriver()
        migrate(driver)
        runBlocking {
            val queries = MuvissDatabase(driver).appSettingsQueries
            queries.ensureRow()
            queries.updateDeviceBackupEnabled(true)
        }

        val row = MuvissDatabase(driver).appSettingsQueries.selectSettings().executeAsOne()
        driver.close()

        assertEquals(true, row.deviceBackupEnabled)
    }
}
