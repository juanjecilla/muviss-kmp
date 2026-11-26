package com.codingpit.muviss.core.sync

import com.codingpit.muviss.core.testing.FakeSupabaseServer
import com.codingpit.muviss.core.testing.inMemoryDatabase
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Sync coverage for the Display Preferences group (ADR 0036, #643). Two real [TestDevice]s share one
 * [FakeSupabaseServer], so each assertion is about what a person sees across devices, through the real
 * backend's wire format and the real engine's order.
 *
 * Stamps are passed explicitly to the stamped writes, so each test states the order of its edits.
 */
class DisplayPreferencesSyncTest {

    private val server = FakeSupabaseServer()

    private fun device() = TestDevice(server, userId = ACCOUNT)

    private suspend fun TestDevice.setTheme(theme: String, at: Long) {
        database.appSettingsQueries.ensureRow()
        database.appSettingsQueries.updateThemeStamped(theme, at)
    }

    private suspend fun TestDevice.setLanguage(language: String, at: Long) {
        database.appSettingsQueries.ensureRow()
        database.appSettingsQueries.updateLanguageStamped(language, at)
    }

    /** A legacy or backfilled group: stamp 0, dirty, with the given values. */
    private suspend fun TestDevice.restoreLegacyGroup(theme: String, language: String) {
        database.appSettingsQueries.ensureRow()
        database.appSettingsQueries.restoreDisplayPreferences(
            theme = theme,
            language = language,
            region = "US",
            animationsEnabled = true,
            triageDeckAnimations = true,
            tickAnimations = true,
            triageControlScheme = "FOUR_WAY",
            triageSnoozePeriod = "ONE_WEEK",
            triageSnoozePlacement = "MIXED_IN",
            mutedGenresInSearch = false,
            stampEpochMs = 0L,
            isDirty = true,
        )
    }

    private fun TestDevice.settings() = database.appSettingsQueries.selectSettings().executeAsOne()

    /** One account's stored group, as the server holds it, or null. */
    private fun accountRow() = server.rows("display_preferences", ACCOUNT).singleOrNull()

    private fun accountText(column: String): String? = accountRow()?.get(column)?.jsonPrimitive?.content

    @Test
    fun `a dirty group is pushed and then marked clean`() = runTest {
        val a = device()
        a.setTheme("DARK", at = 1_000)

        a.sync()

        assertEquals("DARK", accountText("theme"))
        assertFalse(a.settings().displayPreferencesDirty)
    }

    @Test
    fun `a device that never changed a preference pushes no group`() = runTest {
        val fresh = device()
        fresh.database.appSettingsQueries.ensureRow()

        fresh.sync()
        fresh.sync()

        assertTrue(server.requestsTo("display_preferences", "POST").isEmpty(), "defaults are never data (ADR 0036)")
        assertNull(accountRow())
    }

    @Test
    fun `a clean group on the account reaches a device that never changed it`() = runTest {
        val a = device()
        a.setTheme("DARK", at = 1_000)
        a.sync()

        val b = device()
        b.sync()

        assertEquals("DARK", b.settings().theme)
        assertEquals(1_000L, b.settings().displayPreferencesUpdatedAtEpochMs)
        assertFalse(b.settings().displayPreferencesDirty, "a pulled group is the account's, not a change")
    }

    @Test
    fun `a first change on a new device does not carry its defaults over the account's values`() = runTest {
        val a = device()
        a.setLanguage("fr", at = 1_000)
        a.sync()

        val b = device()
        b.setTheme("DARK", at = 2_000)
        b.sync()

        assertEquals("DARK", b.settings().theme, "the device's own change is kept")
        assertEquals("fr", b.settings().language, "a member the device never changed yields to the account")
        assertEquals("fr", accountText("language"))
        assertEquals("DARK", accountText("theme"))
    }

    @Test
    fun `a pull onto a fresh device writes no device switch`() = runTest {
        val a = device()
        a.setTheme("DARK", at = 1_000)
        a.database.appSettingsQueries.updateNotificationsEnabled(true)
        a.database.appSettingsQueries.updateDeviceBackupEnabled(true)
        a.database.appSettingsQueries.updateCrashReportsEnabled(false)
        a.database.appSettingsQueries.updateSyncAutomatically(true)
        a.database.appSettingsQueries.updateOnboardingCompleted(true)
        a.sync()

        val b = device()
        b.database.appSettingsQueries.ensureRow()
        b.sync()

        val settings = b.settings()
        assertEquals("DARK", settings.theme)
        assertFalse(settings.notificationsEnabled)
        assertFalse(settings.deviceBackupEnabled)
        assertTrue(settings.crashReportsEnabled, "crash consent is this install's own")
        assertFalse(settings.syncAutomatically)
        assertFalse(settings.onboardingCompleted)
    }

    @Test
    fun `an unsent local edit newer than the pulled group keeps its own value`() = runTest {
        val a = device()
        a.setTheme("LIGHT", at = 1_000)
        a.sync()

        val b = device()
        b.setLanguage("fr", at = 3_000)
        b.sync()

        a.setTheme("DARK", at = 5_000)
        a.sync()

        assertEquals("DARK", a.settings().theme)
        assertEquals("fr", a.settings().language)
        assertEquals("DARK", accountText("theme"))
    }

    @Test
    fun `concurrent edits to one member resolve by stamp, the later one winning`() = runTest {
        val a = device()
        a.setTheme("DARK", at = 3_000)
        val b = device()
        b.setTheme("LIGHT", at = 2_000)
        b.sync()

        a.sync()

        assertEquals("DARK", a.settings().theme)
        assertEquals("DARK", accountText("theme"))
    }

    @Test
    fun `an unsent edit older than the account's group is replaced by it, the accepted cost of one row`() = runTest {
        val a = device()
        a.setTheme("DARK", at = 1_000)
        val b = device()
        b.setTheme("LIGHT", at = 2_000)
        b.sync()

        a.sync()

        assertEquals("LIGHT", a.settings().theme)
        assertFalse(a.settings().displayPreferencesDirty)
        assertEquals("LIGHT", accountText("theme"))
    }

    @Test
    fun `a group stamped 0 is replaced whole by the account's copy, never merged`() = runTest {
        val a = device()
        a.setTheme("DARK", at = 1_000)
        a.sync()

        val b = device()
        b.restoreLegacyGroup(theme = "LIGHT", language = "de")

        b.sync()

        assertEquals("DARK", b.settings().theme)
        assertEquals("", b.settings().language, "the account's language, not the restored one")
        assertEquals(1_000L, b.settings().displayPreferencesUpdatedAtEpochMs)
        assertFalse(b.settings().displayPreferencesDirty)
    }

    @Test
    fun `a group stamped 0 is pushed when the account has none`() = runTest {
        val b = device()
        b.restoreLegacyGroup(theme = "LIGHT", language = "")

        b.sync()

        assertEquals("LIGHT", accountText("theme"))
        assertFalse(b.settings().displayPreferencesDirty)
    }

    @Test
    fun `clearing a stamp that is no longer the group's leaves it dirty`() = runTest {
        // The push clears the flag only for the stamp it sent (ADR 0020's conditional clearDirty): an edit
        // made while the push was on the wire has a newer stamp, and must stay dirty.
        val a = device()
        a.setTheme("DARK", at = 1_000)

        a.database.appSettingsQueries.clearDisplayPreferencesDirty(pushedAtEpochMs = 999)

        assertTrue(a.settings().displayPreferencesDirty)
    }

    @Test
    fun `resync everything marks a changed group dirty and leaves a never-changed one alone`() = runTest {
        val changed = inMemoryDatabase()
        changed.appSettingsQueries.ensureRow()
        changed.appSettingsQueries.updateThemeStamped("DARK", 1_000)
        val untouched = inMemoryDatabase()
        untouched.appSettingsQueries.ensureRow()

        LocalChangeLog(changed).markAllDirty()
        LocalChangeLog(untouched).markAllDirty()

        assertTrue(changed.appSettingsQueries.selectSettings().executeAsOne().displayPreferencesDirty)
        assertFalse(untouched.appSettingsQueries.selectSettings().executeAsOne().displayPreferencesDirty)
    }

    @Test
    fun `discarding local data resets the group so the account's copy is the only one`() = runTest {
        val a = device()
        a.setTheme("DARK", at = 1_000)

        LocalChangeLog(a.database).deleteAllUserData()

        val settings = a.settings()
        assertEquals("SYSTEM", settings.theme)
        assertNull(settings.displayPreferencesUpdatedAtEpochMs)
        assertFalse(settings.displayPreferencesDirty)
    }

    private companion object {
        const val ACCOUNT = "alice"
    }
}

/** The first-run values in `DisplayPreferenceDefaults` are what a fresh row holds (ADR 0036): one place to change them. */
class DisplayPreferenceDefaultsTest {

    @Test
    fun `a fresh row holds exactly the first-run values the sync compares against`() = runTest {
        val database = inMemoryDatabase()
        database.appSettingsQueries.ensureRow()

        val row = database.appSettingsQueries.selectSettings().executeAsOne()

        assertEquals(DisplayPreferenceDefaults.THEME, row.theme)
        assertEquals(DisplayPreferenceDefaults.LANGUAGE, row.language)
        assertEquals(DisplayPreferenceDefaults.REGION, row.region)
        assertEquals(DisplayPreferenceDefaults.ANIMATIONS_ENABLED, row.animationsEnabled)
        assertEquals(DisplayPreferenceDefaults.TRIAGE_DECK_ANIMATIONS, row.triageDeckAnimations)
        assertEquals(DisplayPreferenceDefaults.TICK_ANIMATIONS, row.tickAnimations)
        assertEquals(DisplayPreferenceDefaults.TRIAGE_CONTROL_SCHEME, row.triageControlScheme)
        assertEquals(DisplayPreferenceDefaults.TRIAGE_SNOOZE_PERIOD, row.triageSnoozePeriod)
        assertEquals(DisplayPreferenceDefaults.TRIAGE_SNOOZE_PLACEMENT, row.triageSnoozePlacement)
        assertEquals(DisplayPreferenceDefaults.MUTED_GENRES_IN_SEARCH, row.mutedGenresInSearch)
    }
}
