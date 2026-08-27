@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.triage.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import com.codingpit.muviss.feature.triage.api.TriageVerdict
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The deck's motion, which [TriageScreenTest] cannot see.
 *
 * Every test there ends in `waitForIdle()`, so an animation has always finished
 * before anything is asserted — which is exactly how the original bug survived:
 * the card *did* end up decided, it just never left the screen on the way. These
 * hold the clock still and look at the frames in between.
 *
 * What is observable from here is *when* the deck advances: the card that flies
 * out has to be the one that was decided, so the ViewModel must still be
 * showing it half way through the flight.
 *
 * Where the card is on screen is measurable too, but not on [TRIAGE_CARD_TAG]:
 * that tag is applied to `SwipeCard`'s caller-supplied modifier, which sits
 * *outside* its `graphicsLayer`, so its bounds in root never pass through the
 * layer's translation. [TRIAGE_CARD_FACE_TAG] is on the card's face, below the
 * layer, and does move — see [a_committed_swipe_moves_the_card_across_the_screen].
 * The offset arithmetic itself stays in [SwipeDeckStateTest], which can name
 * each card's offset individually.
 */
class TriageDeckAnimationTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val filmA = movie("1")
    private val filmB = movie("2")

    /** Past the 120.dp commit threshold at any sane test density. */
    private val commitDistance = 600f

    private fun ComposeUiTest.showDeck(harness: TriageHarness): TriageViewModel {
        val viewModel = harness.viewModel()
        setContent { MuvissTheme { TriageScreen(viewModel, onBack = {}, onOpenSkipped = {}, onOpenDetail = {}) } }
        waitForIdle()
        return viewModel
    }

    // --- the card really moves ---

    @Test
    fun a_committed_swipe_moves_the_card_across_the_screen() = runComposeUiTest {
        val harness = TriageHarness(movies = listOf(filmA, filmB))
        showDeck(harness)

        mainClock.autoAdvance = false
        val atRest = onNodeWithTag(TRIAGE_CARD_FACE_TAG).getUnclippedBoundsInRoot().left
        onNodeWithTag(TRIAGE_CARD_TAG).performTouchInput {
            down(center)
            moveBy(Offset(-commitDistance, 0f))
            up()
        }
        mainClock.advanceTimeBy(EXIT_MS / 2L)

        // Not the deck's offset this time — the drawn position of the card's
        // own face, which is what a person actually sees leave.
        val inFlight = onNodeWithTag(TRIAGE_CARD_FACE_TAG).getUnclippedBoundsInRoot().left
        assertTrue(inFlight < atRest, "the card never left its resting position: $atRest -> $inFlight")

        mainClock.autoAdvance = true
        waitForIdle()
    }

    // --- the deck waits for the card ---

    @Test
    fun a_committed_swipe_holds_the_deck_until_the_card_has_gone() = runComposeUiTest {
        val harness = TriageHarness(movies = listOf(filmA, filmB))
        val viewModel = showDeck(harness)

        mainClock.autoAdvance = false
        onNodeWithTag(TRIAGE_CARD_TAG).performTouchInput {
            down(center)
            moveBy(Offset(-commitDistance, 0f))
            up()
        }
        mainClock.advanceTimeBy(EXIT_MS / 2L)

        // Half way out, the card in flight is still the one that was decided.
        // The bug this replaces advanced the deck in the same frame as the
        // release, so the *next* card was what flew — or rather, flew back in.
        assertEquals(filmA.id, viewModel.state.value.topCard?.id)
        assertEquals(emptyMap(), harness.repository.decisions.value)

        mainClock.autoAdvance = true
        waitForIdle()

        assertEquals(TriageVerdict.SKIP, harness.repository.decisions.value[filmA.id]?.verdict)
        assertEquals(filmB.id, viewModel.state.value.topCard?.id)
    }

    @Test
    fun a_verdict_button_plays_the_same_flight() = runComposeUiTest {
        val harness = TriageHarness(movies = listOf(filmA, filmB))
        val viewModel = showDeck(harness)

        mainClock.autoAdvance = false
        onNodeWithText("Skip").performClick()
        mainClock.advanceTimeBy(EXIT_MS / 2L)

        // The buttons are the primary path, and they used to hard-cut to the
        // next card: pressing one advanced the deck immediately.
        assertEquals(filmA.id, viewModel.state.value.topCard?.id)

        mainClock.autoAdvance = true
        waitForIdle()
        assertEquals(TriageVerdict.SKIP, harness.repository.decisions.value[filmA.id]?.verdict)
        assertEquals(filmB.id, viewModel.state.value.topCard?.id)
    }

    @Test
    fun an_arrow_key_plays_the_same_flight() = runComposeUiTest {
        val harness = TriageHarness(movies = listOf(filmA, filmB))
        val viewModel = showDeck(harness)

        mainClock.autoAdvance = false
        onRoot().performKeyInput { pressKey(Key.DirectionLeft) }
        mainClock.advanceTimeBy(EXIT_MS / 2L)

        assertEquals(filmA.id, viewModel.state.value.topCard?.id)

        mainClock.autoAdvance = true
        waitForIdle()
        assertEquals(TriageVerdict.SKIP, harness.repository.decisions.value[filmA.id]?.verdict)
    }

    @Test
    fun the_verdict_stays_named_on_the_card_while_it_flies_out() = runComposeUiTest {
        val harness = TriageHarness(movies = listOf(filmA, filmB))
        showDeck(harness)

        mainClock.autoAdvance = false
        onNodeWithText("Skip").performClick()
        mainClock.advanceTimeBy(EXIT_MS / 2L)

        // A card in flight carries the hint of the verdict that sent it, so a
        // button press is as legible as a drag rather than an unexplained card
        // leaving the screen.
        onNodeWithContentDescription("Release to Skip").assertIsDisplayed()
    }

    @Test
    fun a_second_input_during_the_flight_is_ignored() = runComposeUiTest {
        val harness = TriageHarness(movies = listOf(filmA, filmB))
        val viewModel = showDeck(harness)

        mainClock.autoAdvance = false
        onNodeWithText("Skip").performClick()
        mainClock.advanceTimeBy(EXIT_MS / 2L)
        // The deck has not advanced yet, so this press would land on the card
        // that is already leaving — and the next card would be decided by
        // something the user never saw.
        onNodeWithText("Later").performClick()
        mainClock.advanceTimeBy(EXIT_MS.toLong())
        mainClock.autoAdvance = true
        waitForIdle()

        assertEquals(TriageVerdict.SKIP, harness.repository.decisions.value[filmA.id]?.verdict)
        assertEquals(1, harness.repository.decisions.value.size)
        assertEquals(filmB.id, viewModel.state.value.topCard?.id)
    }

    @Test
    fun undo_puts_the_card_back_and_the_deck_keeps_working() = runComposeUiTest {
        val harness = TriageHarness(movies = listOf(filmA, filmB))
        val viewModel = showDeck(harness)

        onNodeWithTag(TRIAGE_CARD_TAG).performTouchInput {
            down(center)
            moveBy(Offset(-commitDistance, 0f))
            up()
        }
        waitForIdle()
        onNodeWithText("Undo").performClick()
        waitForIdle()

        assertEquals(filmA.id, viewModel.state.value.topCard?.id)
        assertEquals(TriageVerdict.SKIP, viewModel.state.value.restored?.verdict)

        // The re-entry must not leave the deck locked: the restored card has to
        // be decidable again the moment it lands.
        onNodeWithText("Later").performClick()
        waitForIdle()
        assertEquals(TriageVerdict.LATER, harness.repository.decisions.value[filmA.id]?.verdict)
    }

    // --- the Settings toggle ---

    @Test
    fun with_the_deck_toggle_off_the_deck_advances_without_waiting() = runComposeUiTest {
        val harness = TriageHarness(movies = listOf(filmA, filmB), deckAnimations = false)
        val viewModel = showDeck(harness)

        mainClock.autoAdvance = false
        onNodeWithText("Skip").performClick()
        // A single frame, far short of the flight: with motion off the end
        // state is all there is.
        mainClock.advanceTimeBy(FRAME_MS)

        assertEquals(filmB.id, viewModel.state.value.topCard?.id)
    }

    @Test
    fun the_app_wide_switch_turns_the_deck_off_on_its_own() = runComposeUiTest {
        val harness = TriageHarness(movies = listOf(filmA, filmB))
        harness.flags.appAnimations.value = false
        val viewModel = showDeck(harness)

        mainClock.autoAdvance = false
        onNodeWithText("Skip").performClick()
        mainClock.advanceTimeBy(FRAME_MS)

        // The master switch is off while triage's own is on: the deck reads the
        // AND of the two.
        assertEquals(filmB.id, viewModel.state.value.topCard?.id)
    }

    @Test
    fun with_animations_off_a_drag_still_decides_the_card_it_is_on() = runComposeUiTest {
        val harness = TriageHarness(movies = listOf(filmA, filmB), deckAnimations = false)
        showDeck(harness)

        onNodeWithTag(TRIAGE_CARD_TAG).performTouchInput {
            down(center)
            moveBy(Offset(-commitDistance, 0f))
        }
        waitForIdle()
        // Dragging is direct manipulation, not decoration: the hint still
        // follows the finger with motion turned off.
        onNodeWithContentDescription("Release to Skip").assertIsDisplayed()

        onNodeWithTag(TRIAGE_CARD_TAG).performTouchInput { up() }
        waitForIdle()
        assertEquals(TriageVerdict.SKIP, harness.repository.decisions.value[filmA.id]?.verdict)
    }

    private companion object {
        const val FRAME_MS = 16L
    }
}
