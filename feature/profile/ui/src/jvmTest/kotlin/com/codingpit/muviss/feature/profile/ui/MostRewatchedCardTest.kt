@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.codingpit.muviss.feature.profile.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import com.codingpit.muviss.core.testing.GoldenSurface
import com.codingpit.muviss.feature.profile.domain.RewatchEntry
import kotlin.test.Test
import kotlin.test.assertEquals

internal val cardEntries = listOf(showEntry("The Office", 41), showEntry("Gilmore Girls", 18), movieEntry("Poor Things", 5))

@Composable
internal fun CardUnderTest(entries: List<RewatchEntry>, onOpenRewatch: () -> Unit = {}) {
    MuvissTheme(darkTheme = false) {
        GoldenSurface { MostRewatchedCard(entries, onOpenRewatch) }
    }
}

/**
 * The profile card. Its empty state is the case that matters most: ADR 0011's
 * backfill gave every already-seen episode exactly one viewing, so every
 * install's first sight of this card is the empty one, and it is the only
 * place in the app that names the "Watched again" gesture.
 */
class MostRewatchedCardTest {

    @Test
    fun shows_each_entry_with_its_own_unit() = runComposeUiTest {
        setContent { CardUnderTest(cardEntries) }
        waitForIdle()

        assertEquals(3, onAllNodesWithTag(MOST_REWATCHED_ROW_TAG).fetchSemanticsNodes().size)
        onNodeWithText("41 episode rewatches").assertIsDisplayed()
        onNodeWithText("5 rewatches").assertIsDisplayed()
    }

    @Test
    fun opens_the_full_ranking() = runComposeUiTest {
        var opened = 0
        setContent { CardUnderTest(cardEntries, onOpenRewatch = { opened++ }) }
        waitForIdle()

        onNodeWithText("See all").performClick()

        assertEquals(1, opened)
    }

    @Test
    fun an_empty_card_still_renders_and_teaches_the_gesture() = runComposeUiTest {
        setContent { CardUnderTest(emptyList()) }
        waitForIdle()

        onNodeWithTag(MOST_REWATCHED_CARD_TAG).assertIsDisplayed()
        onNodeWithTag(MOST_REWATCHED_EMPTY_TAG).assertIsDisplayed()
        assertEquals(0, onAllNodesWithTag(MOST_REWATCHED_ROW_TAG).fetchSemanticsNodes().size)
    }

    /** Nothing to see all of, so the affordance is not offered — and tapping the heading does nothing. */
    @Test
    fun an_empty_card_offers_no_way_in() = runComposeUiTest {
        var opened = 0
        setContent { CardUnderTest(emptyList(), onOpenRewatch = { opened++ }) }
        waitForIdle()

        onNodeWithText("Most rewatched").performClick()

        assertEquals(0, opened)
    }
}
