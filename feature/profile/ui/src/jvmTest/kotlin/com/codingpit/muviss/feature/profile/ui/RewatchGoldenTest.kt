@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.codingpit.muviss.feature.profile.ui

import androidx.compose.ui.test.runComposeUiTest
import com.codingpit.muviss.core.testing.assertMatchesGolden
import com.codingpit.muviss.feature.profile.domain.RewatchStats
import com.codingpit.muviss.feature.profile.domain.RewatchWindow
import kotlin.test.Test

/**
 * What the rewatch surfaces *look* like, which the behavioural tests cannot
 * see: that the two rankings read as two rankings rather than one merged
 * list, that a bar chart with a zero month leaves a gap rather than closing
 * up, and that the empty card is a legible piece of teaching rather than a
 * blank strip.
 *
 * JVM-only: golden capture needs Skia. Posters never load in a test and
 * `PosterImage` falls back to drawing the title, so these are deterministic
 * without a network.
 */
class RewatchGoldenTest {

    @Test
    fun the_ranking_reads_as_two_lists_over_a_year_of_trend() = runComposeUiTest {
        setContent {
            RewatchUnderTest(
                RewatchUiState(loading = false, stats = RewatchStats(ranking = populatedRanking, monthly = twelveMonths)),
            )
        }
        waitForIdle()
        assertMatchesGolden("rewatch-ranking", tolerance = TEXT_HEAVY_TOLERANCE)
    }

    @Test
    fun the_empty_screen_teaches_the_gesture() = runComposeUiTest {
        setContent {
            RewatchUnderTest(RewatchUiState(loading = false, stats = RewatchStats(monthly = twelveMonths.map { it.copy(rewatches = 0) })))
        }
        waitForIdle()
        assertMatchesGolden("rewatch-empty", tolerance = TEXT_HEAVY_TOLERANCE)
    }

    @Test
    fun this_year_narrows_the_lists_but_not_the_chart() = runComposeUiTest {
        setContent {
            RewatchUnderTest(
                RewatchUiState(
                    loading = false,
                    stats = RewatchStats(
                        window = RewatchWindow.THIS_YEAR,
                        ranking = populatedRanking.copy(movies = emptyList()),
                        monthly = twelveMonths,
                    ),
                ),
            )
        }
        waitForIdle()
        assertMatchesGolden("rewatch-this-year", tolerance = TEXT_HEAVY_TOLERANCE)
    }

    @Test
    fun the_profile_card_is_a_short_list_with_a_way_in() = runComposeUiTest {
        setContent { CardUnderTest(cardEntries) }
        waitForIdle()
        assertMatchesGolden("rewatch-card", tolerance = TEXT_HEAVY_TOLERANCE)
    }

    @Test
    fun the_empty_profile_card_still_says_something() = runComposeUiTest {
        setContent { CardUnderTest(emptyList()) }
        waitForIdle()
        assertMatchesGolden("rewatch-card-empty", tolerance = TEXT_HEAVY_TOLERANCE)
    }
}

/**
 * Wider than the 0.5% default for the same reason the triage deck's goldens
 * are: these frames are almost entirely text, and Linux rasterises the
 * bundled font more heavily than macOS.
 *
 * Raised from the deck's 0.02, which this originally copied. That value was
 * measured against the deck, and these frames carry more text than it does:
 * on Ubuntu the ranking drifted 2.709% and the this-year variant 2.097%,
 * against a 2.000% ceiling. 0.035 leaves roughly a quarter's headroom over the
 * worst observed rather than sitting a tenth of a point under it.
 *
 * Nothing caught this until now because `:feature:profile:ui:jvmTest` was
 * absent from `ci.yml`'s allowlist — the goldens were recorded on macOS and
 * had never once been compared on the runner they were tuned for.
 */
private const val TEXT_HEAVY_TOLERANCE = 0.035
