package com.codingpit.muviss.core.database

import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers `26.sqm`: the Display Preferences group's stamp and dirty flag, its backfill, and the Muted Genres
 * tables (ADR 0036, #642).
 *
 * What `verifyMigrations` cannot see: that an install which already has a value the person chose is stamped
 * `0` and marked dirty, that one which never changed anything is not, and that a row `ensureRow` creates
 * afterwards is neither. The backfill's literals are the first-run values, so each member is tested on its own.
 */
class DisplayPreferencesMigrationTest {

    // The one place this suite knows the migration's number; see the note in MutedGenresMigrationTest.
    private val migratesFrom = 26L
    private val producesVersion = migratesFrom + 1

    private val fixtures = File("src/commonMain/sqldelight/databases")

    private fun oldDriver(): JdbcSqliteDriver {
        val working = File(createTempDirectory("muviss-display-prefs-migration").toFile(), "muviss.db")
        fixtures.resolve("$migratesFrom.db").copyTo(working)
        return JdbcSqliteDriver("jdbc:sqlite:${working.absolutePath}")
    }

    private fun migrate(driver: JdbcSqliteDriver) {
        runBlocking { MuvissDatabase.Schema.synchronous().migrate(driver, oldVersion = migratesFrom, newVersion = MuvissDatabase.Schema.version) }
    }

    @Test
    fun `26_sqm produced its own fixture`() {
        assertTrue(MuvissDatabase.Schema.version >= producesVersion)
        assertTrue(fixtures.resolve("$producesVersion.db").exists(), "$producesVersion.db fixture is missing — run generateCommonMainMuvissDatabaseSchema")
    }

    @Test
    fun `an install that never changed a preference is neither stamped nor dirty`() {
        val driver = oldDriver()
        runBlocking { MuvissDatabase(driver).appSettingsQueries.ensureRow() }
        migrate(driver)

        val row = MuvissDatabase(driver).appSettingsQueries.selectSettings().executeAsOne()
        driver.close()

        assertNull(row.displayPreferencesUpdatedAtEpochMs)
        assertFalse(row.displayPreferencesDirty)
    }

    @Test
    fun `a row at every first-run value is not dirty, and a neighbour change is not a display preference`() {
        val driver = oldDriver()
        runBlocking {
            val queries = MuvissDatabase(driver).appSettingsQueries
            queries.ensureRow()
            queries.updateNotificationsEnabled(true) // a device switch, outside the group
        }
        migrate(driver)

        val row = MuvissDatabase(driver).appSettingsQueries.selectSettings().executeAsOne()
        driver.close()

        assertNull(row.displayPreferencesUpdatedAtEpochMs)
        assertFalse(row.displayPreferencesDirty)
    }

    /** Each of the ten members, moved off its first-run value on its own, in an otherwise untouched row. */
    private val changes: List<Pair<String, suspend (AppSettingsQueries) -> Unit>> = listOf(
        "theme" to { q -> q.updateTheme("DARK") },
        "language" to { q -> q.updateLanguage("es-ES") },
        "region" to { q -> q.updateRegion("ES") },
        "animationsEnabled" to { q -> q.updateAnimationsEnabled(false) },
        "triageDeckAnimations" to { q -> q.updateTriageDeckAnimations(false) },
        "tickAnimations" to { q -> q.updateTickAnimations(false) },
        "triageControlScheme" to { q -> q.updateTriageControlScheme("TWO_WAY") },
        "triageSnoozePeriod" to { q -> q.updateTriageSnoozePeriod("ONE_MONTH") },
        "triageSnoozePlacement" to { q -> q.updateTriageSnoozePlacement("FIRST") },
        "mutedGenresInSearch" to { q -> q.updateMutedGenresInSearch(true) },
    )

    @Test
    fun `a row that differs from the first-run value in any one member is stamped 0 and dirty`() {
        changes.forEach { (member, change) ->
            val driver = oldDriver()
            runBlocking {
                val queries = MuvissDatabase(driver).appSettingsQueries
                queries.ensureRow()
                change(queries)
            }
            migrate(driver)

            val row = MuvissDatabase(driver).appSettingsQueries.selectSettings().executeAsOne()
            driver.close()

            assertEquals(0L, row.displayPreferencesUpdatedAtEpochMs, "$member changed, so the group is stamped 0")
            assertTrue(row.displayPreferencesDirty, "$member changed, so the group is dirty")
        }
    }

    @Test
    fun `a row created after the migration starts neither stamped nor dirty`() {
        val driver = oldDriver()
        migrate(driver)
        runBlocking { MuvissDatabase(driver).appSettingsQueries.ensureRow() }

        val row = MuvissDatabase(driver).appSettingsQueries.selectSettings().executeAsOne()
        driver.close()

        assertNull(row.displayPreferencesUpdatedAtEpochMs)
        assertFalse(row.displayPreferencesDirty)
    }

    @Test
    fun `the muted genre tables exist and start empty`() {
        val driver = oldDriver()
        migrate(driver)

        val database = MuvissDatabase(driver)
        val decisions = database.mutedGenreQueries.selectAll().executeAsList()
        val choice = database.mutedGenreChoiceQueries.select().executeAsOneOrNull()
        driver.close()

        assertTrue(decisions.isEmpty())
        assertNull(choice)
    }
}
