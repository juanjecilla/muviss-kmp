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

        /** Notifications are off in system settings. */
        var off = false

        /** False once the OS has stopped asking (#326). */
        var askable = true
        override fun blocked(): Boolean = off
        override fun canAskAgain(): Boolean = askable

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

    @Test
    fun a_refusal_the_os_will_not_repeat_is_explained_at_once_without_asking_again() = runTest {
        system.off = true
        system.askable = false
        val vm = viewModel()

        assertEquals(true, vm.state.value.blocked, "offered and refused for good: say why, do not offer a tap that goes nowhere")
        vm.turnOn()
        assertEquals(0, onboarding.accepted, "the OS would answer no at once, so it is not asked")
        assertEquals(true, vm.state.value.visible)
    }

    @Test
    fun a_refusal_the_os_will_not_repeat_shows_nothing_when_the_nudge_is_not_due() = runTest {
        system.off = true
        system.askable = false
        onboarding.offered.value = false

        assertEquals(false, viewModel().state.value.visible)
    }

    @Test
    fun a_refusal_that_can_still_be_asked_again_keeps_the_offer() = runTest {
        system.off = true
        system.askable = true

        assertEquals(false, viewModel().state.value.blocked)
        assertEquals(true, viewModel().state.value.offered)
    }

    @Test
    fun a_permission_granted_in_system_settings_clears_the_explanation_on_resume() = runTest {
        system.off = true
        system.askable = false
        val vm = viewModel()
        assertEquals(true, vm.state.value.blocked)

        system.off = false
        vm.recheckPermission()

        assertEquals(false, vm.state.value.blocked)
    }

    @Test
    fun a_refusal_still_in_force_survives_a_resume() = runTest {
        system.off = true
        system.askable = false
        val vm = viewModel()

        vm.recheckPermission()

        assertEquals(true, vm.state.value.blocked)
    }

    @Test
    fun a_denial_just_given_keeps_its_explanation_after_the_nudge_is_spent_and_on_resume() = runTest {
        onboarding.answer = NotificationNudgeResult.DENIED
        system.off = true
        val vm = viewModel()
        vm.turnOn()
        vm.recheckPermission()

        assertEquals(false, onboarding.offered.value, "the nudge is spent whatever the OS said")
        assertEquals(true, vm.state.value.blocked)
    }
}
