@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.codingpit.muviss.feature.search.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * At 328 dp the loaded row shows a third card 84 dp into view, at its full 110 dp width (#747 review). A bounded
 * Row shrank that card to the width left over, so the placeholder no longer matched the card that replaced it.
 */
class CarouselSkeletonRowTest {

    private val card = "skeletonCard"

    @Test
    fun the_partly_visible_card_keeps_its_full_width_in_its_slot() = runComposeUiTest {
        setContent {
            MuvissTheme(darkTheme = false) {
                Box(Modifier.width(328.dp)) {
                    CarouselSkeletonRow(cardModifier = Modifier.testTag(card))
                }
            }
        }
        waitForIdle()

        val cards = onAllNodesWithTag(card, useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals(3, cards.size)
        val third = onAllNodesWithTag(card, useUnmergedTree = true)[2].getUnclippedBoundsInRoot()
        assertEquals(244f, third.left.value, "the third card starts in its slot, 2 x 122 dp in")
        assertEquals(110f, third.width.value, "the third card keeps its full 110 dp")
    }
}
