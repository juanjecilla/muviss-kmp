@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.codingpit.muviss.feature.search.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import kotlin.test.Test

/**
 * The count comes from the seasons actually rendered below the header rather
 * than from TMDB's `number_of_seasons`, so the two can never disagree —
 * `TmdbProvider` drops season 0 ("Specials"), and a header claiming six
 * seasons above five rows would just look broken.
 */
class SeasonsHeaderTest {

    @Test
    fun the_header_carries_the_number_of_seasons() = runComposeUiTest {
        setContent {
            MuvissTheme(darkTheme = true) {
                SeasonsHeader(seasonCount = 5)
            }
        }

        onNodeWithText("Seasons (5)").assertIsDisplayed()
    }

    @Test
    fun a_single_season_still_reads_naturally() = runComposeUiTest {
        setContent {
            MuvissTheme(darkTheme = true) {
                SeasonsHeader(seasonCount = 1)
            }
        }

        onNodeWithText("Seasons (1)").assertIsDisplayed()
    }
}
