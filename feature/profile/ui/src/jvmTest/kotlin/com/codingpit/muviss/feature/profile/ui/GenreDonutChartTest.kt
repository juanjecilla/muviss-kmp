@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.codingpit.muviss.feature.profile.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import com.codingpit.muviss.core.testing.GoldenSurface
import com.codingpit.muviss.feature.profile.domain.GenreCount
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test

internal val screenshotGenres = listOf(
    GenreCount("Drama", 13),
    GenreCount("Adventure", 11),
    GenreCount("Action", 10),
    GenreCount("Comedy", 7),
    GenreCount("Science Fiction", 7),
    GenreCount("Other", 50),
)

/** Sum of the counts above — what the centre reads when nothing is picked. */
internal const val SCREENSHOT_GENRE_TAGS = "98"

/**
 * Picking a genre, from both the ring and the legend.
 *
 * The legend is the half that matters most here: a slice's arc is only as big
 * as its share, so the rows are what make a one-in-five-hundred genre
 * selectable at all — and they are the only part of the chart a screen reader
 * can operate.
 */
class GenreDonutChartTest {

    @Composable
    private fun DonutUnderTest(genres: List<GenreCount> = screenshotGenres) {
        MuvissTheme(darkTheme = false) {
            GoldenSurface { GenreDonutChart(genres, wide = false) }
        }
    }

    @Test
    fun the_centre_starts_on_the_total_it_divides() = runComposeUiTest {
        setContent { DonutUnderTest() }
        waitForIdle()

        onNodeWithText(SCREENSHOT_GENRE_TAGS).assertIsDisplayed()
        onNodeWithText("genre tags").assertIsDisplayed()
    }

    @Test
    fun tapping_a_legend_row_moves_the_centre_onto_that_genre() = runComposeUiTest {
        setContent { DonutUnderTest() }
        waitForIdle()

        onNodeWithTag(genreLegendTag("Drama")).performClick()
        waitForIdle()

        onNodeWithText("Drama").assertIsDisplayed()
        onNodeWithText("13 · 13%").assertIsDisplayed()
    }

    @Test
    fun tapping_the_same_row_again_goes_back_to_the_total() = runComposeUiTest {
        setContent { DonutUnderTest() }
        waitForIdle()

        onNodeWithTag(genreLegendTag("Drama")).performClick()
        waitForIdle()
        onNodeWithTag(genreLegendTag("Drama")).performClick()
        waitForIdle()

        onNodeWithText(SCREENSHOT_GENRE_TAGS).assertIsDisplayed()
        onNodeWithText("genre tags").assertIsDisplayed()
    }

    @Test
    fun tapping_another_row_switches_rather_than_adds() = runComposeUiTest {
        setContent { DonutUnderTest() }
        waitForIdle()

        onNodeWithTag(genreLegendTag("Drama")).performClick()
        waitForIdle()
        onNodeWithTag(genreLegendTag("Action")).performClick()
        waitForIdle()

        onNodeWithTag(genreLegendTag("Action")).assertIsSelected()
        onNodeWithTag(genreLegendTag("Drama")).assertIsNotSelected()
        onNodeWithText("10 · 10%").assertIsDisplayed()
    }

    @Test
    fun the_selected_row_says_so_in_its_semantics() = runComposeUiTest {
        setContent { DonutUnderTest() }
        waitForIdle()

        onNodeWithTag(genreLegendTag("Comedy")).assertIsNotSelected()
        onNodeWithTag(genreLegendTag("Comedy")).performClick()
        waitForIdle()
        onNodeWithTag(genreLegendTag("Comedy")).assertIsSelected()
    }

    @Test
    fun the_smallest_slice_is_reachable_from_its_row() = runComposeUiTest {
        // One title in five hundred is a 0.7° arc — untappable on the ring, and
        // exactly why the legend rows are selectable.
        val lopsided = listOf(GenreCount("Drama", 499), GenreCount("Documentary", 1))
        setContent { DonutUnderTest(lopsided) }
        waitForIdle()

        onNodeWithTag(genreLegendTag("Documentary")).performClick()
        waitForIdle()

        onNodeWithText("Documentary").assertIsDisplayed()
        onNodeWithText("1 · <1%").assertIsDisplayed()
    }

    @Test
    fun tapping_an_arc_selects_the_genre_under_it() = runComposeUiTest {
        setContent { DonutUnderTest() }
        waitForIdle()

        // Drama is 13/98, so it sweeps ~47.8° clockwise from twelve; aim at its middle.
        onNodeWithTag(GENRE_DONUT_TAG).performTouchInput { click(ringPoint(this.width, degrees = 24f)) }
        waitForIdle()

        onNodeWithText("Drama").assertIsDisplayed()
        onNodeWithText("13 · 13%").assertIsDisplayed()
    }

    @Test
    fun tapping_the_hole_puts_the_total_back() = runComposeUiTest {
        setContent { DonutUnderTest() }
        waitForIdle()

        onNodeWithTag(genreLegendTag("Drama")).performClick()
        waitForIdle()
        onNodeWithTag(GENRE_DONUT_TAG).performTouchInput { click(center) }
        waitForIdle()

        onNodeWithText(SCREENSHOT_GENRE_TAGS).assertIsDisplayed()
    }

    @Test
    fun tapping_a_corner_outside_the_ring_puts_the_total_back() = runComposeUiTest {
        setContent { DonutUnderTest() }
        waitForIdle()

        onNodeWithTag(genreLegendTag("Drama")).performClick()
        waitForIdle()
        onNodeWithTag(GENRE_DONUT_TAG).performTouchInput { click(Offset(1f, 1f)) }
        waitForIdle()

        onNodeWithText(SCREENSHOT_GENRE_TAGS).assertIsDisplayed()
    }

    @Test
    fun a_refresh_that_changes_the_genres_drops_a_stale_selection() = runComposeUiTest {
        // Drama survives the refresh but moves index. Keyed on the index alone
        // the selection would stay put and quietly start describing Horror.
        val genres = mutableStateOf(screenshotGenres)
        setContent {
            MuvissTheme(darkTheme = false) { GoldenSurface { GenreDonutChart(genres.value, wide = false) } }
        }
        waitForIdle()

        onNodeWithTag(genreLegendTag("Drama")).performClick()
        waitForIdle()
        onNodeWithText("Drama").assertIsDisplayed()

        genres.value = listOf(GenreCount("Horror", 4), GenreCount("Drama", 2))
        waitForIdle()

        onNodeWithText("6").assertIsDisplayed()
        onNodeWithText("genre tags").assertIsDisplayed()
        onNodeWithTag(genreLegendTag("Drama")).assertIsNotSelected()
        onNodeWithTag(genreLegendTag("Horror")).assertIsNotSelected()
    }

    /** EPIC 31b (#164): the ring used to clear its semantics and announce nothing. */
    @Test
    fun the_ring_says_what_it_shows() = runComposeUiTest {
        setContent { DonutUnderTest() }
        waitForIdle()

        onNodeWithTag(GENRE_DONUT_TAG).assert(hasContentDescription("Genres: Drama 13%", substring = true))
    }
}

/**
 * A point in the middle of the ring's stroke on a node [side] px wide,
 * [degrees] clockwise from twelve o'clock.
 *
 * The fraction is density-independent: the stroke is a fraction of the
 * donut's width and the selected-slice bonus is a fixed dp of a fixed-dp
 * donut, so both scale with the node.
 */
private fun ringPoint(side: Int, degrees: Float): Offset {
    val radians = degrees * PI.toFloat() / 180f
    val half = side / 2f
    val radius = half * MID_BAND_FRACTION
    return Offset(half + radius * sin(radians), half - radius * cos(radians))
}

/** 1 - strokeFraction(0.18) - bonus(6dp)/size(160dp). */
private const val MID_BAND_FRACTION = 0.7825f
