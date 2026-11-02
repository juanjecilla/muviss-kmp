package com.codingpit.muviss.core.database

import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Covers `19.sqm` — `appSettings.tickAnimations` (EPIC 52). */
class TickAnimationsMigrationTest {

    private val fixtures = File("src/commonMain/sqldelight/databases")

    private fun databaseAtV19(): JdbcSqliteDriver {
        val working = File(createTempDirectory("muviss-migration-test").toFile(), "muviss.db")
        fixtures.resolve("19.db").copyTo(working)
        return JdbcSqliteDriver("jdbc:sqlite:${working.absolutePath}")
    }

    @Test
    fun `19_sqm produced its own fixture`() {
        assertTrue(MuvissDatabase.Schema.version >= 20L)
        assertTrue(fixtures.resolve("20.db").exists(), "20.db fixture is missing — run generateCommonMainMuvissDatabaseSchema")
    }

    @Test
    fun an_existing_install_keeps_animating_ticks_and_its_other_settings() {
        val driver = databaseAtV19()
        runBlocking {
            val before = MuvissDatabase(driver)
            before.appSettingsQueries.ensureRow()
            before.appSettingsQueries.updateTheme("DARK")

            MuvissDatabase.Schema.synchronous().migrate(driver, oldVersion = 19L, newVersion = MuvissDatabase.Schema.version)

            val settings = MuvissDatabase(driver).appSettingsQueries.selectSettings().executeAsOne()
            assertEquals(true, settings.tickAnimations)
            assertEquals("DARK", settings.theme)
        }
        driver.close()
    }

    @Test
    fun the_toggle_round_trips_after_migrating() {
        val driver = databaseAtV19()
        runBlocking {
            MuvissDatabase.Schema.synchronous().migrate(driver, oldVersion = 19L, newVersion = MuvissDatabase.Schema.version)
            val database = MuvissDatabase(driver)
            database.appSettingsQueries.ensureRow()

            database.appSettingsQueries.updateTickAnimations(false)

            assertEquals(false, database.appSettingsQueries.selectSettings().executeAsOne().tickAnimations)
        }
        driver.close()
    }
}
