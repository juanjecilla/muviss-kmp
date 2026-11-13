@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.settings.data

import app.cash.sqldelight.async.coroutines.awaitAsOne
import com.codingpit.muviss.core.common.AppDispatchers
import com.codingpit.muviss.core.common.AppVersion
import com.codingpit.muviss.core.common.flags.SnoozePeriod
import com.codingpit.muviss.core.common.remoteconfig.DefaultsRemoteConfig
import com.codingpit.muviss.core.common.remoteconfig.RemoteValues
import com.codingpit.muviss.core.testing.FakeClock
import com.codingpit.muviss.core.testing.inMemoryDatabase
import com.codingpit.muviss.feature.settings.domain.AppTheme
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ADR 0036 (#642): every Display Preference a person chooses stamps the group with the clock and marks it
 * dirty. The things that must not are the defaults `ensureRow` seeds, and the device switches and the
 * legacy muted list, which are not in the group.
 */
class DisplayPreferencesStampTest {

    private val database = inMemoryDatabase()
    private val clock = FakeClock(epochMs = 7_000L)
    private val dispatcher = UnconfinedTestDispatcher()
    private val dispatchers = object : AppDispatchers {
        override val default = dispatcher
        override val io = dispatcher
    }
    private val repository = SqlDelightSettingsRepository(
        database.appSettingsQueries,
        ExportQueries(database),
        dispatchers,
        clock,
        AppVersion("test", 1L),
        database,
    )
    private val flags = AppSettingsFeatureFlags(
        database.appSettingsQueries,
        dispatchers,
        DefaultsRemoteConfig(RemoteValues(byokEnabled = false)),
        clock,
    )

    private suspend fun row() = database.appSettingsQueries.selectSettings().awaitAsOne()

    @Test
    fun `a fresh install's defaults carry no stamp and are not dirty`() = runTest {
        database.appSettingsQueries.ensureRow()

        assertNull(row().displayPreferencesUpdatedAtEpochMs)
        assertFalse(row().displayPreferencesDirty)
    }

    @Test
    fun `setting the theme stamps the group with the clock and marks it dirty`() = runTest {
        repository.setTheme(AppTheme.DARK)

        assertEquals(7_000L, row().displayPreferencesUpdatedAtEpochMs)
        assertTrue(row().displayPreferencesDirty)
    }

    @Test
    fun `language, region and muted-in-search stamp the group too`() = runTest {
        repository.setLanguage("es-ES")
        assertEquals(7_000L, row().displayPreferencesUpdatedAtEpochMs)

        clock.epochMs = 8_000L
        repository.setRegion("ES")
        assertEquals(8_000L, row().displayPreferencesUpdatedAtEpochMs)

        clock.epochMs = 9_000L
        repository.setMutedGenresInSearch(true)
        assertEquals(9_000L, row().displayPreferencesUpdatedAtEpochMs)
    }

    @Test
    fun `the motion, triage and snooze flags stamp the group`() = runTest {
        flags.setAnimationsEnabled(false)
        assertEquals(7_000L, row().displayPreferencesUpdatedAtEpochMs)
        assertTrue(row().displayPreferencesDirty)

        clock.epochMs = 7_500L
        flags.setTriageSnoozePeriod(SnoozePeriod.ONE_MONTH)
        assertEquals(7_500L, row().displayPreferencesUpdatedAtEpochMs, "each choice moves the group's stamp forward")
    }

    @Test
    fun `a device switch does not stamp the group`() = runTest {
        repository.setNotificationsEnabled(true)
        repository.setCrashReportsEnabled(false)
        flags.setSyncAutomatically(true)

        assertNull(row().displayPreferencesUpdatedAtEpochMs)
        assertFalse(row().displayPreferencesDirty)
    }

    @Test
    fun `the legacy muted list is not in the group, so changing it stamps nothing`() = runTest {
        repository.setGenreMuted(com.codingpit.muviss.models.GenreKey(com.codingpit.muviss.models.MediaType.MOVIE, "27"), "Horror", muted = true)

        assertNull(row().displayPreferencesUpdatedAtEpochMs)
    }
}
