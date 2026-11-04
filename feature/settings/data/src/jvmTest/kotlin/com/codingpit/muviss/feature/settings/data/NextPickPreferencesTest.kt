@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.settings.data

import app.cash.turbine.test
import com.codingpit.muviss.core.common.AppDispatchers
import com.codingpit.muviss.core.common.remoteconfig.DefaultsRemoteConfig
import com.codingpit.muviss.core.common.remoteconfig.RemoteValues
import com.codingpit.muviss.core.database.AppSettingsQueries
import com.codingpit.muviss.core.testing.inMemoryDatabase
import com.codingpit.muviss.feature.nextpick.api.ResultMode
import com.codingpit.muviss.feature.nextpick.api.SessionLength
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Next Pick settings (EPIC 51, #403) against real SQLite. The defaults are asserted on the raw row, not on
 * what `fromStored` returns: that tolerates an unknown value by falling back, so a column DEFAULT that drifted from
 * the enum would otherwise pass unnoticed.
 */
class NextPickPreferencesTest {

    private lateinit var queries: AppSettingsQueries
    private lateinit var preferences: AppSettingsNextPickPreferences
    private lateinit var flags: AppSettingsFeatureFlags

    @BeforeTest
    fun setUp() {
        val dispatcher = UnconfinedTestDispatcher()
        val dispatchers = object : AppDispatchers {
            override val default: CoroutineDispatcher = dispatcher
            override val io: CoroutineDispatcher = dispatcher
        }
        queries = inMemoryDatabase().appSettingsQueries
        preferences = AppSettingsNextPickPreferences(queries, dispatchers)
        flags = AppSettingsFeatureFlags(queries, dispatchers, DefaultsRemoteConfig(RemoteValues(byokEnabled = false)))
    }

    @Test
    fun a_fresh_row_stores_the_defaults_the_enums_declare() = runTest {
        queries.ensureRow()

        val row = queries.selectSettings().executeAsOne()

        assertEquals(true, row.completionPromptEnabled)
        assertEquals(SessionLength.DEFAULT.name, row.nextPickSessionLength)
        assertEquals(ResultMode.DEFAULT.name, row.nextPickResultMode)
    }

    @Test
    fun the_defaults_are_standard_one_pick_and_on() = runTest {
        preferences.sessionLength.test {
            assertEquals(SessionLength.STANDARD, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        preferences.resultMode.test {
            assertEquals(ResultMode.ONE_PICK, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        flags.completionPromptEnabled.test {
            assertEquals(true, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun a_session_length_written_is_the_one_read_back() = runTest {
        preferences.setSessionLength(SessionLength.NONE)

        preferences.sessionLength.test {
            assertEquals(SessionLength.NONE, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun a_result_mode_written_is_the_one_read_back() = runTest {
        preferences.setResultMode(ResultMode.DECK)

        preferences.resultMode.test {
            assertEquals(ResultMode.DECK, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun the_completion_prompt_switch_writes_and_reads_back() = runTest {
        flags.setCompletionPromptEnabled(false)

        flags.completionPromptEnabled.test {
            assertEquals(false, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun a_stored_value_this_build_does_not_know_falls_back_to_the_default() = runTest {
        // A newer build, or a hand-edited row, must not crash the screen or the session.
        queries.ensureRow()
        queries.updateNextPickSessionLength("SOMEDAY")
        queries.updateNextPickResultMode("SHORTLIST")

        preferences.sessionLength.test {
            assertEquals(SessionLength.DEFAULT, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        preferences.resultMode.test {
            assertEquals(ResultMode.DEFAULT, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun delete_all_data_brings_every_default_back() = runTest {
        preferences.setSessionLength(SessionLength.QUICK)
        preferences.setResultMode(ResultMode.TOP_PICKS)
        flags.setCompletionPromptEnabled(false)

        // Delete all data hard-deletes the row (SqlDelightLocalDataEraser); the next read recreates it with the defaults.
        queries.deleteRow()

        preferences.sessionLength.test {
            assertEquals(SessionLength.STANDARD, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        preferences.resultMode.test {
            assertEquals(ResultMode.ONE_PICK, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        flags.completionPromptEnabled.test {
            assertEquals(true, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
