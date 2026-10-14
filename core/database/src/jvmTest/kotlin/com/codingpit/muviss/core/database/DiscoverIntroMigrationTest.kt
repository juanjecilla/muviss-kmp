package com.codingpit.muviss.core.database

import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers `14.sqm` — EPIC 30's (#73) `appSettings.discoverIntroSeen`.
 *
 * What `verifyMigrations` cannot see: the rule that only a genuinely new user
 * sees the intro. An upgraded install with a library already built arrives
 * with the flag set; one with an empty (or wholly removed) library does not.
 */
class DiscoverIntroMigrationTest {

    // The one place this suite knows the migration's number; see the note in
    // TriageSnoozeMigrationTest about renumbering.
    private val migratesFrom = 14L
    private val producesVersion = migratesFrom + 1

    private val fixtures = File("src/commonMain/sqldelight/databases")

    private fun oldDriver(): JdbcSqliteDriver {
        val working = File(createTempDirectory("muviss-discover-intro-migration").toFile(), "muviss.db")
        fixtures.resolve("$migratesFrom.db").copyTo(working)
        return JdbcSqliteDriver("jdbc:sqlite:${working.absolutePath}").also { driver ->
            runBlocking { MuvissDatabase(driver).appSettingsQueries.ensureRow() }
        }
    }

    private fun JdbcSqliteDriver.saveTitle(deleted: Boolean) {
        execute(
            null,
            """
            INSERT INTO collectionEntry(mediaId, mediaType, title, productionStatus, totalEpisodes, airedEpisodes, favorite, addedAtEpochMs, updatedAtEpochMs, isDirty, deleted)
            VALUES ('tmdb:tv:1399', 'tv', 'A show', 'RETURNING', 10, 10, 0, 0, 0, 1, ${if (deleted) 1 else 0})
            """.trimIndent(),
            0,
        )
    }

    private fun migrateAndRead(driver: JdbcSqliteDriver): Boolean {
        runBlocking { MuvissDatabase.Schema.synchronous().migrate(driver, oldVersion = migratesFrom, newVersion = MuvissDatabase.Schema.version) }
        return MuvissDatabase(driver).appSettingsQueries.selectSettings().executeAsOne().discoverIntroSeen
            .also { driver.close() }
    }

    @Test
    fun `14_sqm produced its own fixture`() {
        assertTrue(MuvissDatabase.Schema.version >= producesVersion)
        assertTrue(fixtures.resolve("$producesVersion.db").exists(), "$producesVersion.db fixture is missing — run generateCommonMainMuvissDatabaseSchema")
    }

    @Test
    fun `an upgrade with a library already built skips the intro`() {
        val driver = oldDriver()
        driver.saveTitle(deleted = false)

        assertTrue(migrateAndRead(driver))
    }

    @Test
    fun `an upgrade with an empty library still shows it`() {
        assertFalse(migrateAndRead(oldDriver()))
    }

    @Test
    fun `a library of removed titles counts as empty`() {
        val driver = oldDriver()
        driver.saveTitle(deleted = true)

        assertFalse(migrateAndRead(driver))
    }

    @Test
    fun `a fresh install starts unseen`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MuvissDatabase.Schema.synchronous().create(driver)
        val queries = MuvissDatabase(driver).appSettingsQueries
        runBlocking { queries.ensureRow() }

        assertEquals(false, queries.selectSettings().executeAsOne().discoverIntroSeen)
    }
}
