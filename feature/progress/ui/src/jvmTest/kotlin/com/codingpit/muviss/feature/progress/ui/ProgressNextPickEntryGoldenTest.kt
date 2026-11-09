@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.progress.ui

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.runComposeUiTest
import com.codingpit.muviss.core.testing.GoldenSurface
import com.codingpit.muviss.core.testing.assertMatchesGolden
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * The empty Watch Next with its "What's next?" action (EPIC 51, #601), in both themes. The fixture is
 * [ProgressNextPickEntryTest]'s, so this frame is the same screen the entry tests drive.
 *
 * `darkTheme` is passed explicitly, as CLAUDE.md asks.
 */
class ProgressNextPickEntryGoldenTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun empty_watch_next_with_what_s_next_light() = runComposeUiTest {
        setContent { GoldenSurface { ProgressWithEntry(onOpenNextPick = {}, darkTheme = false) } }
        awaitEmptyWatchNext()
        assertMatchesGolden("progress-empty-what-s-next", tolerance = ENTRY_TOLERANCE)
    }

    @Test
    fun empty_watch_next_with_what_s_next_dark() = runComposeUiTest {
        setContent { GoldenSurface { ProgressWithEntry(onOpenNextPick = {}, darkTheme = true) } }
        awaitEmptyWatchNext()
        assertMatchesGolden("progress-empty-what-s-next-dark", tolerance = ENTRY_TOLERANCE)
    }

    // Watch Next loads off the test dispatcher, so waitForIdle returns before its empty state is composed.
    private fun ComposeUiTest.awaitEmptyWatchNext() = waitUntil(timeoutMillis = 5_000) {
        onAllNodesWithText(EMPTY_TITLE).fetchSemanticsNodes().isNotEmpty()
    }

    private companion object {
        const val EMPTY_TITLE = "Nothing to watch next"
    }
}

/** A short line of text and one button on an otherwise empty frame: the default tolerance is enough. */
private const val ENTRY_TOLERANCE = 0.02
