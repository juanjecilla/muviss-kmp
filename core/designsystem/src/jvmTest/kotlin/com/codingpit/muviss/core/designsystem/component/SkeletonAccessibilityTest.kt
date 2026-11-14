@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.codingpit.muviss.core.designsystem.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.LayoutDirection
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import kotlin.test.Test

/**
 * A skeleton is announced once, as "Loading", and its placeholders are silent (#746). The JVM test locale is pinned
 * to en-US, so "Loading" is the English announcement the tests expect.
 */
class SkeletonAccessibilityTest {

    @Test
    fun a_grid_of_placeholders_is_announced_once_not_once_per_placeholder() = runComposeUiTest {
        setContent { LoadingGrid(animate = false) }

        onAllNodesWithContentDescription("Loading").assertCountEquals(1)
    }

    @Test
    fun the_placeholders_add_no_text_to_the_accessibility_tree() = runComposeUiTest {
        // The card skeleton carries an invisible caption; it must be cleared, not just transparent.
        setContent {
            MuvissTheme(darkTheme = true) {
                SkeletonHost(animate = false) { repeat(12) { PosterCardSkeleton() } }
            }
        }

        onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun a_host_that_holds_no_loading_content_announces_nothing() = runComposeUiTest {
        setContent {
            MuvissTheme(darkTheme = true) {
                SkeletonHost(animate = false, announce = false) { PosterSkeleton(Modifier) }
            }
        }

        onAllNodesWithContentDescription("Loading").assertCountEquals(0)
    }

    @Test
    fun the_announcement_is_the_same_under_rtl() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) { LoadingGrid(animate = true) }
        }

        onNodeWithContentDescription("Loading").assertExists()
        onAllNodesWithContentDescription("Loading").assertCountEquals(1)
    }

    @Composable
    private fun LoadingGrid(animate: Boolean) {
        MuvissTheme(darkTheme = true) {
            SkeletonHost(animate = animate) {
                repeat(12) { PosterSkeleton(Modifier) }
            }
        }
    }
}
