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
 * Covers `29.sqm` — the column that records whether a triage verdict created the library entry it
 * saved (#767). Starts from the checked-in `29.db` fixture, a genuine v29 file with a decision row
 * written before the column existed, migrates forward, and asserts the old row kept undo's
 * existing behaviour and that the new claim round-trips.
 */
class CreatedEntryMigrationTest {

    private val fixtures = File("src/commonMain/sqldelight/databases")

    private fun v29Driver(): JdbcSqliteDriver {
        val working = File(createTempDirectory("muviss-migration-test").toFile(), "muviss.db")
        fixtures.resolve("29.db").copyTo(working)
        return JdbcSqliteDriver("jdbc:sqlite:${working.absolutePath}")
    }

    @Test
    fun `30_db fixture exists for the schema this migration produces`() {
        assertTrue(fixtures.resolve("30.db").exists(), "30.db fixture is missing — run generateCommonMainMuvissDatabaseSchema")
    }

    @Test
    fun `a decision written before the column existed reads as having created its entry`() {
        val driver = v29Driver()
        // Raw SQL: at v29 the generated `upsert` does not exist yet, it has no createdEntry parameter.
        driver.execute(
            identifier = null,
            sql = """
                INSERT INTO triageDecision(
                    mediaId, mediaType, verdict, title, posterUrl, decidedAtEpochMs,
                    resolved, updatedAtEpochMs, isDirty, deleted
                ) VALUES ('tmdb:tv:1399', 'tv', 'CAUGHT_UP', 'Game of Thrones', NULL, 1000, 1, 1000, 0, 0)
            """.trimIndent(),
            parameters = 0,
        )

        runBlocking {
            MuvissDatabase.Schema.synchronous().migrate(driver, oldVersion = 29L, newVersion = MuvissDatabase.Schema.version)
        }

        val row = MuvissDatabase(driver).triageDecisionQueries.selectById("tmdb:tv:1399").executeAsOne()
        assertEquals(true, row.createdEntry, "every pre-existing decision keeps undo's behaviour from before the column")
        driver.close()
    }

    @Test
    fun `a claim written after the upgrade round-trips false and true`() {
        val driver = v29Driver()
        runBlocking {
            MuvissDatabase.Schema.synchronous().migrate(driver, oldVersion = 29L, newVersion = MuvissDatabase.Schema.version)
        }
        val database = MuvissDatabase(driver)

        runBlocking {
            database.triageDecisionQueries.upsert(
                mediaId = "tmdb:movie:603",
                mediaType = "movie",
                verdict = "LATER",
                title = "The Matrix",
                posterUrl = null,
                decidedAtEpochMs = 5_000,
                resolved = true,
                updatedAtEpochMs = 5_000,
                isDirty = true,
                deleted = false,
                createdEntry = false,
            )
        }
        runBlocking {
            database.triageDecisionQueries.upsert(
                mediaId = "tmdb:movie:550",
                mediaType = "movie",
                verdict = "LATER",
                title = "Fight Club",
                posterUrl = null,
                decidedAtEpochMs = 5_000,
                resolved = true,
                updatedAtEpochMs = 5_000,
                isDirty = true,
                deleted = false,
                createdEntry = true,
            )
        }

        assertEquals(false, database.triageDecisionQueries.selectById("tmdb:movie:603").executeAsOne().createdEntry)
        assertEquals(true, database.triageDecisionQueries.selectById("tmdb:movie:550").executeAsOne().createdEntry)
        driver.close()
    }
}
