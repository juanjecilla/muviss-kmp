@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.codingpit.muviss.feature.search.ui

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertTouchHeightIsEqualTo
import androidx.compose.ui.test.assertTouchWidthIsEqualTo
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import com.codingpit.muviss.models.Episode
import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.MediaId
import kotlin.test.Test

/**
 * Regressions found by the a11y audit (docs/AUDIT.md in the talk repo). Each test matches on semantics
 * (role, toggle state), never on text: the JVM's locale picks the language of the string resources.
 */
class AccessibilityTest {

    private val button = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)

    // The audit's static scan flagged `Modifier.size(24.dp)` on this IconButton as a 24 dp target. It is not: Compose
    // enlarges the touch bounds to 48 dp (this passed on the original code too). Kept as a guard against someone
    // disabling LocalMinimumInteractiveComponentSize.
    @Test
    fun the_clear_search_button_keeps_a_48dp_touch_target() = runComposeUiTest {
        setContent { MuvissTheme(darkTheme = true) { SearchPill(query = "dune", onQueryChange = {}) } }

        onNode(button).assertHasClickAction().assertTouchHeightIsEqualTo(48.dp).assertTouchWidthIsEqualTo(48.dp)
    }

    @Test
    fun the_primary_action_is_one_button_named_by_what_it_does() = runComposeUiTest {
        setContent { MuvissTheme(darkTheme = true) { PrimaryActionButton(PrimaryAction.MarkMovieWatched, onAdd = {}, onMarkMovieWatched = {}, onWatchedAgain = {}, onTickNext = {}) } }

        onNode(button).assertHasClickAction()
    }

    // The JVM test locale is pinned to en-US, so the English copy is the expected accessible name.
    @Test
    fun the_movie_primary_action_is_named_by_its_label() = runComposeUiTest {
        setContent { MuvissTheme(darkTheme = true) { PrimaryActionButton(PrimaryAction.MarkMovieWatched, onAdd = {}, onMarkMovieWatched = {}, onWatchedAgain = {}, onTickNext = {}) } }

        onNode(button).assertHasClickAction().assertTextEquals("Mark as watched")
    }

    @Test
    fun the_tick_next_primary_action_is_named_by_the_episode_it_ticks() = runComposeUiTest {
        val episode = Episode(EpisodeId(MediaId.tmdbTv("1399"), 1, 2), 1, 2, "The Kingsroad", airDateEpochDay = 11L)
        setContent { MuvissTheme(darkTheme = true) { PrimaryActionButton(PrimaryAction.TickNext(episode), onAdd = {}, onMarkMovieWatched = {}, onWatchedAgain = {}, onTickNext = {}) } }

        onNode(button).assertHasClickAction().assertContentDescriptionEquals("Mark season 1 episode 2 as seen")
    }
}
