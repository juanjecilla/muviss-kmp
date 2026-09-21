@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.settings.ui

import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import com.codingpit.muviss.core.common.AppVersion
import com.codingpit.muviss.core.common.flags.FeatureFlags
import com.codingpit.muviss.core.common.flags.TriageControlScheme
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import com.codingpit.muviss.feature.settings.domain.AppSettings
import com.codingpit.muviss.feature.settings.domain.AppTheme
import com.codingpit.muviss.feature.settings.domain.ExportDataUseCase
import com.codingpit.muviss.feature.settings.domain.ObserveSettingsUseCase
import com.codingpit.muviss.feature.settings.domain.SetCrashReportsEnabledUseCase
import com.codingpit.muviss.feature.settings.domain.SetLanguageUseCase
import com.codingpit.muviss.feature.settings.domain.SetNotificationsEnabledUseCase
import com.codingpit.muviss.feature.settings.domain.SetRegionUseCase
import com.codingpit.muviss.feature.settings.domain.SetThemeUseCase
import com.codingpit.muviss.feature.settings.domain.SettingsActions
import com.codingpit.muviss.feature.settings.domain.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The "Send crash reports" row is the promise `docs/store/LISTING.md` makes —
 * "optional, anonymous crash reports" — so what must hold is that it exists,
 * starts on, and that a tap reaches the stored setting (which the app shell then
 * forwards to the reporter). It renders the real [SettingsScreen] over a fake
 * repository rather than the row alone, because a row nobody can reach is the
 * failure this guards against.
 */
class CrashReportsSettingTest {

    // The screen collects with `collectAsStateWithLifecycle`, which hops to Dispatchers.Main.
    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private class FakeRepository(initial: AppSettings = AppSettings()) : SettingsRepository {
        val settings = MutableStateFlow(initial)

        override fun observeSettings(): Flow<AppSettings> = settings
        override suspend fun setTheme(theme: AppTheme) = settings.update { it.copy(theme = theme) }
        override suspend fun setLanguage(language: String) = settings.update { it.copy(language = language) }
        override suspend fun setRegion(region: String) = settings.update { it.copy(region = region) }
        override suspend fun setNotificationsEnabled(enabled: Boolean) = settings.update { it.copy(notificationsEnabled = enabled) }
        override suspend fun setCrashReportsEnabled(enabled: Boolean) = settings.update { it.copy(crashReportsEnabled = enabled) }
        override suspend fun exportData(): String = "{}"
    }

    private object NoFlags : FeatureFlags {
        override val triageControlScheme = MutableStateFlow(TriageControlScheme.DEFAULT)
        override val animationsEnabled = MutableStateFlow(true)
        override val triageDeckAnimations = MutableStateFlow(true)
        override val syncAutomatically = MutableStateFlow(false)
        override suspend fun setTriageControlScheme(scheme: TriageControlScheme) = Unit
        override suspend fun setAnimationsEnabled(enabled: Boolean) = Unit
        override suspend fun setTriageDeckAnimations(enabled: Boolean) = Unit
        override suspend fun setSyncAutomatically(enabled: Boolean) = Unit
    }

    private fun viewModel(repository: FakeRepository) = SettingsViewModel(
        ObserveSettingsUseCase(repository),
        SettingsActions(
            SetThemeUseCase(repository),
            SetLanguageUseCase(repository),
            SetRegionUseCase(repository),
            SetNotificationsEnabledUseCase(repository),
            SetCrashReportsEnabledUseCase(repository),
            ExportDataUseCase(repository),
        ),
        AppVersion(versionName = "1.0.0", versionCode = 1),
        NoFlags,
    )

    @Test
    fun the_row_is_there_and_starts_on() = runComposeUiTest {
        val repository = FakeRepository()
        setContent {
            MuvissTheme(darkTheme = false) { SettingsScreen(viewModel(repository), {}, {}, {}) }
        }
        waitForIdle()

        onNodeWithText(CRASH_REPORTS_LABEL).performScrollTo().assertExists()
        // Exactly one switch sits on the Privacy row: it is the last toggleable node.
        onAllNodes(isToggleable()).onLast().assertIsOn()
        assertTrue(repository.settings.value.crashReportsEnabled)
    }

    @Test
    fun a_tap_turns_it_off_and_reaches_the_stored_setting() = runComposeUiTest {
        val repository = FakeRepository()
        setContent {
            MuvissTheme(darkTheme = false) { SettingsScreen(viewModel(repository), {}, {}, {}) }
        }
        waitForIdle()

        onNodeWithText(CRASH_REPORTS_LABEL).performScrollTo()
        onAllNodes(isToggleable()).onLast().performClick()
        waitForIdle()

        assertEquals(false, repository.settings.value.crashReportsEnabled)
        onAllNodes(isToggleable()).onLast().assertIsOff()
    }

    @Test
    fun a_stored_opt_out_renders_as_off() = runComposeUiTest {
        val repository = FakeRepository(AppSettings(crashReportsEnabled = false))
        setContent {
            MuvissTheme(darkTheme = false) { SettingsScreen(viewModel(repository), {}, {}, {}) }
        }
        waitForIdle()

        onNodeWithText(CRASH_REPORTS_LABEL).performScrollTo()
        onAllNodes(isToggleable()).onLast().assertIsOff()
    }

    @Test
    fun the_description_says_what_is_and_is_not_sent() {
        assertTrue("never includes your library" in CRASH_REPORTS_DESCRIPTION)
        assertTrue("Anonymous" in CRASH_REPORTS_DESCRIPTION)
    }
}
