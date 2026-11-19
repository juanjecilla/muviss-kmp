package com.codingpit.muviss.core.database

import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers `31.sqm`: the `forYouCache` table (#785). Nothing to backfill; a new table starts empty. The upgrade starts from
 * the v30 fixture, so it also runs whatever the other lane's `30.sqm` adds once that lands.
 */
class ForYouCacheMigrationTest {

    private val migratesFrom = 30L
    private val producesVersion = 32L

    private val fixtures = File("src/commonMain/sqldelight/databases")

    @Test
    fun `31_sqm produced its own fixture`() {
        assertTrue(MuvissDatabase.Schema.version >= producesVersion)
        assertTrue(fixtures.resolve("$producesVersion.db").exists(), "$producesVersion.db fixture is missing — run generateCommonMainMuvissDatabaseSchema")
    }

    @Test
    fun `an upgraded install has no stored For you copy yet`() {
        val working = File(createTempDirectory("muviss-foryou-cache-migration").toFile(), "muviss.db")
        fixtures.resolve("$migratesFrom.db").copyTo(working)
        val driver = JdbcSqliteDriver("jdbc:sqlite:${working.absolutePath}")
        runBlocking { MuvissDatabase.Schema.synchronous().migrate(driver, oldVersion = migratesFrom, newVersion = MuvissDatabase.Schema.version) }

        val row = MuvissDatabase(driver).forYouCacheQueries.selectByKey("foryou:0000000000000000").executeAsOneOrNull()
        driver.close()

        assertNull(row)
    }
}
