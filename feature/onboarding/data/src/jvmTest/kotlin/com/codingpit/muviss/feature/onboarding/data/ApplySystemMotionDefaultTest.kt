package com.codingpit.muviss.feature.onboarding.data

import com.codingpit.muviss.core.common.AppDispatchers
import com.codingpit.muviss.core.common.motion.SystemMotionPreference
import com.codingpit.muviss.core.testing.FakeFeatureFlags
import com.codingpit.muviss.core.testing.inMemoryDatabase
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApplySystemMotionDefaultTest {

    private val database = inMemoryDatabase()
    private val dispatcher = UnconfinedTestDispatcher()
    private val dispatchers = object : AppDispatchers {
        override val default = dispatcher
        override val io = dispatcher
    }
    private val flags = FakeFeatureFlags()

    private fun applying(reduceMotion: SystemMotionPreference) = ApplySystemMotionDefault(database.appSettingsQueries, dispatchers, flags, reduceMotion)

    @Test
    fun an_os_that_asks_for_less_motion_turns_the_animations_default_off() = runTest {
        applying(SystemMotionPreference { true })()

        assertFalse(flags.animationsEnabled.value)
    }

    @Test
    fun without_that_request_the_switch_is_left_alone() = runTest {
        applying(SystemMotionPreference { false })()

        assertTrue(flags.animationsEnabled.value)
    }

    @Test
    fun a_platform_with_no_signal_changes_nothing() = runTest {
        applying(SystemMotionPreference.None)()

        assertTrue(flags.animationsEnabled.value)
    }

    @Test
    fun a_choice_made_after_the_first_launch_is_never_overwritten() = runTest {
        val reduce = applying(SystemMotionPreference { true })
        reduce() // the first launch, perhaps a deep link straight to Search
        flags.setAnimationsEnabled(true) // the person turns animations back on in Settings

        reduce() // a later plain launch, the introduction still pending

        assertTrue(flags.animationsEnabled.value)
    }

    @Test
    fun an_upgraded_install_is_already_marked_and_keeps_its_switch() = runTest {
        database.appSettingsQueries.ensureRow()
        database.appSettingsQueries.markMotionDefaultApplied() // what 21.sqm does to every existing row

        applying(SystemMotionPreference { true })()

        assertTrue(flags.animationsEnabled.value)
    }
}
