@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.search.ui

import androidx.compose.ui.test.runComposeUiTest
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import com.codingpit.muviss.core.testing.GoldenSurface
import com.codingpit.muviss.core.testing.assertMatchesGolden
import com.codingpit.muviss.models.WatchStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * The "What's next?" entry on Discover and on the Detail of a Watched title (EPIC 51, #601), in both themes. The
 * fixtures are [NextPickEntryTest]'s, so each frame is the screen those behaviour tests drive.
 *
 * `darkTheme` is passed explicitly, as CLAUDE.md asks.
 */
class NextPickEntryGoldenTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun discover_with_what_s_next_light() = runComposeUiTest {
        setContent { MuvissTheme(darkTheme = false) { GoldenSurface { DiscoverWithEntryContent(onOpenNextPick = {}) } } }
        waitForIdle()
        assertMatchesGolden("discover-what-s-next", tolerance = ENTRY_TOLERANCE)
    }

    @Test
    fun discover_with_what_s_next_dark() = runComposeUiTest {
        setContent { MuvissTheme(darkTheme = true) { GoldenSurface { DiscoverWithEntryContent(onOpenNextPick = {}) } } }
        waitForIdle()
        assertMatchesGolden("discover-what-s-next-dark", tolerance = ENTRY_TOLERANCE)
    }

    @Test
    fun completed_detail_with_what_s_next_light() = runComposeUiTest {
        setContent { MuvissTheme(darkTheme = false) { GoldenSurface { DetailWithEntryContent(WatchStatus.WATCHED, onOpenNextPick = {}) } } }
        waitForIdle()
        assertMatchesGolden("detail-completed-what-s-next", tolerance = ENTRY_TOLERANCE)
    }

    @Test
    fun completed_detail_with_what_s_next_dark() = runComposeUiTest {
        setContent { MuvissTheme(darkTheme = true) { GoldenSurface { DetailWithEntryContent(WatchStatus.WATCHED, onOpenNextPick = {}) } } }
        waitForIdle()
        assertMatchesGolden("detail-completed-what-s-next-dark", tolerance = ENTRY_TOLERANCE)
    }
}

/** Discover and Detail carry posters and text, so they take the text-edge tolerance the other screen goldens use. */
private const val ENTRY_TOLERANCE = 0.04
