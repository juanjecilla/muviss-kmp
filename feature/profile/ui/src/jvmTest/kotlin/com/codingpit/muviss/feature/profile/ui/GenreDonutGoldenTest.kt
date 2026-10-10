@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.codingpit.muviss.feature.profile.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.codingpit.muviss.core.designsystem.theme.MuvissSpacing
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import com.codingpit.muviss.core.testing.GoldenSurface
import com.codingpit.muviss.core.testing.assertMatchesGolden
import com.codingpit.muviss.feature.profile.domain.GenreCount
import com.codingpit.muviss.feature.profile.domain.ProfileStats
import com.codingpit.muviss.feature.profile.domain.StatusBreakdown
import com.codingpit.muviss.feature.profile.domain.WatchStreak
import kotlin.test.Test

/**
 * What the genre donut *looks* like, which the behavioural tests cannot see.
 *
 * The frames worth having are the ones that would have caught the bug this
 * chart shipped with: the arc was drawn with a centred `Stroke` into the full
 * canvas rect, so half the stroke — ~11.5dp — fell outside the box, and the
 * ring hung past the screen's own 16dp gutter. Nothing but a picture shows
 * that, which is why `profile-stats-section` renders the whole block rather
 * than the chart alone.
 *
 * Every frame is recorded in both themes (the `-dark` twin): the dimmed,
 * unselected slices and the grey "Other" are exactly the colours that can
 * disappear against a dark surface while looking fine on a light one.
 *
 * JVM-only: golden capture needs Skia. Every test passes `darkTheme`
 * explicitly — `MuvissTheme` otherwise defaults to the *host machine's* dark
 * mode setting and a golden recorded on a Mac in dark mode fails on CI with
 * essentially every pixel moved.
 */
class GenreDonutGoldenTest {

    @Test
    fun the_resting_donut_sits_inside_its_own_box() = runComposeUiTest {
        setContent { DonutFrame(selectedIndex = null, wide = false, darkTheme = false) }
        waitForIdle()
        assertMatchesGolden("genre-donut-narrow-resting", tolerance = TEXT_HEAVY_TOLERANCE)
    }

    @Test
    fun the_resting_donut_sits_inside_its_own_box_dark() = runComposeUiTest {
        setContent { DonutFrame(selectedIndex = null, wide = false, darkTheme = true) }
        waitForIdle()
        assertMatchesGolden("genre-donut-narrow-resting-dark", tolerance = TEXT_HEAVY_TOLERANCE)
    }

    @Test
    fun selecting_a_genre_dims_its_neighbours_and_thickens_it() = runComposeUiTest {
        setContent { DonutFrame(selectedIndex = 0, wide = false, darkTheme = false) }
        waitForIdle()
        assertMatchesGolden("genre-donut-narrow-selected", tolerance = TEXT_HEAVY_TOLERANCE)
    }

    @Test
    fun selecting_a_genre_dims_its_neighbours_and_thickens_it_dark() = runComposeUiTest {
        setContent { DonutFrame(selectedIndex = 0, wide = false, darkTheme = true) }
        waitForIdle()
        assertMatchesGolden("genre-donut-narrow-selected-dark", tolerance = TEXT_HEAVY_TOLERANCE)
    }

    @Test
    fun a_wide_window_puts_the_legend_beside_the_ring() = runComposeUiTest {
        setContent { DonutFrame(selectedIndex = null, wide = true, darkTheme = false, width = WIDE, height = SHORT) }
        waitForIdle()
        assertMatchesGolden("genre-donut-wide-resting", tolerance = TEXT_HEAVY_TOLERANCE)
    }

    @Test
    fun a_wide_window_puts_the_legend_beside_the_ring_dark() = runComposeUiTest {
        setContent { DonutFrame(selectedIndex = null, wide = true, darkTheme = true, width = WIDE, height = SHORT) }
        waitForIdle()
        assertMatchesGolden("genre-donut-wide-resting-dark", tolerance = TEXT_HEAVY_TOLERANCE)
    }

    @Test
    fun the_wide_layout_shows_a_selection_too() = runComposeUiTest {
        setContent { DonutFrame(selectedIndex = 0, wide = true, darkTheme = false, width = WIDE, height = SHORT) }
        waitForIdle()
        assertMatchesGolden("genre-donut-wide-selected", tolerance = TEXT_HEAVY_TOLERANCE)
    }

    @Test
    fun the_wide_layout_shows_a_selection_too_dark() = runComposeUiTest {
        setContent { DonutFrame(selectedIndex = 0, wide = true, darkTheme = true, width = WIDE, height = SHORT) }
        waitForIdle()
        assertMatchesGolden("genre-donut-wide-selected-dark", tolerance = TEXT_HEAVY_TOLERANCE)
    }

    /**
     * #64: at a real tablet width the legend used to stretch each row, and a
     * selected row's highlight, ~500dp past its label. [WIDE] is phone
     * landscape, where that was only loose, so this frame is the one that
     * shows the cap holding.
     */
    @Test
    fun on_a_tablet_the_legend_stops_growing() = runComposeUiTest {
        setContent { DonutFrame(selectedIndex = 0, wide = true, darkTheme = false, width = TABLET_WIDTH, height = TABLET_HEIGHT) }
        waitForIdle()
        assertMatchesGolden("genre-donut-tablet-selected", tolerance = TEXT_HEAVY_TOLERANCE)
    }

    @Test
    fun on_a_tablet_the_legend_stops_growing_dark() = runComposeUiTest {
        setContent { DonutFrame(selectedIndex = 0, wide = true, darkTheme = true, width = TABLET_WIDTH, height = TABLET_HEIGHT) }
        waitForIdle()
        assertMatchesGolden("genre-donut-tablet-selected-dark", tolerance = TEXT_HEAVY_TOLERANCE)
    }

    @Test
    fun one_genre_owns_the_whole_ring() = runComposeUiTest {
        setContent { DonutFrame(genres = listOf(GenreCount("Drama", 7)), selectedIndex = null, wide = false, darkTheme = false) }
        waitForIdle()
        assertMatchesGolden("genre-donut-single-slice", tolerance = TEXT_HEAVY_TOLERANCE)
    }

    @Test
    fun one_genre_owns_the_whole_ring_dark() = runComposeUiTest {
        setContent { DonutFrame(genres = listOf(GenreCount("Drama", 7)), selectedIndex = null, wide = false, darkTheme = true) }
        waitForIdle()
        assertMatchesGolden("genre-donut-single-slice-dark", tolerance = TEXT_HEAVY_TOLERANCE)
    }

    @Test
    fun six_real_genres_get_six_distinct_colours() = runComposeUiTest {
        // No fold happens at exactly six, so all six take a palette colour. With
        // the five-colour palette this chart shipped with, slice 6 wrapped to
        // palette[0] and matched slice 1.
        setContent { DonutFrame(genres = sixGenres, selectedIndex = null, wide = false, darkTheme = false) }
        waitForIdle()
        assertMatchesGolden("genre-donut-six-genres", tolerance = TEXT_HEAVY_TOLERANCE)
    }

    @Test
    fun six_real_genres_get_six_distinct_colours_dark() = runComposeUiTest {
        setContent { DonutFrame(genres = sixGenres, selectedIndex = null, wide = false, darkTheme = true) }
        waitForIdle()
        assertMatchesGolden("genre-donut-six-genres-dark", tolerance = TEXT_HEAVY_TOLERANCE)
    }

    @Test
    fun other_keeps_its_grey_when_selected() = runComposeUiTest {
        // "Other" bypasses the palette entirely, so it is the one slice whose
        // selected colour is not a palette lookup.
        setContent { DonutFrame(selectedIndex = 5, wide = false, darkTheme = false) }
        waitForIdle()
        assertMatchesGolden("genre-donut-other-selected", tolerance = TEXT_HEAVY_TOLERANCE)
    }

    @Test
    fun other_keeps_its_grey_when_selected_dark() = runComposeUiTest {
        setContent { DonutFrame(selectedIndex = 5, wide = false, darkTheme = true) }
        waitForIdle()
        assertMatchesGolden("genre-donut-other-selected-dark", tolerance = TEXT_HEAVY_TOLERANCE)
    }

    @Test
    fun the_stats_section_keeps_every_block_on_one_gutter() = runComposeUiTest {
        setContent { StatsFrame(darkTheme = false) }
        waitForIdle()
        assertMatchesGolden("profile-stats-section", tolerance = TEXT_HEAVY_TOLERANCE)
    }

    @Test
    fun the_stats_section_keeps_every_block_on_one_gutter_dark() = runComposeUiTest {
        setContent { StatsFrame(darkTheme = true) }
        waitForIdle()
        assertMatchesGolden("profile-stats-section-dark", tolerance = TEXT_HEAVY_TOLERANCE)
    }
}

@Composable
private fun DonutFrame(
    genres: List<GenreCount> = screenshotGenres,
    selectedIndex: Int?,
    wide: Boolean,
    darkTheme: Boolean,
    width: Dp = 412.dp,
    height: Dp = 892.dp,
) {
    MuvissTheme(darkTheme = darkTheme) {
        GoldenSurface(width = width, height = height) {
            Column(Modifier.fillMaxWidth().padding(MuvissSpacing.l)) {
                GenreDonutChart(
                    genres = genres,
                    selectedIndex = selectedIndex,
                    onSelect = {},
                    wide = wide,
                )
            }
        }
    }
}

@Composable
private fun StatsFrame(darkTheme: Boolean) {
    MuvissTheme(darkTheme = darkTheme) {
        // Taller than a phone: the section scrolls on the real screen,
        // and a golden that clips mid-row hides whatever it clipped.
        GoldenSurface(height = TALL) {
            // Mirrors ProfileScreen's own scroll column padding, so the
            // gutter in this frame is the gutter on the screen.
            Column(Modifier.fillMaxWidth().padding(MuvissSpacing.l)) {
                StatsSection(statsFixture, onOpenRewatch = {}, wide = false)
            }
        }
    }
}

private val WIDE = 892.dp
private val SHORT = 412.dp
private val TALL = 1040.dp

/** A 10-inch tablet in landscape — well past the ~500dp-per-row stretch #64 describes. */
private val TABLET_WIDTH = 1280.dp
private val TABLET_HEIGHT = 800.dp

private val sixGenres = listOf(
    GenreCount("Drama", 13),
    GenreCount("Adventure", 11),
    GenreCount("Action", 10),
    GenreCount("Comedy", 7),
    GenreCount("Science Fiction", 7),
    GenreCount("Documentary", 6),
)

private val statsFixture = ProfileStats(
    statusBreakdown = StatusBreakdown(notStarted = 12, watching = 5, watched = 23, finished = 1),
    moviesWatched = 23,
    episodesSeen = 418,
    estimatedMinutesWatched = 21_600,
    genreBreakdown = screenshotGenres,
    streak = WatchStreak(currentDays = 4, longestDays = 19),
    mostRewatched = cardEntries,
)

/**
 * Wider than the 0.5% default, matching `RewatchGoldenTest`: these frames are
 * mostly text, and Ubuntu rasterises the bundled font more heavily than macOS.
 */
private const val TEXT_HEAVY_TOLERANCE = 0.035
