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
 * Covers `8.sqm` — the migration that adds the `episode` catalog (ADR 0015).
 *
 * `verifyMigrations` already proves the `.sqm` chain reproduces what the `.sq`
 * files declare. What it cannot prove is that a real library survives, and
 * this migration's risk is the mirror image of `6.sqm`'s: that one had to
 * backfill, this one must *not*. Fabricating catalog rows would put episode
 * names in front of people that no source ever reported.
 */
class EpisodeCatalogMigrationTest {

    private val fixtures = File("src/commonMain/sqldelight/databases")

    private fun v8Driver(): Pair<JdbcSqliteDriver, MuvissDatabase> {
        val working = File(createTempDirectory("muviss-catalog-migration").toFile(), "muviss.db")
        fixtures.resolve("8.db").copyTo(working)
        val driver = JdbcSqliteDriver("jdbc:sqlite:${working.absolutePath}")
        return driver to MuvissDatabase(driver)
    }

    private suspend fun tick(database: MuvissDatabase, episode: String, at: Long) {
        database.episodeProgressQueries.upsert(
            episodeId = episode,
            mediaId = "tmdb:tv:1399",
            seasonNumber = 1L,
            episodeNumber = episode.substringAfterLast('/').toLong(),
            seen = true,
            updatedAtEpochMs = at,
            isDirty = false,
        )
        // episodePlay's key became the derived `episodeId@watchedAtEpochMs`
        // when it joined the sync change-log (ADR 0013), and its deletes went
        // soft with it.
        database.episodePlayQueries.upsert(
            id = "$episode@$at",
            episodeId = episode,
            mediaId = "tmdb:tv:1399",
            watchedAtEpochMs = at,
            updatedAtEpochMs = at,
            isDirty = false,
            deleted = false,
        )
    }

    private fun migrate(driver: JdbcSqliteDriver) = runBlocking {
        MuvissDatabase.Schema.synchronous().migrate(driver, oldVersion = 8L, newVersion = MuvissDatabase.Schema.version)
    }

    @Test
    fun `8_sqm produced its own fixture`() {
        // The .sqm is named after the version it migrates FROM, so 8.sqm
        // produces version 9 (ADR 0008's 2026-07-11 amendment).
        assertTrue(MuvissDatabase.Schema.version >= 9L)
        assertTrue(fixtures.resolve("9.db").exists(), "9.db fixture is missing — run generateCommonMainMuvissDatabaseSchema")
    }

    @Test
    fun `the catalog starts empty and is filled read-through, never backfilled`() {
        val (driver, database) = v8Driver()

        runBlocking {
            tick(database, "tmdb:tv:1399/1/1", at = 1_000)
            tick(database, "tmdb:tv:1399/1/2", at = 2_000)
        }
        migrate(driver)

        val after = MuvissDatabase(driver)
        assertEquals(
            emptyList(),
            after.episodeQueries.selectForMedia("tmdb:tv:1399").executeAsList(),
            "there is nothing local to derive an episode list from — inventing rows would name episodes no source reported",
        )
    }

    @Test
    fun `ticks and play history come through untouched`() {
        val (driver, database) = v8Driver()

        runBlocking {
            tick(database, "tmdb:tv:1399/1/1", at = 1_000)
            tick(database, "tmdb:tv:1399/1/2", at = 2_000)
        }
        migrate(driver)

        val after = MuvissDatabase(driver)
        assertEquals(2, after.episodeProgressQueries.selectForMedia("tmdb:tv:1399").executeAsList().size)
        assertTrue(after.episodeProgressQueries.selectForMedia("tmdb:tv:1399").executeAsList().all { it.seen })
        assertEquals(listOf(1_000L, 2_000L), after.episodePlayQueries.selectAllWatchedAt().executeAsList().sorted())
        driver.close()
    }

    /**
     * The catalog is provider data, not the user's (ADR 0015). It has no
     * `isDirty` column at all, so there is no change-log for a future sync
     * pass to pick up by accident — this asserts the shape rather than the
     * absence of a wiring, because the wiring is what would be easy to add.
     */
    @Test
    fun `the catalog table carries no sync columns`() {
        val (driver, _) = v8Driver()
        migrate(driver)

        val columns = driver.executeQuery(
            identifier = null,
            sql = "PRAGMA table_info(episode)",
            mapper = { cursor ->
                val names = mutableListOf<String>()
                while (cursor.next().value) names += cursor.getString(1)!!
                app.cash.sqldelight.db.QueryResult.Value(names.toList())
            },
            parameters = 0,
        ).value

        assertEquals(
            listOf(
                "episodeId", "mediaId", "seasonNumber", "seasonName", "episodeNumber",
                "name", "airDateEpochDay", "stillUrl", "runtimeMinutes", "fetchedAtEpochMs",
            ),
            columns,
        )
        driver.close()
    }

    @Test
    fun `a refetch replaces a title's catalog rather than merging into it`() {
        val (driver, _) = v8Driver()
        migrate(driver)
        val database = MuvissDatabase(driver)

        runBlocking {
            database.episodeQueries.upsert("tmdb:tv:1399/1/1", "tmdb:tv:1399", 1L, "Season 1", 1L, "Winter Is Coming", 50L, null, 62L, 10L)
            database.episodeQueries.upsert("tmdb:tv:1399/0/1", "tmdb:tv:1399", 0L, "Specials", 1L, "A special TMDB later removed", null, null, null, 10L)

            database.episodeQueries.deleteForMedia("tmdb:tv:1399")
            database.episodeQueries.upsert("tmdb:tv:1399/1/1", "tmdb:tv:1399", 1L, "Season 1", 1L, "Winter Is Coming", 50L, null, 62L, 20L)
        }

        val rows = database.episodeQueries.selectForMedia("tmdb:tv:1399").executeAsList()
        assertEquals(listOf("tmdb:tv:1399/1/1"), rows.map { it.episodeId })
        assertEquals(20L, rows.single().fetchedAtEpochMs)
        driver.close()
    }
}
