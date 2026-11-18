@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.search.ui

import com.codingpit.muviss.core.common.notifications.SystemNotificationSettings
import com.codingpit.muviss.feature.onboarding.api.NotificationNudgeResult
import com.codingpit.muviss.feature.onboarding.api.OnboardingApi
import com.codingpit.muviss.models.MediaId
import kotlinx.coroutines.CompletableDeferred
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

        /** When set, the next accept spends the nudge and then waits here before answering (a turn-on still in flight). */
        var heldAccept: CompletableDeferred<Unit>? = null

        override fun observeCompleted(): Flow<Boolean> = flowOf(true)

        override suspend fun markCompleted() = Unit

        override fun observeNotificationNudge(mediaId: MediaId): Flow<Boolean> = offered

        override suspend fun acceptNotificationNudge(): NotificationNudgeResult {
            accepted++
            offered.value = false // the real one spends the nudge whatever the OS said
            heldAccept?.also { heldAccept = null }?.await()
            return answer
        }

        override suspend fun dismissNotificationNudge() {
            dismissed++
            offered.value = false
        }
    }

    /**
     * [off] and [askable] are what the OS says now. The suspend reads answer those and, like iOS, copy them
     * into a cache; the synchronous reads answer only that cache, which a test can leave behind the OS (#846).
     */
    private class FakeSystem : SystemNotificationSettings {
        var opened = 0

        /** Notifications are off in system settings. */
        var off = false

        /** False once the OS has stopped asking (#326). */
        var askable = true

        private var cachedOff = false
        private var cachedAskable = true

        /** Sets what the synchronous reads see, as if the platform had not been asked since the OS changed. */
        fun cache(off: Boolean, askable: Boolean) {
            cachedOff = off
            cachedAskable = askable
        }

        override fun blocked(): Boolean = cachedOff
        override fun canAskAgain(): Boolean = cachedAskable

        /** When set, the next OS read answers what the OS said when it started, and only returns after this completes. */
        var heldRead: CompletableDeferred<Unit>? = null

        override suspend fun blockedNow(): Boolean {
            val answer = off
            heldRead?.also { heldRead = null }?.await()
            cache(off, askable)
            return answer
        }

        override suspend fun canAskAgainNow(): Boolean {
            cache(off, askable)
            return askable
        }

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

    @Test
    fun a_nudge_built_after_a_refusal_was_undone_in_settings_offers_turn_on_not_a_stale_explanation() = runTest {
        system.cache(off = true, askable = false)
        system.off = false
        system.askable = true
        val vm = viewModel()

        assertEquals(false, vm.state.value.blocked, "the OS now allows notifications; the cache still says refused")
        vm.turnOn()
        assertEquals(1, onboarding.accepted, "the OS would show a prompt, so the tap asks")
    }

    @Test
    fun a_turn_on_tapped_after_notifications_were_refused_in_settings_does_not_ask_from_a_stale_cache() = runTest {
        val vm = viewModel()
        system.cache(off = false, askable = true)
        system.off = true
        system.askable = false

        vm.turnOn()

        assertEquals(0, onboarding.accepted, "the OS now refuses for good; a request would answer no at once")
        assertEquals(true, vm.state.value.blocked)
    }

    @Test
    fun a_switch_turned_off_in_settings_while_the_nudge_is_offered_explains_it_on_resume() = runTest {
        val vm = viewModel()
        assertEquals(false, vm.state.value.blocked)

        system.off = true
        system.askable = false
        vm.recheckPermission()

        assertEquals(true, vm.state.value.blocked, "offering Turn on now would only answer no")
        assertEquals(true, vm.state.value.offered, "the nudge itself is still unspent")
    }

    @Test
    fun a_refusal_still_in_force_at_construction_explains_at_once_from_a_stale_allowed_cache() = runTest {
        system.cache(off = false, askable = true)
        system.off = true
        system.askable = false

        assertEquals(true, viewModel().state.value.blocked)
    }

    @Test
    fun a_dismissal_while_the_os_is_being_read_leaves_no_explanation_for_a_refusal() = runTest {
        system.off = true
        system.askable = false
        system.heldRead = CompletableDeferred()
        val vm = viewModel()

        vm.dismiss()
        assertEquals(false, vm.state.value.offered, "the collector takes the dismissal while the read is still pending")

        system.heldRead?.complete(Unit)
        assertEquals(false, vm.state.value.visible, "the settlement read a refusal that was on screen before the dismissal")
        assertEquals(false, vm.state.value.blocked)
    }

    @Test
    fun a_read_that_started_before_a_denial_does_not_clear_the_denial_explanation() = runTest {
        onboarding.answer = NotificationNudgeResult.DENIED
        system.off = false
        system.askable = true
        val held = CompletableDeferred<Unit>()
        system.heldRead = held
        val vm = viewModel()

        vm.turnOn()
        assertEquals(true, vm.state.value.blocked, "the person was just refused")

        held.complete(Unit)
        assertEquals(true, vm.state.value.blocked, "the older read said notifications were allowed; it must not undo the denial")
    }

    @Test
    fun a_dismissal_during_a_turn_on_does_not_show_the_denial_it_then_receives() = runTest {
        onboarding.answer = NotificationNudgeResult.DENIED
        val held = CompletableDeferred<Unit>()
        onboarding.heldAccept = held
        val vm = viewModel()

        vm.turnOn()
        vm.dismiss()
        held.complete(Unit)

        assertEquals(false, vm.state.value.visible, "the person dismissed the nudge before the answer came back")
    }
}
