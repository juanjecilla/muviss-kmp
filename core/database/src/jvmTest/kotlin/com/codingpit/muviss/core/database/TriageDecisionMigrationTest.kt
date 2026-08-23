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
 * Covers `4.sqm` — the migration that adds `triageDecision` and the two
 * per-device triage columns on `appSettings` (ADR 0010).
 *
 * The SQLDelight plugin's own `verifyMigrations` already proves the `.sqm`
 * chain reproduces the schema the `.sq` files declare. What it does *not*
 * prove is that a real database with real user data survives the upgrade —
 * which is the part that matters to someone who has been using the app since
 * before triage existed. This starts from the checked-in `4.db` schema
 * fixture (a genuine SQLite file), fills it with rows, migrates forward, and
 * asserts nothing was lost.
 */
class TriageDecisionMigrationTest {

    private val fixtures = File("src/commonMain/sqldelight/databases")

    private fun migratedDatabaseFromV4(): Pair<MuvissDatabase, JdbcSqliteDriver> {
        val working = File(createTempDirectory("muviss-migration-test").toFile(), "muviss.db")
        fixtures.resolve("4.db").copyTo(working)

        val driver = JdbcSqliteDriver("jdbc:sqlite:${working.absolutePath}")
        runBlocking {
            MuvissDatabase.Schema.synchronous().migrate(driver, oldVersion = 4L, newVersion = MuvissDatabase.Schema.version)
        }
        return MuvissDatabase(driver) to driver
    }

    @Test
    fun `the schema fixture chain is at the version 4_sqm produces`() {
        // Guards the "the .sqm is named after the version it migrates FROM"
        // trap (ADR 0008's 2026-07-11 amendment): 4.sqm produces version 5.
        assertEquals(5L, MuvissDatabase.Schema.version)
        assertTrue(fixtures.resolve("5.db").exists(), "5.db fixture is missing — run generateCommonMainMuvissDatabaseSchema")
    }

    @Test
    fun `existing collection, progress and list rows survive the upgrade`() {
        val working = File(createTempDirectory("muviss-migration-test").toFile(), "muviss.db")
        fixtures.resolve("4.db").copyTo(working)
        val driver = JdbcSqliteDriver("jdbc:sqlite:${working.absolutePath}")

        runBlocking {
            // Populate at v4, before triage's tables exist at all.
            val before = MuvissDatabase(driver)
            before.collectionEntryQueries.upsert(
                mediaId = "tmdb:tv:1399",
                mediaType = "tv",
                title = "Game of Thrones",
                posterUrl = null,
                releaseYear = 2011,
                productionStatus = "ENDED",
                totalEpisodes = 73,
                airedEpisodes = 73,
                favorite = true,
                genres = "Drama",
                runtimeMinutes = 57,
                addedAtEpochMs = 1_000,
                updatedAtEpochMs = 1_000,
                isDirty = false,
                deleted = false,
                notificationsMuted = false,
                rating = 9,
                note = "the early seasons",
            )
            before.episodeProgressQueries.upsert(
                episodeId = "tmdb:tv:1399/1/1",
                mediaId = "tmdb:tv:1399",
                seasonNumber = 1,
                episodeNumber = 1,
                seen = true,
                updatedAtEpochMs = 1_000,
                isDirty = false,
            )
            before.appSettingsQueries.ensureRow()
            before.appSettingsQueries.updateTheme("DARK")

            MuvissDatabase.Schema.synchronous().migrate(driver, oldVersion = 4L, newVersion = MuvissDatabase.Schema.version)

            val after = MuvissDatabase(driver)
            val entry = after.collectionEntryQueries.selectById("tmdb:tv:1399").executeAsOne()
            assertEquals("Game of Thrones", entry.title)
            assertEquals(9L, entry.rating)
            assertEquals("the early seasons", entry.note)
            assertEquals(true, entry.favorite)
            assertEquals(1, after.episodeProgressQueries.selectForMedia("tmdb:tv:1399").executeAsList().size)
            assertEquals("DARK", after.appSettingsQueries.selectSettings().executeAsOne().theme)
        }
        driver.close()
    }

    @Test
    fun `triageDecision exists and starts empty`() {
        val (database, driver) = migratedDatabaseFromV4()

        // Nothing is backfilled: titles already in the collection are kept out
        // of the deck by collection membership, not by a synthetic decision.
        assertEquals(emptyList(), database.triageDecisionQueries.selectAll().executeAsList())
        assertEquals(emptyList(), database.triageDecisionQueries.selectDecidedIds().executeAsList())

        driver.close()
    }

    @Test
    fun `a pre-existing settings row picks up the triage defaults`() {
        val working = File(createTempDirectory("muviss-migration-test").toFile(), "muviss.db")
        fixtures.resolve("4.db").copyTo(working)
        val driver = JdbcSqliteDriver("jdbc:sqlite:${working.absolutePath}")

        runBlocking {
            MuvissDatabase(driver).appSettingsQueries.ensureRow()
            MuvissDatabase.Schema.synchronous().migrate(driver, oldVersion = 4L, newVersion = MuvissDatabase.Schema.version)

            val settings = MuvissDatabase(driver).appSettingsQueries.selectSettings().executeAsOne()
            assertEquals("FOUR_WAY", settings.triageControlScheme)
            assertEquals(false, settings.triageTutorialSeen)
        }
        driver.close()
    }

    @Test
    fun `a decision written after migrating round-trips through every query`() {
        val (database, driver) = migratedDatabaseFromV4()

        runBlocking {
            database.triageDecisionQueries.upsert(
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

            assertEquals(listOf("tmdb:movie:603"), database.triageDecisionQueries.selectDecidedIds().executeAsList())
            assertEquals(1, database.triageDecisionQueries.selectByVerdict("SKIP").executeAsList().size)
            assertEquals(1, database.triageDecisionQueries.selectDirty().executeAsList().size)

            database.triageDecisionQueries.softDelete(now = 6_000, mediaId = "tmdb:movie:603")
            // A tombstone still exists as a row (so it can sync) but no longer counts as decided.
            assertEquals(emptyList(), database.triageDecisionQueries.selectDecidedIds().executeAsList())
            assertEquals(true, database.triageDecisionQueries.selectById("tmdb:movie:603").executeAsOne().deleted)
        }
        driver.close()
    }
}
