package com.codingpit.muviss.core.database

import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Covers `28.sqm`: the `discoverCache` table (#724). Nothing to backfill; a new table starts empty. */
class DiscoverCacheMigrationTest {

    private val migratesFrom = 28L
    private val producesVersion = migratesFrom + 1

    private val fixtures = File("src/commonMain/sqldelight/databases")

    @Test
    fun `28_sqm produced its own fixture`() {
        assertTrue(MuvissDatabase.Schema.version >= producesVersion)
        assertTrue(fixtures.resolve("$producesVersion.db").exists(), "$producesVersion.db fixture is missing — run generateCommonMainMuvissDatabaseSchema")
    }

    @Test
    fun `an upgraded install has no stored Discover answers yet`() {
        val working = File(createTempDirectory("muviss-discover-cache-migration").toFile(), "muviss.db")
        fixtures.resolve("$migratesFrom.db").copyTo(working)
        val driver = JdbcSqliteDriver("jdbc:sqlite:${working.absolutePath}")
        runBlocking { MuvissDatabase.Schema.synchronous().migrate(driver, oldVersion = migratesFrom, newVersion = MuvissDatabase.Schema.version) }

        val row = MuvissDatabase(driver).discoverCacheQueries.selectByKey("genres:tv|en-US").executeAsOneOrNull()
        driver.close()

        assertNull(row)
    }
}
