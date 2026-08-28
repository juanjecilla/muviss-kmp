@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.codingpit.muviss.core.designsystem.component

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Titles wrap to two lines so more of a long one is readable, and the caption
 * box is always two lines tall so a wrapping title in one grid cell doesn't
 * push its neighbours' posters out of alignment.
 *
 * Everything here matches on the caption's test tag rather than its text:
 * `PosterImage` renders the title again as its no-artwork fallback, so
 * matching by text finds two nodes.
 */
class PosterCardTest {

    private val longTitle = "Everything Everywhere All at Once"
    private val shortTitle = "Dune"

    @Test
    fun a_long_title_gets_a_second_line() = runComposeUiTest {
        setContent {
            MuvissTheme(darkTheme = true) {
                Row {
                    PosterCard(title = longTitle, posterUrl = null, onClick = {}, modifier = Modifier.width(110.dp))
                    Text(shortTitle, style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag("oneLine"))
                }
            }
        }

        val caption = onNodeWithTag(POSTER_TITLE_TAG, useUnmergedTree = true).getUnclippedBoundsInRoot().height
        val oneLine = onNodeWithTag("oneLine", useUnmergedTree = true).getUnclippedBoundsInRoot().height

        assertTrue(caption > oneLine * 1.5f, "caption was $caption, a single line is $oneLine")
    }

    @Test
    fun a_short_title_reserves_the_second_line_anyway() = runComposeUiTest {
        setContent {
            MuvissTheme(darkTheme = true) {
                Row {
                    PosterCard(
                        title = longTitle,
                        posterUrl = null,
                        onClick = {},
                        modifier = Modifier.width(110.dp).testTag("long"),
                    )
                    PosterCard(
                        title = shortTitle,
                        posterUrl = null,
                        onClick = {},
                        modifier = Modifier.width(110.dp).testTag("short"),
                    )
                }
            }
        }

        val long = onNodeWithTag("long").getUnclippedBoundsInRoot()
        val short = onNodeWithTag("short").getUnclippedBoundsInRoot()

        assertEquals(long.height, short.height, "a ragged grid is the cost of letting the caption size itself")
    }
}
