@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.codingpit.muviss.feature.settings.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import com.codingpit.muviss.core.common.notifications.NotificationStatus
import com.codingpit.muviss.core.common.notifications.SystemNotificationSettings
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import com.codingpit.muviss.feature.settings.domain.AppSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * The "notifications blocked" line must follow what the OS says now. A cached answer on iOS can predate
 * a change made in iOS Settings (#856, #864), so the fake below answers only when the test completes it,
 * and a line shown before that answer would fail here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationsBlockedNoteTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    /** [blocked] is what [statusNow] answers, once [answer] is completed. */
    private class PendingNotifications(
        private val answer: CompletableDeferred<Boolean>,
    ) : SystemNotificationSettings {
        override suspend fun statusNow(): NotificationStatus = NotificationStatus(blocked = answer.await(), canAskAgain = true)
        override fun open() = Unit
    }

    private fun androidx.compose.ui.test.ComposeUiTest.screenWith(system: SystemNotificationSettings) {
        setContent {
            CompositionLocalProvider(LocalSystemNotificationSettings provides system) {
                MuvissTheme(darkTheme = false) {
                    SettingsScreen(settingsViewModel(FakeRepository(AppSettings(notificationsEnabled = true))), {}, {}, {})
                }
            }
        }
    }

    @Test
    fun a_permission_allowed_in_iOS_Settings_shows_no_blocked_line() = runComposeUiTest {
        screenWith(PendingNotifications(answer = CompletableDeferred(false)))
        waitForIdle()

        onNodeWithTag(NOTIFICATIONS_BLOCKED_TAG).assertDoesNotExist()
    }

    @Test
    fun the_first_frame_shows_no_blocked_line_while_the_os_is_still_being_asked() = runComposeUiTest {
        // The OS has not answered yet: nothing is shown until it does.
        val answer = CompletableDeferred<Boolean>()
        screenWith(PendingNotifications(answer = answer))
        waitForIdle()

        onNodeWithTag(NOTIFICATIONS_BLOCKED_TAG).assertDoesNotExist()

        answer.complete(true)
        waitForIdle()
        onNodeWithTag(NOTIFICATIONS_BLOCKED_TAG).assertExists()
    }

    @Test
    fun a_refusal_made_in_iOS_Settings_shows_once_the_os_answers() = runComposeUiTest {
        screenWith(PendingNotifications(answer = CompletableDeferred(true)))
        waitForIdle()

        onNodeWithTag(NOTIFICATIONS_BLOCKED_TAG).assertExists()
    }
}
