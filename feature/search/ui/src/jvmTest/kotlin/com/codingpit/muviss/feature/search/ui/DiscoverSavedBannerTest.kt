@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.codingpit.muviss.feature.search.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import com.codingpit.muviss.models.Genre
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaType
import kotlin.test.Test

/**
 * The "couldn't refresh" banner shows exactly when the view model says a saved copy is out of date (#724, #974). The
 * rule lives in the view model; these tests only pin that Discover renders the flag it is given.
 */
class DiscoverSavedBannerTest {

    @Test
    fun a_saved_copy_shows_the_banner() = runComposeUiTest {
        setContent { Browse(SearchUiState(discoverSaved = true)) }

        onNodeWithTag(DISCOVER_SAVED_BANNER_TAG).assertIsDisplayed()
    }

    @Test
    fun a_fresh_copy_shows_no_banner() = runComposeUiTest {
        setContent { Browse(SearchUiState(discoverSaved = false)) }

        onAllNodesWithTag(DISCOVER_SAVED_BANNER_TAG).assertCountEquals(0)
    }

    @Composable
    private fun Browse(state: SearchUiState) {
        MuvissTheme(darkTheme = false) {
            DiscoverBrowse(
                state,
                onSelectGenre = { _: Genre, _: MediaType -> },
                onOpenDetail = { _: MediaId -> },
                onOpenTriage = {},
            )
        }
    }
}
