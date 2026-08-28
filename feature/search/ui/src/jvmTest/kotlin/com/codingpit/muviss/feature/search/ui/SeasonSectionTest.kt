@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.codingpit.muviss.feature.search.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import com.codingpit.muviss.models.Episode
import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.Season
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The reported problem was a TV detail screen that rendered every episode of
 * every season at once. These cover the collapse, and the aired-only rule the
 * season toggle has to obey — ticking an unaired episode makes seen exceed
 * aired, which `WatchProgress` forbids and the library screen throws on.
 */
class SeasonSectionTest {

    private val show = MediaId.tmdbTv("1399")
    private val today = 100L

    private fun episode(number: Int, airDay: Long? = 10L) = Episode(EpisodeId(show, 1, number), 1, number, "Episode $number", airDateEpochDay = airDay)

    private val airedSeason = Season(1, "Season 1", listOf(episode(1), episode(2)))

    private fun noActions(
        onMark: () -> Unit = {},
        onUnmark: () -> Unit = {},
        onTap: (EpisodeId) -> Unit = {},
    ) = SeasonActions(
        onMarkSeasonSeen = onMark,
        onUnmarkSeason = onUnmark,
        onTapEpisode = onTap,
        onCatchUp = {},
        onOpenEpisode = {},
    )

    private fun stateWith(vararg seen: EpisodeId) = DetailUiState(loading = false, seenEpisodes = seen.toSet())

    @Test
    fun a_collapsed_season_shows_its_header_but_none_of_its_episodes() = runComposeUiTest {
        setContent {
            MuvissTheme(darkTheme = true) {
                SeasonSection(airedSeason, stateWith(), today, expanded = false, onExpandedChange = {}, actions = noActions())
            }
        }

        onNodeWithTag("${SEASON_HEADER_TAG_PREFIX}1").assertIsDisplayed()
        onNodeWithTag("${SEASON_EPISODES_TAG_PREFIX}1").assertDoesNotExist()
    }

    @Test
    fun an_expanded_season_lists_its_episodes() = runComposeUiTest {
        setContent {
            MuvissTheme(darkTheme = true) {
                SeasonSection(airedSeason, stateWith(), today, expanded = true, onExpandedChange = {}, actions = noActions())
            }
        }

        onNodeWithTag("${SEASON_EPISODES_TAG_PREFIX}1").assertIsDisplayed()
    }

    @Test
    fun tapping_the_header_asks_to_expand() = runComposeUiTest {
        var requested: Boolean? = null
        setContent {
            MuvissTheme(darkTheme = true) {
                SeasonSection(
                    airedSeason,
                    stateWith(),
                    today,
                    expanded = false,
                    onExpandedChange = { requested = it },
                    actions = noActions(),
                )
            }
        }

        onNodeWithTag("${SEASON_HEADER_TAG_PREFIX}1").performClick()

        assertEquals(true, requested)
    }

    @Test
    fun the_header_counts_against_what_has_aired_not_what_exists() = runComposeUiTest {
        // Two aired, one still to come: "1 / 2 aired" is a truthful mid-season
        // reading, where "1 / 3" would look permanently unfinished.
        val season = Season(1, "Season 1", listOf(episode(1), episode(2), episode(3, airDay = 9_999L)))
        setContent {
            MuvissTheme(darkTheme = true) {
                SeasonSection(
                    season,
                    stateWith(EpisodeId(show, 1, 1)),
                    today,
                    expanded = false,
                    onExpandedChange = {},
                    actions = noActions(),
                )
            }
        }

        onNodeWithText("1 / 2 aired").assertIsDisplayed()
    }

    @Test
    fun the_toggle_reads_as_done_once_every_aired_episode_is_seen() = runComposeUiTest {
        val season = Season(1, "Season 1", listOf(episode(1), episode(2, airDay = 9_999L)))
        setContent {
            MuvissTheme(darkTheme = true) {
                SeasonSection(
                    season,
                    stateWith(EpisodeId(show, 1, 1)),
                    today,
                    expanded = false,
                    onExpandedChange = {},
                    actions = noActions(),
                )
            }
        }

        // Caught up, even though an unaired episode remains.
        onNodeWithTag("${SEASON_TOGGLE_TAG_PREFIX}1").assertIsOn()
    }

    @Test
    fun turning_the_toggle_on_marks_the_season_and_turning_it_off_unmarks_it() = runComposeUiTest {
        var marked = false
        setContent {
            MuvissTheme(darkTheme = true) {
                SeasonSection(
                    airedSeason,
                    stateWith(),
                    today,
                    expanded = false,
                    onExpandedChange = {},
                    actions = noActions(onMark = { marked = true }),
                )
            }
        }

        onNodeWithTag("${SEASON_TOGGLE_TAG_PREFIX}1").assertIsOff().performClick()

        assertTrue(marked)
    }

    @Test
    fun a_season_with_nothing_aired_offers_no_toggle_to_press() = runComposeUiTest {
        val unaired = Season(1, "Season 1", listOf(episode(1, airDay = 9_999L)))
        setContent {
            MuvissTheme(darkTheme = true) {
                SeasonSection(unaired, stateWith(), today, expanded = false, onExpandedChange = {}, actions = noActions())
            }
        }

        onNodeWithTag("${SEASON_TOGGLE_TAG_PREFIX}1").assertIsNotEnabled()
    }

    @Test
    fun an_unaired_episode_cannot_be_ticked() = runComposeUiTest {
        val season = Season(1, "Season 1", listOf(episode(1, airDay = 9_999L)))
        val tapped = mutableListOf<EpisodeId>()
        setContent {
            MuvissTheme(darkTheme = true) {
                SeasonSection(
                    season,
                    stateWith(),
                    today,
                    expanded = true,
                    onExpandedChange = {},
                    actions = noActions(onTap = { tapped += it }),
                )
            }
        }

        onNodeWithContentDescription("Mark Episode 1 S1 · E1 · not aired yet watched").performClick()

        assertEquals(emptyList(), tapped, "ticking it would push seen past aired and crash the library screen")
    }

    @Test
    fun a_rewatched_episode_says_how_many_times() = runComposeUiTest {
        val state = DetailUiState(
            loading = false,
            seenEpisodes = setOf(EpisodeId(show, 1, 1)),
            playCounts = mapOf(EpisodeId(show, 1, 1) to 3),
        )
        setContent {
            MuvissTheme(darkTheme = true) {
                SeasonSection(airedSeason, state, today, expanded = true, onExpandedChange = {}, actions = noActions())
            }
        }

        onNodeWithText("S1 · E1 · watched 3×").assertIsDisplayed()
    }
}
