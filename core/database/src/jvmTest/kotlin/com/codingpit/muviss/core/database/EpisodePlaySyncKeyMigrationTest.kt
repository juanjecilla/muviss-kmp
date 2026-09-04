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
 * Covers `7.sqm` — the rebuild that gives `episodePlay` a cross-device
 * identity so rewatch history can sync (ADR 0013), plus the `syncCursorEpochMs`
 * column split out of `lastSyncedAtEpochMs` (ADR 0018).
 *
 * `verifyMigrations` proves the `.sqm` chain reproduces the `.sq` files'
 * schema; it says nothing about the data. This migration drops and recreates a
 * table, so the thing worth testing is that somebody's existing rewatch history
 * survives the trip with its timestamps and counts intact.
 */
class EpisodePlaySyncKeyMigrationTest {

    private val fixtures = File("src/commonMain/sqldelight/databases")

    private fun v7Driver(): Pair<JdbcSqliteDriver, MuvissDatabase> {
        val working = File(createTempDirectory("muviss-play-key-migration").toFile(), "muviss.db")
        fixtures.resolve("7.db").copyTo(working)
        val driver = JdbcSqliteDriver("jdbc:sqlite:${working.absolutePath}")
        return driver to MuvissDatabase(driver)
    }

    /**
     * v7's `episodePlay` had an AUTOINCREMENT `id` and no `updatedAtEpochMs`
     * or `deleted`, so the generated queries (which describe v8) cannot write
     * it — raw SQL is the only way to seed the old shape.
     */
    private fun seedV7Play(driver: JdbcSqliteDriver, episodeId: String, watchedAt: Long, isDirty: Int = 1) {
        driver.execute(
            identifier = null,
            sql = "INSERT INTO episodePlay(episodeId, mediaId, watchedAtEpochMs, isDirty) VALUES ('$episodeId', 'tmdb:tv:1399', $watchedAt, $isDirty)",
            parameters = 0,
        )
    }

    private fun migrate(driver: JdbcSqliteDriver) = runBlocking {
        MuvissDatabase.Schema.synchronous().migrate(driver, oldVersion = 7L, newVersion = MuvissDatabase.Schema.version)
    }

    @Test
    fun `7_sqm produced its own fixture`() {
        // The .sqm is named after the version it migrates FROM, so 7.sqm
        // produces version 8 (ADR 0008's 2026-07-11 amendment).
        assertTrue(MuvissDatabase.Schema.version >= 8L)
        assertTrue(fixtures.resolve("8.db").exists(), "8.db fixture is missing — run generateCommonMainMuvissDatabaseSchema")
    }

    @Test
    fun `existing rewatch history keeps every viewing through the rebuild`() {
        val (driver, _) = v7Driver()
        listOf(1_000L, 2_000L, 3_000L).forEach { seedV7Play(driver, EPISODE, it) }

        migrate(driver)

        val database = MuvissDatabase(driver)
        runBlocking {
            assertEquals(3, database.episodePlayQueries.countForEpisode(EPISODE).executeAsOne().toInt())
            assertEquals(
                listOf(1_000L, 2_000L, 3_000L),
                database.episodePlayQueries.selectForEpisode(EPISODE).executeAsList().map { it.watchedAtEpochMs }.sorted(),
                "a table rebuild that loses viewings loses the only record of them",
            )
        }
        driver.close()
    }

    @Test
    fun `ids are derived from the viewing so two devices agree on them`() {
        val (driver, _) = v7Driver()
        seedV7Play(driver, EPISODE, 1_000L)

        migrate(driver)

        val row = MuvissDatabase(driver).episodePlayQueries.selectForEpisode(EPISODE).executeAsOne()
        // The point of the whole migration: an AUTOINCREMENT id is allocated
        // per device, so device A's play #1 and device B's play #1 are
        // different viewings and could never be a sync key.
        assertEquals("$EPISODE@1000", row.id)
        assertEquals(1_000L, row.updatedAtEpochMs, "updatedAtEpochMs seeds from the only timestamp these rows had")
        assertEquals(false, row.deleted)
        driver.close()
    }

    @Test
    fun `two rows recorded in the same millisecond collapse rather than aborting the migration`() {
        val (driver, _) = v7Driver()
        // Not reachable by tapping, but a v7 database could hold it, and a
        // duplicate primary key would fail the migration for that whole install.
        seedV7Play(driver, EPISODE, 1_000L)
        seedV7Play(driver, EPISODE, 1_000L)

        migrate(driver)

        assertEquals(1, MuvissDatabase(driver).episodePlayQueries.countForEpisode(EPISODE).executeAsOne().toInt())
        driver.close()
    }

    @Test
    fun `a dirty row stays dirty and a clean one stays clean`() {
        val (driver, _) = v7Driver()
        seedV7Play(driver, EPISODE, 1_000L, isDirty = 0)
        seedV7Play(driver, "$EPISODE_BASE/2", 2_000L, isDirty = 1)

        migrate(driver)

        val database = MuvissDatabase(driver)
        val dirty = database.episodePlayQueries.selectDirty().executeAsList()
        assertEquals(1, dirty.size, "6.sqm's backfilled history is already on the server as ticks; re-pushing all of it is wasted")
        assertEquals("$EPISODE_BASE/2@2000", dirty.single().id)
        driver.close()
    }

    @Test
    fun `the pull cursor starts empty rather than inheriting the local clock reading`() {
        val (driver, database) = v7Driver()
        runBlocking {
            database.appSettingsQueries.ensureRow()
            database.appSettingsQueries.updateLastSyncedAt(9_999L)
        }

        migrate(driver)

        val settings = MuvissDatabase(driver).appSettingsQueries.selectSettings().executeAsOne()
        assertEquals(9_999L, settings.lastSyncedAtEpochMs, "the label keeps its history")
        assertNull(
            settings.syncCursorEpochMs,
            "lastSyncedAtEpochMs is a local clock reading, which is exactly the value the cursor split exists to stop trusting — " +
                "null asks for a full pull, and last-write-wins makes that idempotent",
        )
        driver.close()
    }

    private companion object {
        const val EPISODE_BASE = "tmdb:tv:1399/1"
        const val EPISODE = "tmdb:tv:1399/1/1"
    }
}
