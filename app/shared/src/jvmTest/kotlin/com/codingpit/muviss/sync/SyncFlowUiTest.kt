@file:OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.sync

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import com.codingpit.muviss.core.sync.SyncTiming
import com.codingpit.muviss.core.testing.FakeSupabaseServer
import com.codingpit.muviss.feature.profile.ui.ProfileScreen
import com.codingpit.muviss.feature.profile.ui.ProfileViewModel
import com.codingpit.muviss.feature.profile.ui.SYNC_AUTOMATIC_SWITCH_TAG
import com.codingpit.muviss.feature.profile.ui.SYNC_DETAIL_TAG
import com.codingpit.muviss.feature.profile.ui.SYNC_LAST_SYNCED_TAG
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

/**
 * The whole feature through the screen a person uses: the real Profile screen
 * and view model over the real Koin graph, the real engine and the real
 * Supabase backend against [FakeSupabaseServer]. Turn the switch on, change
 * something through the real collection repository, and the server has it while
 * the screen reads "Synced just now" with nothing waiting.
 *
 * **Real time, deliberately.** The plan called for advancing a virtual clock,
 * but a Compose UI test has its own frame clock that no coroutine scheduler
 * shares, and the mock engine answers on real threads besides. So the
 * coordinator is built with a 200 ms debounce (the production binding with
 * shorter numbers; `SyncCoordinatorTest` and `AutomaticSyncIntegrationTest`
 * pin the real 5 s / 30 s in virtual time) and this waits on the outcome.
 */
class SyncFlowUiTest {

    private var app: SyncApp? = null

    @BeforeTest
    fun setMain() = Dispatchers.setMain(Dispatchers.Default)

    @AfterTest
    fun tearDown() {
        app?.close()
        Dispatchers.resetMain()
    }

    @Test
    fun turning_the_switch_on_then_changing_something_syncs_it_and_the_screen_says_so() = runComposeUiTest {
        val server = FakeSupabaseServer()
        val sync = SyncApp(
            server = server,
            dispatcher = Dispatchers.Default,
            clock = LambdaClock { System.currentTimeMillis() },
            timing = SyncTiming(debounce = 200.milliseconds, minChangeInterval = 500.milliseconds, foregroundSkipWindow = 60_000.milliseconds),
        ).also { app = it }
        runBlocking { sync.signIn() }
        sync.start()
        val viewModel = sync.koin.get<ProfileViewModel>()

        setContent { MuvissTheme(darkTheme = false) { ProfileScreen(viewModel, onOpenRewatch = {}, onOpenCompanions = {}) } }

        // Signed in and entitled: the switch is there and usable, and off.
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithTagExists(SYNC_AUTOMATIC_SWITCH_TAG) }
        onNodeWithTag(SYNC_AUTOMATIC_SWITCH_TAG).assertIsEnabled()
        assertEquals(false, runBlocking { sync.flags.syncAutomatically.first() })
        assertEquals(0, server.requests.size, "off by default: nothing has reached the server")

        onNodeWithTag(SYNC_AUTOMATIC_SWITCH_TAG).performClick()
        waitUntil(timeoutMillis = 10_000) { runBlocking { sync.flags.syncAutomatically.first() } }

        // The change goes through the same repository the Library screen uses.
        runBlocking { sync.addMovie() }
        waitUntil(timeoutMillis = 10_000) { server.rows("collection_entry", "alice").size == 1 }

        waitUntil(timeoutMillis = 10_000) { !onAllNodesWithTagExists(SYNC_DETAIL_TAG) }
        onNodeWithTag(SYNC_LAST_SYNCED_TAG).assertTextEquals("Synced just now")
    }

    private fun ComposeUiTest.onAllNodesWithTagExists(tag: String): Boolean = onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
}
