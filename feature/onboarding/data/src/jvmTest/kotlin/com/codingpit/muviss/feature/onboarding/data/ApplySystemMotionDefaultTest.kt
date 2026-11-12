package com.codingpit.muviss.feature.onboarding.data

import com.codingpit.muviss.core.common.AppDispatchers
import com.codingpit.muviss.core.common.motion.SystemMotionPreference
import com.codingpit.muviss.core.testing.inMemoryDatabase
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApplySystemMotionDefaultTest {

    private val database = inMemoryDatabase()
    private val dispatcher = UnconfinedTestDispatcher()
    private val dispatchers = object : AppDispatchers {
        override val default = dispatcher
        override val io = dispatcher
    }

    private fun applying(reduceMotion: SystemMotionPreference) = ApplySystemMotionDefault(database.appSettingsQueries, dispatchers, reduceMotion)

    private suspend fun animationsEnabled(): Boolean = database.appSettingsQueries.selectSettings().executeAsOne().animationsEnabled

    /** What a person's Settings toggle writes: the column, then the group's stamp (the repository's setter). */
    private suspend fun personChoosesAnimations(enabled: Boolean) {
        database.appSettingsQueries.updateAnimationsEnabled(enabled)
        database.appSettingsQueries.markDisplayPreferencesChanged(nowEpochMs = 5_000L)
    }

    @Test
    fun an_os_that_asks_for_less_motion_turns_the_animations_default_off() = runTest {
        applying(SystemMotionPreference { true })()

        assertFalse(animationsEnabled())
    }

    @Test
    fun without_that_request_the_switch_is_left_alone() = runTest {
        applying(SystemMotionPreference { false })()

        assertTrue(animationsEnabled())
    }

    @Test
    fun a_platform_with_no_signal_changes_nothing() = runTest {
        applying(SystemMotionPreference.None)()

        assertTrue(animationsEnabled())
    }

    @Test
    fun the_os_default_does_not_stamp_or_dirty_the_display_preferences_group() = runTest {
        applying(SystemMotionPreference { true })()

        val row = database.appSettingsQueries.selectSettings().executeAsOne()
        assertFalse(row.animationsEnabled)
        assertNull(row.displayPreferencesUpdatedAtEpochMs, "an OS default is not a change, so the stamp stays null")
        assertFalse(row.displayPreferencesDirty)
    }

    @Test
    fun a_choice_made_after_the_first_launch_is_never_overwritten() = runTest {
        val reduce = applying(SystemMotionPreference { true })
        reduce() // the first launch, perhaps a deep link straight to Search
        personChoosesAnimations(true) // the person turns animations back on in Settings

        reduce() // a later plain launch, the introduction still pending

        assertTrue(animationsEnabled())
    }

    @Test
    fun an_upgraded_install_is_already_marked_and_keeps_its_switch() = runTest {
        database.appSettingsQueries.ensureRow()
        database.appSettingsQueries.markMotionDefaultApplied() // what 21.sqm does to every existing row

        applying(SystemMotionPreference { true })()

        assertTrue(animationsEnabled())
        assertEquals(null, database.appSettingsQueries.selectSettings().executeAsOne().displayPreferencesUpdatedAtEpochMs)
    }
}
