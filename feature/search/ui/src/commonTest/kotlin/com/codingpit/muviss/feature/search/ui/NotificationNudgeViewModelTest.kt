@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.search.ui

import com.codingpit.muviss.core.common.notifications.SystemNotificationSettings
import com.codingpit.muviss.feature.onboarding.api.NotificationNudgeResult
import com.codingpit.muviss.feature.onboarding.api.OnboardingApi
import com.codingpit.muviss.models.MediaId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class NotificationNudgeViewModelTest {

    private class FakeOnboarding : OnboardingApi {
        val offered = MutableStateFlow(true)
        var answer = NotificationNudgeResult.ENABLED
        var accepted = 0
        var dismissed = 0

        override fun observeCompleted(): Flow<Boolean> = flowOf(true)

        override suspend fun markCompleted() = Unit

        override fun observeNotificationNudge(mediaId: MediaId): Flow<Boolean> = offered

        override suspend fun acceptNotificationNudge(): NotificationNudgeResult {
            accepted++
            offered.value = false // the real one spends the nudge whatever the OS said
            return answer
        }

        override suspend fun dismissNotificationNudge() {
            dismissed++
            offered.value = false
        }
    }

    private class FakeSystem : SystemNotificationSettings {
        var opened = 0
        override fun blocked(): Boolean = false

        override fun open() {
            opened++
        }
    }

    private val onboarding = FakeOnboarding()
    private val system = FakeSystem()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = NotificationNudgeViewModel(MediaId.parse("tmdb:tv:1399"), onboarding, system)

    @Test
    fun it_is_visible_while_onboarding_says_it_is_due() = runTest {
        assertEquals(true, viewModel().state.value.visible)
        onboarding.offered.value = false
        assertEquals(false, viewModel().state.value.visible)
    }

    @Test
    fun turning_it_on_spends_the_nudge_and_hides_the_banner() = runTest {
        val vm = viewModel()
        vm.turnOn()

        assertEquals(1, onboarding.accepted)
        assertEquals(false, vm.state.value.visible)
    }

    @Test
    fun a_refusal_keeps_a_line_that_says_why_instead_of_vanishing() = runTest {
        onboarding.answer = NotificationNudgeResult.DENIED
        val vm = viewModel()
        vm.turnOn()

        assertEquals(true, vm.state.value.blocked)
        assertEquals(true, vm.state.value.visible)
    }

    @Test
    fun the_blocked_line_can_open_system_settings_and_dismissing_clears_it() = runTest {
        onboarding.answer = NotificationNudgeResult.DENIED
        val vm = viewModel()
        vm.turnOn()
        vm.openSystemSettings()
        vm.dismiss()

        assertEquals(1, system.opened)
        assertEquals(false, vm.state.value.visible)
    }

    @Test
    fun the_cross_dismisses_for_good() = runTest {
        val vm = viewModel()
        vm.dismiss()

        assertEquals(1, onboarding.dismissed)
        assertEquals(false, vm.state.value.visible)
    }
}
