package com.codingpit.muviss

import com.codingpit.muviss.core.common.AppVersion
import com.codingpit.muviss.core.common.crash.CrashReportingConfig
import com.codingpit.muviss.core.database.DatabaseDriverFactory
import com.codingpit.muviss.core.database.MuvissDatabase
import com.codingpit.muviss.feature.settings.api.SettingsApi
import com.codingpit.muviss.feature.settings.api.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The startup order EPIC 26 exists for, driven through the real database file
 * and a recording stand-in for the reporter (the real one is a process-wide
 * singleton over the real Sentry SDK, which a test must never start).
 */
class MuvissCrashReportingTest {

    private class Recorded {
        var config: CrashReportingConfig? = null
        var enabled: Boolean? = null
        var calls = 0
        val init: (CrashReportingConfig, Boolean) -> Unit = { c, e ->
            config = c
            enabled = e
            calls++
        }
    }

    private fun seed(directory: java.io.File, enabled: Boolean) {
        val driver = DatabaseDriverFactory(directory).create()
        runBlocking {
            val queries = MuvissDatabase(driver).appSettingsQueries
            queries.ensureRow()
            queries.updateCrashReportsEnabled(enabled)
        }
        driver.close()
    }

    @Test
    fun a_first_launch_starts_reporting_on() {
        val recorded = Recorded()

        MuvissCrashReporting.start(DatabaseDriverFactory(createTempDirectory("muviss-crash-start").toFile()), recorded.init)

        assertEquals(true, recorded.enabled)
        assertEquals(1, recorded.calls)
    }

    @Test
    fun a_stored_opt_out_starts_reporting_off_before_any_event_can_be_sent() {
        val directory = createTempDirectory("muviss-crash-optout").toFile()
        seed(directory, enabled = false)
        val recorded = Recorded()

        MuvissCrashReporting.start(DatabaseDriverFactory(directory), recorded.init)

        assertEquals(false, recorded.enabled)
    }

    @Test
    fun the_release_matches_what_the_gradle_plugin_stamps_on_the_mapping() {
        val config = MuvissCrashReporting.config(AppVersion(versionName = "1.4.0", versionCode = 87))

        assertEquals("com.codingpit.muviss@1.4.0+87", config.release)
        assertEquals("87", config.dist)
        assertTrue(config.environment.isNotBlank())
    }

    @Test
    fun the_switch_follows_the_setting_live() {
        val setting = MutableStateFlow(true)
        val seen = mutableListOf<Boolean>()
        val settings = object : SettingsApi {
            override fun observeThemeMode(): Flow<ThemeMode> = MutableStateFlow(ThemeMode.SYSTEM)
            override fun observeNotificationsEnabled(): Flow<Boolean> = MutableStateFlow(true)
            override fun observeCrashReportsEnabled(): Flow<Boolean> = setting
        }

        MuvissCrashReporting.follow(CoroutineScope(Dispatchers.Unconfined), { seen += it }) { settings }
        setting.value = false
        setting.value = true

        assertEquals(listOf(true, false, true), seen)
    }
}
