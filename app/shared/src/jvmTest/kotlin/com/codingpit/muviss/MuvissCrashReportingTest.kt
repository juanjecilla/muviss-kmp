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
        var initEnabled: Boolean? = null
        var initCalls = 0
        var setEnabledCalls = mutableListOf<Boolean>()
        val init: (CrashReportingConfig, Boolean) -> Unit = { c, e ->
            config = c
            initEnabled = e
            initCalls++
        }
        val setEnabled: (Boolean) -> Unit = { setEnabledCalls += it }
    }

    // Runs the async consent read synchronously on the calling thread instead
    // of a real background dispatcher, so a test does not need to await it.
    private fun Recorded.start(driverFactory: DatabaseDriverFactory) = MuvissCrashReporting.start(
        driverFactory,
        init,
        setEnabled,
        readScope = CoroutineScope(Dispatchers.Unconfined),
        readDispatcher = Dispatchers.Unconfined,
    )

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
    fun a_first_launch_starts_reporting_on_immediately_and_the_read_confirms_it() {
        val recorded = Recorded()

        recorded.start(DatabaseDriverFactory(createTempDirectory("muviss-crash-start").toFile()))

        // init() runs synchronously, before the database is ever touched (#124),
        // seeded with the same default CrashReportsConsent falls back to.
        assertEquals(true, recorded.initEnabled)
        assertEquals(1, recorded.initCalls)
        assertEquals(listOf(true), recorded.setEnabledCalls)
    }

    @Test
    fun a_stored_opt_out_starts_reporting_on_then_corrects_it_off_within_the_same_call() {
        val directory = createTempDirectory("muviss-crash-optout").toFile()
        seed(directory, enabled = false)
        val recorded = Recorded()

        recorded.start(DatabaseDriverFactory(directory))

        // init() never sees the real stored choice — only setEnabled() does,
        // off-thread — which is the trade #124's KDoc documents.
        assertEquals(true, recorded.initEnabled)
        assertEquals(listOf(false), recorded.setEnabledCalls)
    }

    @Test
    fun the_call_returns_before_the_real_background_read_finishes_and_the_gate_still_catches_up() {
        val directory = createTempDirectory("muviss-crash-nonblocking").toFile()
        seed(directory, enabled = false)
        val recorded = Recorded()

        // No readScope/readDispatcher override this time — the real ones
        // MuvissCrashReporting uses, a background CoroutineScope on a real IO
        // dispatcher. init() (and this call) must be able to return without
        // waiting on it, which is the whole point of #124: the thread calling
        // start() — the main thread on Android — is never the one opening or
        // migrating the database.
        MuvissCrashReporting.start(DatabaseDriverFactory(directory), recorded.init, recorded.setEnabled)

        assertEquals(true, recorded.initEnabled) // returned already, seeded with the default
        val deadline = System.currentTimeMillis() + 2_000
        while (recorded.setEnabledCalls.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
        }
        assertEquals(listOf(false), recorded.setEnabledCalls)
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
