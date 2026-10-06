@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.cowatch

import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.codingpit.muviss.coWatchEntry
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import com.codingpit.muviss.core.sync.SyncAvailability
import com.codingpit.muviss.core.testing.FakeSupabaseServer
import com.codingpit.muviss.feature.profile.ui.ProfileScreen
import com.codingpit.muviss.feature.profile.ui.ProfileViewModel
import com.codingpit.muviss.sync.LambdaClock
import com.codingpit.muviss.sync.SyncApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Co-watch is built on sync, so a build without sync — every release build
 * today (ADR 0018) — offers no way into it, the same rule that hides the
 * profile's sync row.
 */
class CoWatchEntryTest {

    private var app: SyncApp? = null

    @BeforeTest
    fun setMain() = Dispatchers.setMain(Dispatchers.Default)

    @AfterTest
    fun tearDown() {
        app?.close()
        Dispatchers.resetMain()
    }

    @Test
    fun a_build_without_sync_has_no_way_into_co_watch() {
        assertNull(coWatchEntry(availability(configured = false)) {})
    }

    @Test
    fun a_build_with_sync_opens_co_watch() {
        val open = {}
        assertSame(open, coWatchEntry(availability(configured = true), open))
    }

    @Test
    fun the_profile_shows_no_co_watch_row_without_a_way_in() = runComposeUiTest {
        val viewModel = profileViewModel()
        setContent { MuvissTheme(darkTheme = false) { ProfileScreen(viewModel, onOpenRewatch = {}, onOpenCompanions = null) } }
        waitForIdle()

        onNodeWithText(CO_WATCH_ROW).assertDoesNotExist()
    }

    @Test
    fun the_profile_shows_the_co_watch_row_and_it_opens_co_watch() = runComposeUiTest {
        val viewModel = profileViewModel()
        var opened = 0
        setContent { MuvissTheme(darkTheme = false) { ProfileScreen(viewModel, onOpenRewatch = {}, onOpenCompanions = { opened++ }) } }
        waitForIdle()

        onNodeWithText(CO_WATCH_ROW).performClick()
        assertEquals(1, opened)
    }

    private fun profileViewModel(): ProfileViewModel {
        val sync = SyncApp(
            server = FakeSupabaseServer(),
            dispatcher = Dispatchers.Default,
            clock = LambdaClock { System.currentTimeMillis() },
        ).also { app = it }
        return sync.koin.get()
    }

    private fun availability(configured: Boolean) = object : SyncAvailability {
        override fun isConfigured() = configured
        override fun isBackgroundAvailable() = false
    }

    private companion object {
        const val CO_WATCH_ROW = "Watch together"
    }
}
