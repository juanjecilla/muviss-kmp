@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.triage.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.runComposeUiTest
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import com.codingpit.muviss.core.testing.GoldenSurface
import com.codingpit.muviss.core.testing.assertMatchesGolden
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What the deck *looks* like, which the behavioural tests cannot see.
 *
 * Two things are guarded here. That a queue reads as a queue: before this,
 * `peekedCard` existed and was documented as a stack, but was drawn with a
 * layout `padding(top = …)` behind an opaque, `fillMaxSize` top card — so it
 * was completely occluded and the deck looked like a single lonely card. And
 * that a movie's verdict row says "Watched" where a TV row says "Caught up".
 *
 * JVM-only: golden capture needs Skia. Posters never load in a test, and
 * `PosterImage` falls back to drawing the title, so these are deterministic
 * without a network.
 */
class TriageDeckGoldenTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun a_full_deck_shows_the_cards_waiting_behind_the_top_one() = runComposeUiTest {
        val harness = TriageHarness(tv = listOf(show("1"), show("2"), show("3"), show("4")))
        val viewModel = showDeck(harness, darkTheme = false)

        assertEquals(BACKING_CARD_COUNT, viewModel.state.value.backingCards.size)
        assertMatchesGolden("triage-deck-stacked", tolerance = DECK_TOLERANCE)
    }

    /** The end of the deck: one card, and nothing behind it to imply otherwise. */
    @Test
    fun the_last_card_stands_alone() = runComposeUiTest {
        val harness = TriageHarness(tv = listOf(show("1")))
        val viewModel = showDeck(harness, darkTheme = false)

        assertEquals(0, viewModel.state.value.backingCards.size)
        assertMatchesGolden("triage-deck-single", tolerance = DECK_TOLERANCE)
    }

    @Test
    fun a_movie_deck_offers_three_verdicts_and_says_watched() = runComposeUiTest {
        showDeck(TriageHarness(movies = listOf(movie("1"), movie("2"), movie("3"))), darkTheme = false)
        assertMatchesGolden("triage-deck-movie", tolerance = DECK_TOLERANCE)
    }

    @Test
    fun a_full_deck_shows_the_cards_waiting_behind_the_top_one_dark() = runComposeUiTest {
        showDeck(TriageHarness(tv = listOf(show("1"), show("2"), show("3"), show("4"))), darkTheme = true)
        assertMatchesGolden("triage-deck-stacked-dark", tolerance = DECK_TOLERANCE)
    }

    @Test
    fun the_last_card_stands_alone_dark() = runComposeUiTest {
        showDeck(TriageHarness(tv = listOf(show("1"))), darkTheme = true)
        assertMatchesGolden("triage-deck-single-dark", tolerance = DECK_TOLERANCE)
    }

    @Test
    fun a_movie_deck_offers_three_verdicts_and_says_watched_dark() = runComposeUiTest {
        showDeck(TriageHarness(movies = listOf(movie("1"), movie("2"), movie("3"))), darkTheme = true)
        assertMatchesGolden("triage-deck-movie-dark", tolerance = DECK_TOLERANCE)
    }

    private fun ComposeUiTest.showDeck(harness: TriageHarness, darkTheme: Boolean): TriageViewModel {
        val viewModel = harness.viewModel()
        setContent { DeckUnderTest(viewModel, darkTheme) }
        waitForIdle()
        return viewModel
    }
}

@Composable
private fun DeckUnderTest(viewModel: TriageViewModel, darkTheme: Boolean) {
    // Pinned, never left to default: `MuvissTheme`'s `darkTheme` reads
    // `isSystemInDarkTheme()`, i.e. the *host's* setting, so a golden recorded
    // on a machine in dark mode compares against a light render on CI and every
    // pixel moves. See `GoldenSurface`.
    MuvissTheme(darkTheme = darkTheme) {
        GoldenSurface {
            TriageScreen(viewModel = viewModel, onBack = {}, onOpenSkipped = {}, onOpenSnoozed = {}, onOpenDetail = {})
        }
    }
}

/**
 * Wider than the 0.5% default: a deck card is a poster plus a title, an
 * overview and three verdict labels, and Linux rasterises the bundled font
 * heavier than macOS does. Measured between a recording here and the same
 * frames on `ubuntu-latest`: 1.015% for the stacked and single decks, 0.968%
 * for the movie deck, with the diff marking glyphs and nothing else. Leaves
 * roughly double the observed drift as headroom while staying far below what
 * a card moving would cost. See `assertMatchesGolden`.
 */
private const val DECK_TOLERANCE = 0.02
