@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.onboarding.ui

import com.codingpit.muviss.core.common.notifications.platformCanNotify
import com.codingpit.muviss.core.designsystem.component.TmdbKeyProblem
import com.codingpit.muviss.core.testing.FakeFeatureFlags
import com.codingpit.muviss.feature.settings.api.SaveTmdbKeyResult
import com.codingpit.muviss.feature.settings.api.TmdbDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OnboardingViewModelTest {

    private val onboarding = FakeOnboardingApi()
    private val settings = FakeSettingsApi()
    private val key = FakeTmdbKeyApi()
    private val flags = FakeFeatureFlags()
    private val system = FakeSystemNotifications()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = OnboardingViewModel(onboarding, settings, key, flags, system)

    @Test
    fun the_alerts_page_needs_a_platform_that_notifies_and_the_key_page_needs_the_offer() {
        val everything = OnboardingPage.entries
        assertEquals(everything, pagesFor(canNotify = true, byokOffered = true))
        assertFalse(OnboardingPage.NOTIFICATIONS in pagesFor(canNotify = false, byokOffered = true))
        assertFalse(OnboardingPage.TMDB_KEY in pagesFor(canNotify = true, byokOffered = false))
        assertEquals(
            listOf(OnboardingPage.WELCOME, OnboardingPage.LIBRARY, OnboardingPage.WATCH_NEXT, OnboardingPage.DISCOVER, OnboardingPage.IMPORT, OnboardingPage.DONE),
            pagesFor(canNotify = false, byokOffered = false),
        )
    }

    @Test
    fun it_starts_on_the_welcome_page_and_always_ends_on_done() = runTest {
        val state = viewModel().state.value
        assertEquals(OnboardingPage.WELCOME, state.page)
        assertTrue(state.isFirst)
        assertEquals(OnboardingPage.DONE, state.pages.last())
    }

    @Test
    fun the_key_page_appears_only_when_the_app_offers_a_key() = runTest {
        assertFalse(OnboardingPage.TMDB_KEY in viewModel().state.value.pages)

        flags.byokEnabled.value = true
        assertTrue(OnboardingPage.TMDB_KEY in viewModel().state.value.pages)
    }

    @Test
    fun next_and_back_move_one_page_and_stop_at_the_ends() = runTest {
        val vm = viewModel()
        vm.back()
        assertEquals(0, vm.state.value.index, "back from the first page stays put")

        vm.next()
        vm.next()
        assertEquals(2, vm.state.value.index)
        vm.back()
        assertEquals(1, vm.state.value.index)

        repeat(vm.state.value.pages.size + 3) { vm.next() }
        assertTrue(vm.state.value.isLast, "next from the last page stays put")
    }

    @Test
    fun skipping_from_the_first_page_completes_without_touching_any_setting() = runTest {
        val vm = viewModel()
        var finished = false
        vm.finish { finished = true }

        assertTrue(finished)
        assertEquals(1, onboarding.completions)
        assertFalse(settings.notifications.value, "skip must not turn anything on")
        assertEquals(TmdbDataSource.MUVISS, key.source.value)
    }

    @Test
    fun skipping_keeps_what_an_earlier_page_already_wrote() = runTest {
        val vm = viewModel()
        vm.onNotificationsToggled(true)
        vm.finish {}

        assertTrue(settings.notifications.value, "each page writes as it is changed, so skip has nothing to undo")
        assertEquals(1, onboarding.completions)
    }

    @Test
    fun turning_alerts_on_goes_through_settings_and_a_refusal_is_explained() = runTest {
        val vm = viewModel()
        vm.onNotificationsToggled(true)
        assertTrue(vm.state.value.notificationsEnabled)
        assertFalse(vm.state.value.notificationsDenied)

        settings.notifications.value = false
        settings.osAnswer = false
        vm.onNotificationsToggled(true)
        assertFalse(vm.state.value.notificationsEnabled, "the switch must not read on for an alert that cannot arrive")
        assertTrue(vm.state.value.notificationsDenied)
    }

    @Test
    fun the_blocked_note_can_open_the_system_settings() = runTest {
        viewModel().openSystemNotificationSettings()
        assertEquals(1, system.opened)
    }

    @Test
    fun a_saved_key_is_reported_done_and_a_failed_one_is_a_problem() = runTest {
        val vm = viewModel()
        var done = false
        vm.saveKey("a".repeat(32)) { done = true }
        assertTrue(done)
        assertEquals(TmdbDataSource.YOURS, vm.state.value.keySource)
        assertNull(vm.state.value.keyProblem)

        key.result = SaveTmdbKeyResult.REJECTED
        done = false
        vm.saveKey("b".repeat(32)) { done = true }
        assertFalse(done)
        assertEquals(TmdbKeyProblem.REJECTED, vm.state.value.keyProblem)

        vm.dismissKeyProblem()
        assertNull(vm.state.value.keyProblem)
    }

    @Test
    fun a_store_failure_releases_the_key_dialog_with_a_problem() = runTest {
        val vm = viewModel()
        key.failure = IllegalStateException("keychain unavailable")
        var done = false
        vm.saveKey("a".repeat(32)) { done = true }
        assertFalse(vm.state.value.keyChecking, "a thrown save must not leave the dialog locked")
        assertEquals(TmdbKeyProblem.COULD_NOT_CHECK, vm.state.value.keyProblem)
        assertFalse(done)
    }

    @Test
    fun going_back_to_muvisss_key_clears_the_own_one() = runTest {
        val vm = viewModel()
        vm.saveKey("a".repeat(32)) {}
        vm.useMuvissKey {}
        assertEquals(TmdbDataSource.MUVISS, vm.state.value.keySource)
    }

    @Test
    fun the_platform_decides_whether_the_alerts_page_exists() = runTest {
        assertEquals(platformCanNotify, OnboardingPage.NOTIFICATIONS in viewModel().state.value.pages)
    }
}
