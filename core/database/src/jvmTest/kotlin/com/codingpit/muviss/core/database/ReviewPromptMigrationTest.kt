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
 * Covers `21.sqm`: the four store-review counters on `appSettings` (#410).
 *
 * The starting point is the v21 fixture, which is what `develop` ships today, so this is the upgrade a
 * real install takes, one migration step to v22. Its neighbours must come through untouched, and an upgraded install must start
 * unstamped (not eligible) with no ticks and no ask, so the prompt can never fire on the day of upgrade.
 *
 * NUMBERING: PR #543 and PR #553 also claim `21.sqm`; the merge queue renumbers whichever lands later.
 */
class ReviewPromptMigrationTest {

    // The one place this suite knows the starting version; see the note in DeviceBackupMigrationTest.
    private val startsFrom = 21L

    private val fixtures = File("src/commonMain/sqldelight/databases")

    private fun oldDriver(): JdbcSqliteDriver {
        val working = File(createTempDirectory("muviss-review-migration").toFile(), "muviss.db")
        fixtures.resolve("$startsFrom.db").copyTo(working)
        return JdbcSqliteDriver("jdbc:sqlite:${working.absolutePath}")
    }

    private fun migrate(driver: JdbcSqliteDriver) {
        runBlocking { MuvissDatabase.Schema.synchronous().migrate(driver, oldVersion = startsFrom, newVersion = MuvissDatabase.Schema.version) }
    }

    @Test
    fun the_review_fixture_for_the_new_version_is_checked_in() {
        assertTrue(fixtures.resolve("${MuvissDatabase.Schema.version}.db").exists(), "fixture is missing: run generateCommonMainMuvissDatabaseSchema")
    }

    @Test
    fun an_upgraded_install_is_unstamped_with_no_ticks_and_no_ask() {
        val driver = oldDriver()
        runBlocking {
            val queries = MuvissDatabase(driver).appSettingsQueries
            queries.ensureRow()
            queries.updateMutedGenresInSearch(true)
        }
        migrate(driver)

        val row = MuvissDatabase(driver).appSettingsQueries.selectSettings().executeAsOne()
        driver.close()

        assertNull(row.reviewInstalledAtEpochMs)
        assertEquals(0L, row.reviewTickCount)
        assertNull(row.reviewLastAskedAtEpochMs)
        assertNull(row.reviewLastAskedVersion)
        assertTrue(row.mutedGenresInSearch, "the migration must not reset a neighbouring preference")
    }

    @Test
    fun a_row_created_after_the_migration_starts_the_same_way() {
        val driver = oldDriver()
        migrate(driver)
        runBlocking { MuvissDatabase(driver).appSettingsQueries.ensureRow() }

        val row = MuvissDatabase(driver).appSettingsQueries.selectSettings().executeAsOne()
        driver.close()

        assertNull(row.reviewInstalledAtEpochMs)
        assertEquals(0L, row.reviewTickCount)
        assertNull(row.reviewLastAskedVersion)
    }
}
