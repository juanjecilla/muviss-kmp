package com.codingpit.muviss.core.database

import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers `17.sqm` — EPIC 47's (#299) `appSettings.onboardingCompleted` and
 * `notificationNudgeDismissed`.
 *
 * What `verifyMigrations` cannot see is the rule the whole epic leans on: a
 * *migration* only ever runs on an install that already has the app, so it must
 * never be shown the introduction and must keep whatever notification choice it
 * made — while a *fresh* install, which never migrates, starts on the
 * introduction with notifications off.
 */
class OnboardingMigrationTest {

    // The one place this suite knows the migration's number; renumber it with the file.
    private val migratesFrom = 17L
    private val producesVersion = migratesFrom + 1

    private val fixtures = File("src/commonMain/sqldelight/databases")

    private fun oldDriver(): JdbcSqliteDriver {
        val working = File(createTempDirectory("muviss-onboarding-migration").toFile(), "muviss.db")
        fixtures.resolve("$migratesFrom.db").copyTo(working)
        return JdbcSqliteDriver("jdbc:sqlite:${working.absolutePath}")
    }

    private fun migrate(driver: JdbcSqliteDriver) {
        runBlocking { MuvissDatabase.Schema.synchronous().migrate(driver, oldVersion = migratesFrom, newVersion = MuvissDatabase.Schema.version) }
    }

    @Test
    fun `17_sqm produced its own fixture`() {
        assertTrue(MuvissDatabase.Schema.version >= producesVersion)
        assertTrue(fixtures.resolve("$producesVersion.db").exists(), "$producesVersion.db fixture is missing — run generateCommonMainMuvissDatabaseSchema")
    }

    @Test
    fun `an upgraded install is marked completed and keeps its notification choice`() {
        val driver = oldDriver()
        val queries = MuvissDatabase(driver).appSettingsQueries
        runBlocking {
            queries.ensureRow()
            // Someone who turned alerts off before this epic existed: that choice must survive.
            queries.updateNotificationsEnabled(false)
        }

        migrate(driver)

        val row = queries.selectSettings().executeAsOne()
        assertTrue(row.onboardingCompleted, "an upgraded install never sees the introduction")
        assertFalse(row.notificationsEnabled, "silently turning someone's alerts back on, or off, is worse than the inconsistency")
        assertFalse(row.notificationNudgeDismissed)
        driver.close()
    }

    @Test
    fun `an upgraded install that had alerts on keeps them on`() {
        val driver = oldDriver()
        val queries = MuvissDatabase(driver).appSettingsQueries
        // The v17 fixture's ensureRow would now seed 0, so write the old default explicitly.
        driver.execute(null, "INSERT INTO appSettings(id, theme, language, region, notificationsEnabled) VALUES (0, 'SYSTEM', '', 'US', 1)", 0)

        migrate(driver)

        assertTrue(queries.selectSettings().executeAsOne().notificationsEnabled)
        driver.close()
    }

    @Test
    fun `an upgraded install that never had a settings row still gets one marked completed`() {
        val driver = oldDriver()
        val queries = MuvissDatabase(driver).appSettingsQueries
        // (The v17 fixture has no `appSettings` row at all, and cannot be read through the new queries
        // until it has migrated: they name columns it does not have yet.)

        migrate(driver)

        // Without the migration creating the row, `ensureRow` would later create it as a fresh install's
        // and this install would be shown the introduction.
        val row = queries.selectSettings().executeAsOne()
        assertTrue(row.onboardingCompleted)
        assertTrue(row.notificationsEnabled, "the default such an install has always behaved as")
        driver.close()
    }

    @Test
    fun `a fresh install starts on the introduction with notifications off`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MuvissDatabase.Schema.synchronous().create(driver)
        val queries = MuvissDatabase(driver).appSettingsQueries
        runBlocking { queries.ensureRow() }

        val row = queries.selectSettings().executeAsOne()
        assertFalse(row.onboardingCompleted)
        assertFalse(row.notificationsEnabled, "asked in Onboarding, the nudge or Settings; never opted in by default")
        assertFalse(row.notificationNudgeDismissed)
    }
}
