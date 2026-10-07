@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.codingpit.muviss.feature.profile.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import com.codingpit.muviss.core.testing.GoldenSurface
import com.codingpit.muviss.feature.profile.domain.MonthlyRewatches
import com.codingpit.muviss.feature.profile.domain.RewatchEntry
import com.codingpit.muviss.feature.profile.domain.RewatchRanking
import com.codingpit.muviss.feature.profile.domain.RewatchStats
import com.codingpit.muviss.feature.profile.domain.RewatchWindow
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaType
import kotlin.test.Test
import kotlin.test.assertEquals

internal fun showEntry(title: String, rewatches: Int) = RewatchEntry(
    mediaId = MediaId.tmdbTv(title.hashCode().toString()),
    title = title,
    posterUrl = null,
    mediaType = MediaType.TV,
    rewatches = rewatches,
)

internal fun movieEntry(title: String, rewatches: Int) = RewatchEntry(
    mediaId = MediaId.tmdbMovie(title.hashCode().toString()),
    title = title,
    posterUrl = null,
    mediaType = MediaType.MOVIE,
    rewatches = rewatches,
)

internal val populatedRanking = RewatchRanking(
    shows = listOf(showEntry("The Office", 41), showEntry("Gilmore Girls", 18), showEntry("Fleabag", 6)),
    movies = listOf(movieEntry("Poor Things", 5), movieEntry("Arrival", 3)),
)

internal val twelveMonths = listOf(1, 3, 7, 2, 1, 5, 8, 3, 1, 2, 6, 4).mapIndexed { index, count ->
    val absolute = 2025 * 12 + 8 + index
    MonthlyRewatches(year = absolute / 12, month = absolute % 12 + 1, rewatches = count)
}

@Composable
internal fun RewatchUnderTest(state: RewatchUiState, onWindowSelected: (RewatchWindow) -> Unit = {}) {
    // Pinned, never left to default: MuvissTheme's darkTheme reads
    // isSystemInDarkTheme(), i.e. the host machine's setting, so a golden
    // recorded in dark mode compares against a light render on CI.
    MuvissTheme(darkTheme = false) {
        GoldenSurface {
            RewatchScreenContent(state, onWindowSelected = onWindowSelected, onBack = {})
        }
    }
}

/**
 * The rewatch screen's behaviour. Rows are matched by tag, never by title:
 * `PosterImage` draws the title as its no-artwork fallback, so a title
 * matcher finds two nodes per row.
 */
class RewatchScreenTest {

    @Test
    fun lists_every_ranked_title_across_both_lists() = runComposeUiTest {
        setContent { RewatchUnderTest(RewatchUiState(loading = false, stats = RewatchStats(ranking = populatedRanking))) }
        waitForIdle()

        assertEquals(5, onAllNodesWithTag(REWATCH_ROW_TAG).fetchSemanticsNodes().size)
        onNodeWithText("Shows").assertIsDisplayed()
        onNodeWithText("Movies").assertIsDisplayed()
    }

    /** The unit is the whole reason the two lists can hold different kinds of number (ADR 0012). */
    @Test
    fun every_row_prints_its_unit() = runComposeUiTest {
        setContent { RewatchUnderTest(RewatchUiState(loading = false, stats = RewatchStats(ranking = populatedRanking))) }
        waitForIdle()

        onNodeWithText("41 episode rewatches").assertIsDisplayed()
        onNodeWithText("5 rewatches").assertIsDisplayed()
    }

    @Test
    fun a_single_rewatch_reads_in_the_singular() = runComposeUiTest {
        val ranking = RewatchRanking(shows = listOf(showEntry("Fleabag", 1)), movies = listOf(movieEntry("Arrival", 1)))
        setContent { RewatchUnderTest(RewatchUiState(loading = false, stats = RewatchStats(ranking = ranking))) }
        waitForIdle()

        onNodeWithText("1 episode rewatch").assertIsDisplayed()
        onNodeWithText("1 rewatch").assertIsDisplayed()
    }

    @Test
    fun choosing_a_window_reports_it() = runComposeUiTest {
        var selected: RewatchWindow? = null
        setContent {
            RewatchUnderTest(
                RewatchUiState(loading = false, stats = RewatchStats(ranking = populatedRanking)),
                onWindowSelected = { selected = it },
            )
        }
        waitForIdle()

        onNodeWithText("This year").performClick()

        assertEquals(RewatchWindow.THIS_YEAR, selected)
    }

    /** Empty is the day-one state for every install, so it has to teach the gesture that fills it. */
    @Test
    fun an_empty_ranking_names_the_gesture_that_fills_it() = runComposeUiTest {
        setContent { RewatchUnderTest(RewatchUiState(loading = false)) }
        waitForIdle()

        onNodeWithTag(REWATCH_EMPTY_TAG).assertIsDisplayed()
        onNodeWithText("Nothing rewatched yet").assertIsDisplayed()
        onNodeWithText("Tap an episode you've already seen and choose “Watched again”.").assertIsDisplayed()
    }

    @Test
    fun the_empty_state_says_which_window_is_empty() = runComposeUiTest {
        setContent { RewatchUnderTest(RewatchUiState(loading = false, stats = RewatchStats(window = RewatchWindow.THIS_YEAR))) }
        waitForIdle()

        onNodeWithText("Nothing rewatched this year").assertIsDisplayed()
    }

    /** The trend keeps its own heading precisely because its window is not the toggle's. */
    @Test
    fun the_trend_is_labelled_with_its_own_window() = runComposeUiTest {
        setContent {
            RewatchUnderTest(
                RewatchUiState(loading = false, stats = RewatchStats(ranking = populatedRanking, monthly = twelveMonths)),
            )
        }
        waitForIdle()

        onNodeWithTag(REWATCH_TREND_TAG).assertIsDisplayed()
        onNodeWithText("Rewatches · last 12 months").assertIsDisplayed()
    }
}
