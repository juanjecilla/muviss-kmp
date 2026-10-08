@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.settings.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.core.common.AppVersion
import com.codingpit.muviss.core.common.flags.FeatureFlags
import com.codingpit.muviss.core.common.flags.SnoozePeriod
import com.codingpit.muviss.core.common.flags.SnoozePlacement
import com.codingpit.muviss.core.common.flags.TriageControlScheme
import com.codingpit.muviss.core.common.notifications.SystemNotificationSettings
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import com.codingpit.muviss.feature.settings.domain.AppSettings
import com.codingpit.muviss.feature.settings.domain.AppTheme
import com.codingpit.muviss.feature.settings.domain.DeleteAllDataUseCase
import com.codingpit.muviss.feature.settings.domain.ExportDataUseCase
import com.codingpit.muviss.feature.settings.domain.LocalDataEraser
import com.codingpit.muviss.feature.settings.domain.ObserveSettingsUseCase
import com.codingpit.muviss.feature.settings.domain.SetCrashReportsEnabledUseCase
import com.codingpit.muviss.feature.settings.domain.SetLanguageUseCase
import com.codingpit.muviss.feature.settings.domain.SetNotificationsEnabledUseCase
import com.codingpit.muviss.feature.settings.domain.SetRegionUseCase
import com.codingpit.muviss.feature.settings.domain.SetThemeUseCase
import com.codingpit.muviss.feature.settings.domain.SettingsActions
import com.codingpit.muviss.feature.settings.domain.SettingsRepository
import com.codingpit.muviss.feature.settings.ui.generated.resources.Res
import com.codingpit.muviss.feature.settings.ui.generated.resources.crash_reports_body
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.jetbrains.compose.resources.getString
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
        override val triageSnoozePeriod = MutableStateFlow(SnoozePeriod.DEFAULT)
        override val triageSnoozePlacement = MutableStateFlow(SnoozePlacement.DEFAULT)
        override suspend fun setTriageControlScheme(scheme: TriageControlScheme) = Unit
        override suspend fun setAnimationsEnabled(enabled: Boolean) = Unit
        override suspend fun setTriageDeckAnimations(enabled: Boolean) = Unit
        override suspend fun setSyncAutomatically(enabled: Boolean) = Unit
        override suspend fun setTriageSnoozePeriod(period: SnoozePeriod) = Unit
        override suspend fun setTriageSnoozePlacement(placement: SnoozePlacement) = Unit
    }

    private var erased = 0

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
        DeleteAllDataUseCase(object : LocalDataEraser {
            override suspend fun deleteAllData() {
                erased++
            }
        }),
        object : AppClock {
            override fun nowEpochMs(): Long = 0L
        },
    )

    /** EPIC 29 (#72): Delete all data is two steps, and the first one cannot delete anything. */
    @Test
    fun one_tap_on_delete_all_only_asks() = runComposeUiTest {
        setContent {
            MuvissTheme(darkTheme = false) { SettingsScreen(viewModel(FakeRepository()), {}, {}, {}) }
        }
        waitForIdle()

        onNodeWithTag(DELETE_ALL_ROW_TAG).performScrollTo().performClick()
        waitForIdle()
        onNodeWithTag(DELETE_ALL_DIALOG_TAG).assertExists()
        assertEquals(0, erased)

        onNodeWithTag(DELETE_ALL_CONFIRM_TAG).performClick()
        waitForIdle()
        assertEquals(1, erased)
        onNodeWithTag(DELETE_ALL_DIALOG_TAG).assertDoesNotExist()
    }

    @Test
    fun the_row_is_there_and_starts_on() = runComposeUiTest {
        val repository = FakeRepository()
        setContent {
            MuvissTheme(darkTheme = false) { SettingsScreen(viewModel(repository), {}, {}, {}) }
        }
        waitForIdle()

        onNodeWithText("Send crash reports").performScrollTo().assertExists()
        // Exactly one switch sits on the Privacy row: it is the last toggleable node.
        onAllNodes(isToggleable()).onLast().assertIsOn()
        assertTrue(repository.settings.value.crashReportsEnabled)
    }

    @Test
    fun a_screen_reader_hears_the_switch_by_its_name() = runComposeUiTest {
        // EPIC 31b (#164): the switch used to be its own node, with no label.
        setContent {
            MuvissTheme(darkTheme = false) { SettingsScreen(viewModel(FakeRepository()), {}, {}, {}) }
        }
        waitForIdle()

        onNode(isToggleable() and hasText("Send crash reports")).performScrollTo().assertIsOn()
    }

    @Test
    fun a_tap_turns_it_off_and_reaches_the_stored_setting() = runComposeUiTest {
        val repository = FakeRepository()
        setContent {
            MuvissTheme(darkTheme = false) { SettingsScreen(viewModel(repository), {}, {}, {}) }
        }
        waitForIdle()

        onNodeWithText("Send crash reports").performScrollTo()
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

        onNodeWithText("Send crash reports").performScrollTo()
        onAllNodes(isToggleable()).onLast().assertIsOff()
    }

    @Test
    fun the_description_says_what_is_and_is_not_sent() = runTest {
        val description = getString(Res.string.crash_reports_body)
        assertTrue("never includes your library" in description)
        assertTrue("Anonymous" in description)
    }

    private class FakeSystemNotifications(var blocked: Boolean) : SystemNotificationSettings {
        var opened = 0
        override fun blocked(): Boolean = blocked
        override fun open() {
            opened++
        }
    }

    @Test
    fun a_blocked_notification_switch_says_so_and_opens_system_settings() = runComposeUiTest {
        // EPIC 30 (#73): on Android 13+ a denial is sticky, and the switch alone
        // would store a preference that does nothing.
        val system = FakeSystemNotifications(blocked = true)
        setContent {
            CompositionLocalProvider(LocalSystemNotificationSettings provides system) {
                MuvissTheme(darkTheme = false) { SettingsScreen(viewModel(FakeRepository()), {}, {}, {}) }
            }
        }
        waitForIdle()

        onNodeWithTag(NOTIFICATIONS_BLOCKED_TAG).assertExists()
        onNodeWithText("Open settings").performClick()
        assertEquals(1, system.opened)
    }

    @Test
    fun an_allowed_notification_switch_shows_no_warning() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalSystemNotificationSettings provides FakeSystemNotifications(blocked = false)) {
                MuvissTheme(darkTheme = false) { SettingsScreen(viewModel(FakeRepository()), {}, {}, {}) }
            }
        }
        waitForIdle()

        onNodeWithTag(NOTIFICATIONS_BLOCKED_TAG).assertDoesNotExist()
    }
}
