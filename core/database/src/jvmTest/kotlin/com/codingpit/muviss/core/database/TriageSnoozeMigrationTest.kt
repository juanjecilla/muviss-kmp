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
 * Covers `12.sqm` — the migration EPIC 42 (snooze, ADR 0023) ships: the
 * `triageSnooze` table and the three `appSettings` columns that steer it.
 *
 * `verifyMigrations` proves the `.sqm` chain reproduces what the `.sq` files
 * declare. What it cannot prove is the behaviour a person would notice: that an
 * upgraded install arrives with no snoozes and the documented defaults, that
 * nothing already in the settings row is disturbed, and that a snooze written
 * after the upgrade round-trips.
 */
class TriageSnoozeMigrationTest {

    // The one place this suite knows the migration's number. `12.sqm` is named
    // for the version it migrates FROM, so it produces the next one, and its
    // fixture is named for that. If this is ever renumbered — EPIC 28 (#70) is
    // the other branch reaching for a number — change this constant, rename the
    // `.sqm`, regenerate the fixture, and nothing else here needs to move.
    private val migratesFrom = 12L
    private val producesVersion = migratesFrom + 1

    private val fixtures = File("src/commonMain/sqldelight/databases")

    private fun oldDriver(): Pair<JdbcSqliteDriver, MuvissDatabase> {
        val working = File(createTempDirectory("muviss-triage-snooze-migration").toFile(), "muviss.db")
        fixtures.resolve("$migratesFrom.db").copyTo(working)
        val driver = JdbcSqliteDriver("jdbc:sqlite:${working.absolutePath}")
        return driver to MuvissDatabase(driver)
    }

    private fun migrate(driver: JdbcSqliteDriver) = runBlocking {
        MuvissDatabase.Schema.synchronous().migrate(driver, oldVersion = migratesFrom, newVersion = MuvissDatabase.Schema.version)
    }

    @Test
    fun `12_sqm produced its own fixture`() {
        // Deliberately `>=`: later migrations move the schema on, and only the
        // newest one's test pins the current number.
        assertTrue(MuvissDatabase.Schema.version >= producesVersion)
        assertTrue(
            fixtures.resolve("$producesVersion.db").exists(),
            "$producesVersion.db fixture is missing — run generateCommonMainMuvissDatabaseSchema",
        )
    }

    @Test
    fun `an upgraded install has no snoozes and cannot have`() {
        val (driver, _) = oldDriver()
        migrate(driver)

        // Nothing is backfilled and nothing can be: before this migration there
        // was no way to postpone a decision, so an empty table is correct
        // rather than a missing backfill.
        assertEquals(0, MuvissDatabase(driver).triageSnoozeQueries.selectAll().executeAsList().size)
        driver.close()
    }

    @Test
    fun `an upgraded install arrives with the documented defaults`() {
        val (driver, database) = oldDriver()

        runBlocking { database.appSettingsQueries.ensureRow() }
        migrate(driver)

        val settings = MuvissDatabase(driver).appSettingsQueries.selectSettings().executeAsOne()
        assertEquals("ONE_WEEK", settings.triageSnoozePeriod)
        assertEquals("MIXED_IN", settings.triageSnoozePlacement)
        // False, so an install that upgrades is shown the hint once — the whole
        // reason this is not `triageTutorialSeen`, which is already true there.
        assertFalse(settings.triageSnoozeHintSeen)
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
            database.appSettingsQueries.updateCrashReportsEnabled(false)
        }
        migrate(driver)

        val settings = MuvissDatabase(driver).appSettingsQueries.selectSettings().executeAsOne()
        assertEquals("DARK", settings.theme)
        assertEquals("es-ES", settings.language)
        assertFalse(settings.notificationsEnabled)
        assertEquals("THREE_WAY", settings.triageControlScheme)
        assertFalse(settings.crashReportsEnabled)
        driver.close()
    }

    @Test
    fun `the three settings sit after the columns that preceded them, where an ALTER TABLE puts them`() {
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

        // Asserted relative to their predecessor rather than as the last three:
        // `verifyMigrations` compares ordinal position, so what matters is that
        // `12.sqm` appended these after the two EPIC 41's `11.sqm` added. A
        // later migration appending more is correct, and `takeLast(3)` would
        // report that as this migration breaking.
        assertEquals(
            listOf("coWatchIncludeSeen", "triageSnoozePeriod", "triageSnoozePlacement", "triageSnoozeHintSeen"),
            columns.dropWhile { it != "coWatchIncludeSeen" }.take(4),
        )
        driver.close()
    }

    @Test
    fun `a snooze written after migrating round-trips`() {
        val (driver, _) = oldDriver()
        migrate(driver)
        val database = MuvissDatabase(driver)

        runBlocking {
            database.triageSnoozeQueries.upsert(
                mediaId = "tmdb:movie:603",
                mediaType = "MOVIE",
                title = "The Matrix",
                year = 1999,
                posterUrl = null,
                overview = "A hacker learns the truth.",
                snoozedAtEpochMs = 1_000L,
                dueAtEpochDay = 20_007L,
                updatedAtEpochMs = 1_000L,
                isDirty = true,
                deleted = false,
            )
        }

        val stored = database.triageSnoozeQueries.selectAll().executeAsOne()
        assertEquals("tmdb:movie:603", stored.mediaId)
        assertEquals(20_007L, stored.dueAtEpochDay)
        assertTrue(stored.isDirty)

        // Soft, not hard: last-write-wins cannot express a hard delete.
        runBlocking { database.triageSnoozeQueries.softDelete(now = 2_000L, mediaId = "tmdb:movie:603") }
        assertEquals(0, database.triageSnoozeQueries.selectAll().executeAsList().size)
        assertTrue(database.triageSnoozeQueries.selectById("tmdb:movie:603").executeAsOne().deleted)
        driver.close()
    }
}
