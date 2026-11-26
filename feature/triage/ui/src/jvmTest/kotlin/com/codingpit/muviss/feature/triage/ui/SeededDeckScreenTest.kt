@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.triage.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * EPIC 51 (#555, item 1): when a seeded deck runs out it says the picks are done. "All caught up" is a claim about
 * the whole catalogue, which a seeded deck never was, and the catalogue's filter bar is not offered on a seeded deck.
 */
class SeededDeckScreenTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun an_empty_seeded_deck_says_the_picks_are_done_and_never_all_caught_up() = runComposeUiTest {
        val harness = TriageHarness(movies = listOf(movie("1")))

        setContent { MuvissTheme { TriageScreen(harness.seededViewModel(emptyList()), onBack = {}, onOpenSkipped = {}, onOpenSnoozed = {}, onOpenDetail = {}) } }
        waitForIdle()

        onNodeWithText("Your picks are done").assertIsDisplayed()
        onNodeWithText("All caught up").assertDoesNotExist()
        onNodeWithText("Keep looking").assertDoesNotExist()
    }

    @Test
    fun a_restored_empty_seeded_deck_neither_falls_back_to_the_catalogue_nor_says_all_caught_up_and_offers_the_way_back() = runComposeUiTest {
        // The process died, so the holder is empty on restore (#768). The catalogue has titles that must not appear.
        val harness = TriageHarness(movies = listOf(movie("1"), movie("2")))
        var backs = 0

        setContent { MuvissTheme { TriageScreen(harness.seededViewModel(emptyList()), onBack = { backs++ }, onOpenSkipped = {}, onOpenSnoozed = {}, onOpenDetail = {}) } }
        waitForIdle()

        onNodeWithTag(TRIAGE_CARD_TAG).assertDoesNotExist()
        onNodeWithText("Your picks are done").assertIsDisplayed()
        onNodeWithText("All caught up").assertDoesNotExist()
        onNodeWithText("Back to Next Pick").performClick()
        assertEquals(1, backs)
    }

    @Test
    fun a_seeded_deck_with_titles_shows_the_first_one_and_no_catalogue_filter_bar() = runComposeUiTest {
        val harness = TriageHarness(movies = listOf(movie("1")))

        setContent { MuvissTheme { TriageScreen(harness.seededViewModel(listOf(movie("1"))), onBack = {}, onOpenSkipped = {}, onOpenSnoozed = {}, onOpenDetail = {}) } }
        waitForIdle()

        // Matched by tag: the poster falls back to the title, so the text is on screen twice.
        onNodeWithTag(TRIAGE_CARD_TAG).assertIsDisplayed()
        onNodeWithText("Show muted").assertDoesNotExist()
    }
}
