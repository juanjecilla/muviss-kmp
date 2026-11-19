@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.codingpit.muviss.feature.settings.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
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
 * The "notifications blocked" line must follow what the OS says now, not the synchronous
 * cache. On iOS `blocked()` is the last cached authorization status, which can predate a
 * change made in iOS Settings (#856); `blockedNow()` is the fresh answer. The fake below
 * lets the two disagree so a regression to the cached read fails here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationsBlockedNoteTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    /** [cached] is what the synchronous read sees; [now] is what [blockedNow] answers, once [answer] is completed. */
    private class StaleCacheNotifications(
        private val cached: Boolean,
        private val answer: CompletableDeferred<Boolean>,
    ) : SystemNotificationSettings {
        override fun blocked(): Boolean = cached
        override suspend fun blockedNow(): Boolean = answer.await()
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
    fun a_stale_blocked_cache_is_not_shown_once_the_os_says_allowed() = runComposeUiTest {
        // Changed to allowed in iOS Settings: the cache still says blocked, the OS does not.
        screenWith(StaleCacheNotifications(cached = true, answer = CompletableDeferred(false)))
        waitForIdle()

        onNodeWithTag(NOTIFICATIONS_BLOCKED_TAG).assertDoesNotExist()
    }

    @Test
    fun the_first_frame_shows_no_blocked_line_while_the_os_is_still_being_asked() = runComposeUiTest {
        // The cache says blocked, but the OS has not answered yet: nothing is shown until it does.
        val answer = CompletableDeferred<Boolean>()
        screenWith(StaleCacheNotifications(cached = true, answer = answer))
        waitForIdle()

        onNodeWithTag(NOTIFICATIONS_BLOCKED_TAG).assertDoesNotExist()

        answer.complete(true)
        waitForIdle()
        onNodeWithTag(NOTIFICATIONS_BLOCKED_TAG).assertExists()
    }

    @Test
    fun a_refusal_made_in_iOS_Settings_shows_once_the_os_answers_even_if_the_cache_says_allowed() = runComposeUiTest {
        screenWith(StaleCacheNotifications(cached = false, answer = CompletableDeferred(true)))
        waitForIdle()

        onNodeWithTag(NOTIFICATIONS_BLOCKED_TAG).assertExists()
    }
}
