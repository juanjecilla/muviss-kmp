package com.codingpit.muviss.feature.onboarding.data

import com.codingpit.muviss.core.common.motion.SystemMotionPreference
import com.codingpit.muviss.core.testing.FakeFeatureFlags
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApplySystemMotionDefaultTest {

    @Test
    fun an_os_that_asks_for_less_motion_turns_the_animations_default_off() = runTest {
        val flags = FakeFeatureFlags()

        ApplySystemMotionDefault(flags, SystemMotionPreference { true })()

        assertFalse(flags.animationsEnabled.value)
    }

    @Test
    fun without_that_request_the_switch_is_left_alone() = runTest {
        val flags = FakeFeatureFlags()

        ApplySystemMotionDefault(flags, SystemMotionPreference { false })()

        assertTrue(flags.animationsEnabled.value)
    }

    @Test
    fun a_platform_with_no_signal_changes_nothing() = runTest {
        val flags = FakeFeatureFlags()

        ApplySystemMotionDefault(flags, SystemMotionPreference.None)()

        assertTrue(flags.animationsEnabled.value)
    }
}
