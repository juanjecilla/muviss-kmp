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
 * Covers `6.sqm` — the migration that adds `episodePlay` and backfills it
 * from existing ticks (ADR 0011).
 *
 * `verifyMigrations` already proves the `.sqm` chain reproduces what the
 * `.sq` files declare. What it cannot prove is that somebody's real library
 * comes through: the backfill is the whole reason this migration exists
 * rather than the table starting empty, because an upgraded install must not
 * read as "you have watched nothing" the morning after.
 */
class EpisodePlayMigrationTest {

    private val fixtures = File("src/commonMain/sqldelight/databases")

    private fun v6Driver(): Pair<JdbcSqliteDriver, MuvissDatabase> {
        val working = File(createTempDirectory("muviss-play-migration").toFile(), "muviss.db")
        fixtures.resolve("6.db").copyTo(working)
        val driver = JdbcSqliteDriver("jdbc:sqlite:${working.absolutePath}")
        return driver to MuvissDatabase(driver)
    }

    private suspend fun tick(database: MuvissDatabase, episode: String, seen: Boolean, at: Long) {
        database.episodeProgressQueries.upsert(
            episodeId = episode,
            mediaId = "tmdb:tv:1399",
            seasonNumber = 1L,
            episodeNumber = episode.substringAfterLast('/').toLong(),
            seen = seen,
            updatedAtEpochMs = at,
            isDirty = false,
        )
    }

    private fun migrate(driver: JdbcSqliteDriver) = runBlocking {
        MuvissDatabase.Schema.synchronous().migrate(driver, oldVersion = 6L, newVersion = MuvissDatabase.Schema.version)
    }

    @Test
    fun `6_sqm produced its own fixture`() {
        // The .sqm is named after the version it migrates FROM, so 6.sqm
        // produces version 7 (ADR 0008's 2026-07-11 amendment).
        assertTrue(MuvissDatabase.Schema.version >= 7L)
        assertTrue(fixtures.resolve("7.db").exists(), "7.db fixture is missing — run generateCommonMainMuvissDatabaseSchema")
    }

    @Test
    fun `every already-seen episode is backfilled with one play at its tick time`() {
        val (driver, database) = v6Driver()

        runBlocking {
            tick(database, "tmdb:tv:1399/1/1", seen = true, at = 1_000)
            tick(database, "tmdb:tv:1399/1/2", seen = true, at = 2_000)
            tick(database, "tmdb:tv:1399/1/3", seen = false, at = 3_000)
        }
        migrate(driver)

        val after = MuvissDatabase(driver)
        assertEquals(1, after.episodePlayQueries.countForEpisode("tmdb:tv:1399/1/1").executeAsOne().toInt())
        assertEquals(1, after.episodePlayQueries.countForEpisode("tmdb:tv:1399/1/2").executeAsOne().toInt())
        assertEquals(
            0,
            after.episodePlayQueries.countForEpisode("tmdb:tv:1399/1/3").executeAsOne().toInt(),
            "an unseen episode has no viewing to record",
        )

        // The original tick time carries across, so the profile's watch streak
        // keeps the days it already had rather than collapsing onto today.
        assertEquals(
            listOf(1_000L, 2_000L),
            after.episodePlayQueries.selectAllWatchedAt().executeAsList().sorted(),
        )

        driver.close()
    }

    @Test
    fun `backfilled rows are not queued for sync`() {
        val (driver, database) = v6Driver()

        runBlocking { tick(database, "tmdb:tv:1399/1/1", seen = true, at = 1_000) }
        migrate(driver)

        assertEquals(
            emptyList(),
            MuvissDatabase(driver).episodePlayQueries.selectDirty().executeAsList(),
            "history that already existed locally is not a change to push",
        )
        driver.close()
    }

    @Test
    fun `seen ticks themselves are untouched by the migration`() {
        val (driver, database) = v6Driver()

        runBlocking { tick(database, "tmdb:tv:1399/1/1", seen = true, at = 1_000) }
        migrate(driver)

        // episodeProgress.seen stays the stored column the sync change-log and
        // status derivation key off; episodePlay sits alongside it.
        val rows = MuvissDatabase(driver).episodeProgressQueries.selectForMedia("tmdb:tv:1399").executeAsList()
        assertEquals(1, rows.size)
        assertEquals(true, rows.single().seen)
        driver.close()
    }

    @Test
    fun `deleting the latest play leaves earlier ones alone`() {
        val (driver, _) = v6Driver()
        migrate(driver)
        val database = MuvissDatabase(driver)

        runBlocking {
            listOf(1_000L, 2_000L, 3_000L).forEach {
                database.episodePlayQueries.upsert(
                    id = "tmdb:tv:1399/3/5@$it",
                    episodeId = "tmdb:tv:1399/3/5",
                    mediaId = "tmdb:tv:1399",
                    watchedAtEpochMs = it,
                    updatedAtEpochMs = it,
                    isDirty = true,
                    deleted = false,
                )
            }

            database.episodePlayQueries.deleteLatestForEpisode(updatedAtEpochMs = 4_000L, episodeId = "tmdb:tv:1399/3/5")

            assertEquals(2, database.episodePlayQueries.countForEpisode("tmdb:tv:1399/3/5").executeAsOne().toInt())
            assertEquals(
                listOf(1_000L, 2_000L),
                database.episodePlayQueries.selectForEpisode("tmdb:tv:1399/3/5").executeAsList().map { it.watchedAtEpochMs }.sorted(),
                "'I ticked that by mistake' drops the newest viewing, not the history behind it",
            )
        }
        driver.close()
    }
}
