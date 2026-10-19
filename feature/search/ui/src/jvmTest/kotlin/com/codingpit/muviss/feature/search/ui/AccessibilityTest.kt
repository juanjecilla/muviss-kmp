@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.codingpit.muviss.feature.search.ui

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertTouchHeightIsEqualTo
import androidx.compose.ui.test.assertTouchWidthIsEqualTo
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import kotlin.test.Test

/**
 * Regressions found by the a11y audit (docs/AUDIT.md in the talk repo). Each test matches on semantics
 * (role, toggle state), never on text: the JVM's locale picks the language of the string resources.
 */
class AccessibilityTest {

    private val button = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)
    private val toggle = SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState)

    // The audit's static scan flagged `Modifier.size(24.dp)` on this IconButton as a 24 dp target. It is not: Compose
    // enlarges the touch bounds to 48 dp (this passed on the original code too). Kept as a guard against someone
    // disabling LocalMinimumInteractiveComponentSize.
    @Test
    fun the_clear_search_button_keeps_a_48dp_touch_target() = runComposeUiTest {
        setContent { MuvissTheme(darkTheme = true) { SearchPill(query = "dune", onQueryChange = {}) } }

        onNode(button).assertHasClickAction().assertTouchHeightIsEqualTo(48.dp).assertTouchWidthIsEqualTo(48.dp)
    }

    @Test
    fun mark_as_watched_is_one_checkbox_not_a_row_and_an_unlabelled_checkbox() = runComposeUiTest {
        setContent { MuvissTheme(darkTheme = true) { MovieWatchedToggle(watched = false, playCount = 0, onToggle = {}) } }

        onAllNodes(toggle).assertCountEquals(1)
        onNode(toggle).assertHasClickAction()
        onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox)).assertHasClickAction()
    }
}
