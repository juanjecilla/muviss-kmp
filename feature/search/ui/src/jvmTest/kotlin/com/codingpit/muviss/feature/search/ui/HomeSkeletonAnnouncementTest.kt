@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.codingpit.muviss.feature.search.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.LayoutDirection
import com.codingpit.muviss.core.designsystem.component.SkeletonHost
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import com.codingpit.muviss.models.Genre
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaType
import kotlin.test.Test

/**
 * Home's loading area is announced once, as "Loading", and a loaded Home says nothing about loading (#746). The
 * JVM test locale is pinned to en-US, so the English announcement is the one these tests expect.
 */
class HomeSkeletonAnnouncementTest {

    @Test
    fun a_loading_home_is_announced_once() = runComposeUiTest {
        setContent { Home(SearchUiState(loading = true, hasForYouSeeds = true, forYouLoading = true)) }

        onAllNodesWithContentDescription("Loading").assertCountEquals(1)
    }

    @Test
    fun a_loaded_home_with_no_skeleton_left_announces_nothing() = runComposeUiTest {
        setContent { Home(SearchUiState()) }

        onAllNodesWithContentDescription("Loading").assertCountEquals(0)
    }

    @Test
    fun a_loading_home_under_rtl_is_still_announced_once() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                Home(SearchUiState(loading = true, hasForYouSeeds = true, forYouLoading = true))
            }
        }

        onAllNodesWithContentDescription("Loading").assertCountEquals(1)
    }

    @Composable
    private fun Home(state: SearchUiState) {
        MuvissTheme(darkTheme = false) {
            SkeletonHost(animate = false, announce = state.discoverSkeleton) {
                DiscoverBrowse(
                    state,
                    onSelectGenre = { _: Genre, _: MediaType -> },
                    onOpenDetail = { _: MediaId -> },
                    onOpenTriage = {},
                    muted = MutedChip(available = true),
                )
            }
        }
    }
}
