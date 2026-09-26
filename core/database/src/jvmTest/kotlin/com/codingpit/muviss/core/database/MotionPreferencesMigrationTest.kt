package com.codingpit.muviss.core.database

import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers `5.sqm` — the migration that adds the two motion preferences
 * (`animationsEnabled`, `triageDeckAnimations`) to `appSettings`.
 *
 * As with `4.sqm` (see [TriageDecisionMigrationTest]), `verifyMigrations`
 * already proves the `.sqm` chain reproduces what the `.sq` files declare. What
 * it does not prove is that a real database with real user data survives the
 * upgrade, or that an install that predates these columns lands on the
 * behaviour it already had rather than silently losing its animations.
 */
class MotionPreferencesMigrationTest {

    private val fixtures = File("src/commonMain/sqldelight/databases")

    private fun databaseAtV5(): Pair<JdbcSqliteDriver, File> {
        val working = File(createTempDirectory("muviss-migration-test").toFile(), "muviss.db")
        fixtures.resolve("5.db").copyTo(working)
        return JdbcSqliteDriver("jdbc:sqlite:${working.absolutePath}") to working
    }

    @Test
    fun `5_sqm produced its own fixture`() {
        // The .sqm is named after the version it migrates FROM, so 5.sqm
        // produces version 6 (ADR 0008's 2026-07-11 amendment). Deliberately
        // `>=` rather than `== 6`: later migrations move the schema on, and
        // only the newest one's test pins the current number — see
        // `EpisodePlayMigrationTest`.
        assertTrue(MuvissDatabase.Schema.version >= 6L)
        assertTrue(fixtures.resolve("6.db").exists(), "6.db fixture is missing — run generateCommonMainMuvissDatabaseSchema")
    }

    @Test
    fun `an install from before the toggles keeps the behaviour it already had`() {
        val (driver, _) = databaseAtV5()

        runBlocking {
            MuvissDatabase(driver).appSettingsQueries.ensureRow()
            MuvissDatabase.Schema.synchronous().migrate(driver, oldVersion = 5L, newVersion = MuvissDatabase.Schema.version)

            // The app has always animated. Upgrading is not the moment to stop.
            val settings = MuvissDatabase(driver).appSettingsQueries.selectSettings().executeAsOne()
            assertEquals(true, settings.animationsEnabled)
            assertEquals(true, settings.triageDeckAnimations)
        }
        driver.close()
    }

    @Test
    fun `existing settings, collection and triage rows survive the upgrade`() {
        val (driver, _) = databaseAtV5()

        runBlocking {
            val before = MuvissDatabase(driver)
            before.appSettingsQueries.ensureRow()
            before.appSettingsQueries.updateTheme("DARK")
            before.appSettingsQueries.updateTriageControlScheme("THREE_WAY")
            before.appSettingsQueries.updateTriageTutorialSeen(true)
            // Raw SQL, not the generated query: this database is still at v5
            // and the generated one speaks the current schema. See
            // `seedLegacyCollectionEntry`.
            driver.seedLegacyCollectionEntry(
                mediaId = "tmdb:tv:1399",
                addedAtEpochMs = 1_000,
                updatedAtEpochMs = 1_000,
                rating = 9,
                note = "the early seasons",
            )
            before.triageDecisionQueries.upsert(
                mediaId = "tmdb:movie:603",
                mediaType = "movie",
                verdict = "SKIP",
                title = "The Matrix",
                posterUrl = null,
                decidedAtEpochMs = 5_000,
                resolved = true,
                updatedAtEpochMs = 5_000,
                isDirty = true,
                deleted = false,
            )

            MuvissDatabase.Schema.synchronous().migrate(driver, oldVersion = 5L, newVersion = MuvissDatabase.Schema.version)

            val after = MuvissDatabase(driver)
            val settings = after.appSettingsQueries.selectSettings().executeAsOne()
            assertEquals("DARK", settings.theme)
            assertEquals("THREE_WAY", settings.triageControlScheme)
            assertEquals(true, settings.triageTutorialSeen)
            assertEquals("Game of Thrones", after.collectionEntryQueries.selectById("tmdb:tv:1399").executeAsOne().title)
            assertEquals(listOf("tmdb:movie:603"), after.triageDecisionQueries.selectDecidedIds().executeAsList())
        }
        driver.close()
    }

    @Test
    fun `the toggles round-trip after migrating`() {
        val (driver, _) = databaseAtV5()

        runBlocking {
            MuvissDatabase.Schema.synchronous().migrate(driver, oldVersion = 5L, newVersion = MuvissDatabase.Schema.version)
            val database = MuvissDatabase(driver)
            database.appSettingsQueries.ensureRow()

            database.appSettingsQueries.updateAnimationsEnabled(false)
            database.appSettingsQueries.updateTriageDeckAnimations(false)

            val settings = database.appSettingsQueries.selectSettings().executeAsOne()
            assertEquals(false, settings.animationsEnabled)
            assertEquals(false, settings.triageDeckAnimations)
        }
        driver.close()
    }
}
