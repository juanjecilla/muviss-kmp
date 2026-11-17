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
 * Covers `30.sqm` — the added-at stamp and the ticked episode ids a triage verdict wrote (#902, #790).
 * Starts from the checked-in `30.db` fixture, a genuine v30 file with a decision row written before the
 * columns existed, migrates forward, and asserts the old row claims nothing it cannot prove and the new
 * values round-trip.
 */
class TriageUndoStampMigrationTest {

    private val fixtures = File("src/commonMain/sqldelight/databases")

    private fun v30Driver(): JdbcSqliteDriver {
        val working = File(createTempDirectory("muviss-migration-test").toFile(), "muviss.db")
        fixtures.resolve("30.db").copyTo(working)
        return JdbcSqliteDriver("jdbc:sqlite:${working.absolutePath}")
    }

    @Test
    fun `31_db fixture exists for the schema this migration produces`() {
        assertTrue(fixtures.resolve("31.db").exists(), "31.db fixture is missing — run generateCommonMainMuvissDatabaseSchema")
    }

    @Test
    fun `a decision written before the columns existed claims no stamp and ticks nothing`() {
        val driver = v30Driver()
        // Raw SQL: at v30 the generated `upsert` does not take the new columns.
        driver.execute(
            identifier = null,
            sql = """
                INSERT INTO triageDecision(
                    mediaId, mediaType, verdict, title, posterUrl, decidedAtEpochMs,
                    resolved, updatedAtEpochMs, isDirty, deleted, createdEntry
                ) VALUES ('tmdb:tv:1399', 'tv', 'CAUGHT_UP', 'Game of Thrones', NULL, 1000, 1, 1000, 0, 0, 1)
            """.trimIndent(),
            parameters = 0,
        )

        runBlocking {
            MuvissDatabase.Schema.synchronous().migrate(driver, oldVersion = 30L, newVersion = MuvissDatabase.Schema.version)
        }

        val row = MuvissDatabase(driver).triageDecisionQueries.selectById("tmdb:tv:1399").executeAsOne()
        assertNull(row.entryAddedAtEpochMs, "no stamp was ever recorded, so undo must not be able to remove the entry")
        assertEquals("", row.tickedEpisodeIds, "no ticks were ever recorded, so undo un-ticks nothing")
        driver.close()
    }

    @Test
    fun `a stamp and a tick list written after the upgrade round-trip`() {
        val driver = v30Driver()
        runBlocking {
            MuvissDatabase.Schema.synchronous().migrate(driver, oldVersion = 30L, newVersion = MuvissDatabase.Schema.version)
        }
        val database = MuvissDatabase(driver)

        runBlocking {
            database.triageDecisionQueries.upsert(
                mediaId = "tmdb:tv:1400",
                mediaType = "tv",
                verdict = "CAUGHT_UP",
                title = "Breaking Bad",
                posterUrl = null,
                decidedAtEpochMs = 5_000,
                resolved = true,
                updatedAtEpochMs = 5_000,
                isDirty = true,
                deleted = false,
                createdEntry = true,
                entryAddedAtEpochMs = 4_321,
                tickedEpisodeIds = "tmdb:tv:1400/1/1,tmdb:tv:1400/1/2",
            )
        }

        val row = database.triageDecisionQueries.selectById("tmdb:tv:1400").executeAsOne()
        assertEquals(4_321L, row.entryAddedAtEpochMs)
        assertEquals("tmdb:tv:1400/1/1,tmdb:tv:1400/1/2", row.tickedEpisodeIds)
        driver.close()
    }
}
