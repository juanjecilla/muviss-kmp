package com.codingpit.muviss.core.database

import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers `21.sqm`: `appSettings.motionDefaultApplied` (#394).
 *
 * What `verifyMigrations` cannot see: that an install which already has the app is marked as having
 * taken its motion default (so the OS setting never replaces its own choice), and that only a row
 * created afterwards, a fresh install's, starts unmarked.
 */
class MotionDefaultMigrationTest {

    // The one place this suite knows the migration's number; see the note in MutedGenresMigrationTest.
    private val migratesFrom = 21L
    private val producesVersion = migratesFrom + 1

    private val fixtures = File("src/commonMain/sqldelight/databases")

    private fun oldDriver(): JdbcSqliteDriver {
        val working = File(createTempDirectory("muviss-motion-default-migration").toFile(), "muviss.db")
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
    fun `an upgraded install is marked applied and keeps its animations choice`() {
        val driver = oldDriver()
        runBlocking {
            val queries = MuvissDatabase(driver).appSettingsQueries
            queries.ensureRow() // a v21 row, as an installed app has
            queries.updateAnimationsEnabled(true)
        }
        migrate(driver)

        val row = MuvissDatabase(driver).appSettingsQueries.selectSettings().executeAsOne()
        driver.close()

        assertTrue(row.motionDefaultApplied)
        assertTrue(row.animationsEnabled, "the migration must not reset a neighbouring preference")
    }

    @Test
    fun `a row created after the migration has not taken the default yet`() {
        val driver = oldDriver()
        migrate(driver)
        runBlocking { MuvissDatabase(driver).appSettingsQueries.ensureRow() }

        val row = MuvissDatabase(driver).appSettingsQueries.selectSettings().executeAsOne()
        driver.close()

        assertFalse(row.motionDefaultApplied)
    }
}
