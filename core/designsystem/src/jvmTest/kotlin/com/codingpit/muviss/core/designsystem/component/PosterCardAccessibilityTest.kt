@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.codingpit.muviss.core.designsystem.component

import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The a11y audit found each poster announced twice: "Dune, button" (the image's description) and then "Dune"
 * (the caption). The card is now one control, one announcement.
 */
class PosterCardAccessibilityTest {

    @Test
    fun the_card_is_one_button_that_says_the_title_once() = runComposeUiTest {
        setContent {
            MuvissTheme(darkTheme = true) { PosterCard(title = "Dune", posterUrl = null, onClick = {}, modifier = Modifier.width(110.dp).testTag("card")) }
        }
        val config = onNodeWithTag("card").fetchSemanticsNode().config

        assertEquals(Role.Button, config[SemanticsProperties.Role])
        assertEquals(listOf("Dune"), config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text })
    }

    @Test
    fun the_selection_ring_is_also_announced() = runComposeUiTest {
        setContent {
            MuvissTheme(darkTheme = true) { PosterCard(title = "Dune", posterUrl = null, onClick = {}, selected = true, modifier = Modifier.testTag("card")) }
        }

        assertEquals(true, onNodeWithTag("card").fetchSemanticsNode().config[SemanticsProperties.Selected])
    }
}
