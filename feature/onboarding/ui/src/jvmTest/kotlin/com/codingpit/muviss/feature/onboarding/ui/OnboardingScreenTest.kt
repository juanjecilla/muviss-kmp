@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.onboarding.ui

import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import com.codingpit.muviss.core.testing.FakeFeatureFlags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** EPIC 47: the real screen over the real view model, driven the way a person drives it. */
class OnboardingScreenTest {

    private val onboarding = FakeOnboardingApi()
    private val settings = FakeSettingsApi()
    private val key = FakeTmdbKeyApi()
    private val flags = FakeFeatureFlags(byokEnabled = true)

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = OnboardingViewModel(onboarding, settings, key, flags, FakeSystemNotifications())

    private fun androidx.compose.ui.test.ComposeUiTest.screen(onFinished: () -> Unit = {}, onOpenImport: () -> Unit = {}) = setContent {
        MuvissTheme(darkTheme = false) { OnboardingScreen(viewModel(), onFinished, onOpenImport) }
    }

    @Test
    fun skip_is_on_the_first_page_and_ends_the_introduction_straight_away() = runComposeUiTest {
        var finished = 0
        screen(onFinished = { finished++ })

        onNodeWithTag(onboardingPageTag(OnboardingPage.WELCOME)).assertExists()
        onNodeWithTag(ONBOARDING_SKIP_TAG).performClick()

        assertEquals(1, finished)
        assertEquals(1, onboarding.completions)
    }

    @Test
    fun next_walks_every_page_in_order_and_start_finishes() = runComposeUiTest {
        var finished = 0
        screen(onFinished = { finished++ })
        val pages = pagesFor(canNotify = true, byokOffered = true)

        pages.dropLast(1).forEach { page ->
            onNodeWithTag(onboardingPageTag(page)).assertExists()
            onNodeWithTag(ONBOARDING_NEXT_TAG).performClick()
        }
        onNodeWithTag(onboardingPageTag(OnboardingPage.DONE)).assertExists()
        onNodeWithTag(ONBOARDING_SKIP_TAG).assertDoesNotExist()
        onNodeWithTag(ONBOARDING_START_TAG).performClick()

        assertEquals(1, finished)
    }

    @Test
    fun back_returns_to_the_previous_page_and_is_absent_on_the_first() = runComposeUiTest {
        screen()
        onNodeWithTag(ONBOARDING_BACK_TAG).assertDoesNotExist()

        onNodeWithTag(ONBOARDING_NEXT_TAG).performClick()
        onNodeWithTag(onboardingPageTag(OnboardingPage.LIBRARY)).assertExists()
        onNodeWithTag(ONBOARDING_BACK_TAG).performClick()
        onNodeWithTag(onboardingPageTag(OnboardingPage.WELCOME)).assertExists()
    }

    @Test
    fun the_import_page_hands_over_to_the_shell() = runComposeUiTest {
        var opened = 0
        screen(onOpenImport = { opened++ })
        repeat(4) { onNodeWithTag(ONBOARDING_NEXT_TAG).performClick() }

        onNodeWithTag(onboardingPageTag(OnboardingPage.IMPORT)).assertExists()
        onNodeWithText("Import…").performClick()
        assertEquals(1, opened)
    }

    @Test
    fun the_alerts_switch_writes_as_it_is_flipped() = runComposeUiTest {
        screen()
        repeat(5) { onNodeWithTag(ONBOARDING_NEXT_TAG).performClick() }

        onNodeWithTag(ONBOARDING_NOTIFICATIONS_SWITCH_TAG).performClick()
        onNodeWithTag(ONBOARDING_NOTIFICATIONS_SWITCH_TAG).assertIsOn()
        assertEquals(true, settings.notifications.value)
    }

    @Test
    fun a_refused_permission_snaps_the_switch_back_and_says_why() = runComposeUiTest {
        settings.osAnswer = false
        screen()
        repeat(5) { onNodeWithTag(ONBOARDING_NEXT_TAG).performClick() }

        onNodeWithTag(ONBOARDING_NOTIFICATIONS_SWITCH_TAG).performClick()
        onNodeWithText("Notifications are blocked for Muviss in the system settings, so they cannot be turned on from here.").assertExists()
        assertEquals(false, settings.notifications.value)
    }

    @Test
    fun the_genre_page_shows_the_defaults_and_a_tap_flips_a_switch_through_settings() = runComposeUiTest {
        screen()
        val pages = pagesFor(canNotify = true, byokOffered = true)
        repeat(pages.indexOf(OnboardingPage.MUTED_GENRES)) { onNodeWithTag(ONBOARDING_NEXT_TAG).performClick() }

        onNodeWithTag(onboardingPageTag(OnboardingPage.MUTED_GENRES)).assertExists()
        onNodeWithTag(mutedGenreSwitchTag(MutedGenreChoice.TALK)).assertIsOn()
        onNodeWithTag(mutedGenreSwitchTag(MutedGenreChoice.SOAP)).assertIsOff()

        onNodeWithTag(mutedGenreSwitchTag(MutedGenreChoice.TALK)).performClick()
        onNodeWithTag(mutedGenreSwitchTag(MutedGenreChoice.SOAP)).performClick()

        onNodeWithTag(mutedGenreSwitchTag(MutedGenreChoice.TALK)).assertIsOff()
        onNodeWithTag(mutedGenreSwitchTag(MutedGenreChoice.SOAP)).assertIsOn()
    }

    @Test
    fun skip_on_the_genre_page_ends_the_introduction_and_keeps_the_defaults() = runComposeUiTest {
        var finished = 0
        screen(onFinished = { finished++ })
        val pages = pagesFor(canNotify = true, byokOffered = true)
        repeat(pages.indexOf(OnboardingPage.MUTED_GENRES)) { onNodeWithTag(ONBOARDING_NEXT_TAG).performClick() }

        onNodeWithTag(ONBOARDING_SKIP_TAG).performClick()

        assertEquals(1, finished)
        assertEquals(setOf("10767", "10763", "10764"), settings.muted.value.forType(com.codingpit.muviss.models.MediaType.TV))
    }
}
