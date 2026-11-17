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
 * Covers `33.sqm` (#938, #949): an install that already has a special ticked gets "Count specials toward progress" on, and
 * every other install stays off. A movie's tick is season 0 / episode 0 and must not turn it on, and an un-tick is not a
 * tick. Ticks themselves are never touched.
 */
class SpecialsSettingMigrationTest {

    private val fixtures = File("src/commonMain/sqldelight/databases")

    private data class Row(val episodeId: String, val season: Int, val number: Int, val seen: Boolean = true)

    /** A v33 database: the v34 fixture with the last column dropped, the version set back, one settings row and [rows]. */
    private fun v33(rows: List<Row>): JdbcSqliteDriver {
        val working = File(createTempDirectory("muviss-specials-migration").toFile(), "muviss.db")
        fixtures.resolve("34.db").copyTo(working)
        val driver = JdbcSqliteDriver("jdbc:sqlite:${working.absolutePath}")
        driver.execute(null, "ALTER TABLE appSettings DROP COLUMN countSpecialsTowardProgress", 0)
        driver.execute(null, "PRAGMA user_version = 33", 0)
        driver.execute(null, "INSERT OR IGNORE INTO appSettings(id, theme, language, region, notificationsEnabled) VALUES (0, 'SYSTEM', '', 'US', 0)", 0)
        rows.forEach { row ->
            driver.execute(
                null,
                "INSERT INTO episodeProgress(episodeId, mediaId, seasonNumber, episodeNumber, seen, updatedAtEpochMs, isDirty) " +
                    "VALUES ('${row.episodeId}', 'tmdb:tv:1399', ${row.season}, ${row.number}, ${if (row.seen) 1 else 0}, 1, 0)",
                0,
            )
        }
        return driver
    }

    private fun migratedFlag(driver: JdbcSqliteDriver): Boolean {
        runBlocking { MuvissDatabase.Schema.synchronous().migrate(driver, oldVersion = 33L, newVersion = MuvissDatabase.Schema.version) }
        return MuvissDatabase(driver).appSettingsQueries.selectSettings().executeAsOne().countSpecialsTowardProgress
    }

    @Test
    fun an_install_with_a_seen_special_tick_upgrades_with_the_setting_on() {
        val driver = v33(listOf(Row("tmdb:tv:1399/0/1", season = 0, number = 1)))

        assertTrue(migratedFlag(driver))
    }

    @Test
    fun an_install_with_no_special_ticks_upgrades_with_the_setting_off() {
        val driver = v33(listOf(Row("tmdb:tv:1399/1/1", season = 1, number = 1)))

        assertFalse(migratedFlag(driver))
    }

    @Test
    fun a_movie_tick_is_season_zero_but_not_a_special_so_the_setting_stays_off() {
        val driver = v33(listOf(Row("tmdb:movie:603/0/0", season = 0, number = 0)))

        assertFalse(migratedFlag(driver))
    }

    @Test
    fun an_un_ticked_special_is_not_a_tick_so_the_setting_stays_off() {
        val driver = v33(listOf(Row("tmdb:tv:1399/0/1", season = 0, number = 1, seen = false)))

        assertFalse(migratedFlag(driver))
    }
}
