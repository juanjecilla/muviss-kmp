@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.codingpit.muviss.feature.triage.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.codingpit.muviss.core.common.flags.TriageControlScheme
import com.codingpit.muviss.feature.triage.api.TriageVerdict
import com.codingpit.muviss.models.MediaId
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which card owns which offset — the thing a screen-level test cannot name,
 * because it can only ever see the one card currently on top. Reading the state
 * that drives the motion is what makes `cardA` and `cardB` separately
 * assertable. (That the top card visibly moves *is* observable on screen; see
 * `TriageDeckAnimationTest`, which measures the face node below `SwipeCard`'s
 * `graphicsLayer` — the card's own tag sits above the layer and does not move.)
 *
 * The load-bearing one is
 * [a_card_that_does_not_own_the_offset_draws_at_rest]. That single rule is what
 * fixes the original bug — one shared offset meant the promoted card inherited
 * the flung-out position and flew *back in* while the outgoing card's pixels
 * were swapped underneath it.
 */
class SwipeDeckStateTest {

    private val cardA = movie("1").id
    private val cardB = movie("2").id

    private class Harness {
        /** EPIC 42: long press snoozes; recorded so a slow drag start can be proved not to fire it. */
        val longPressed = mutableListOf<MediaId>()

        lateinit var deck: SwipeDeckState
        lateinit var commit: (MediaId, TriageVerdict) -> Unit
        lateinit var enter: (MediaId, DragDirection) -> Unit
        val decided = mutableListOf<TriageVerdict>()
    }

    /**
     * One card in a fixed-size box, wired the way [TriageScreen] wires it: a
     * committed drag reports the verdict, and the flight plus the decision that
     * follows it belong to the deck state.
     */
    @Composable
    private fun Deck(harness: Harness, cardId: MediaId, animated: Boolean, onTap: (MediaId) -> Unit = {}) {
        val deck = rememberSwipeDeckState(animated = animated)
        val scope = rememberCoroutineScope()
        harness.deck = deck
        harness.commit = { id, verdict -> scope.launch { deck.commit(id, verdict) { harness.decided += verdict } } }
        harness.enter = { id, direction -> scope.launch { deck.enter(id, direction) } }
        Box(Modifier.size(CARD_WIDTH, CARD_HEIGHT)) {
            SwipeCard(
                state = deck,
                cardId = cardId,
                scheme = TriageControlScheme.FOUR_WAY,
                available = TriageVerdict.entries.toList(),
                onDecide = { verdict -> harness.commit(cardId, verdict) },
                // `cardId` is captured by value here, per Deck invocation —
                // exactly like TriageScreen's real `onTap = { onOpenDetail(top.id) }`,
                // where `top` is a fixed local, not a re-readable mutable state.
                onTap = { onTap(cardId) },
                onLongPress = { harness.longPressed += cardId },
                modifier = Modifier.testTag(CARD_TAG),
            ) { _, _ -> }
        }
    }

    private fun ComposeUiTest.show(cardId: MediaId = cardA, animated: Boolean = true): Harness {
        val harness = Harness()
        setContent { Deck(harness, cardId, animated) }
        waitForIdle()
        return harness
    }

    // --- the rule that fixes the bug ---

    @Test
    fun a_card_that_does_not_own_the_offset_draws_at_rest() = runComposeUiTest {
        val harness = show()

        mainClock.autoAdvance = false
        harness.commit(cardA, TriageVerdict.SKIP)
        mainClock.advanceTimeBy(EXIT_MS.toLong() + FRAME_MS)

        // The decided card is parked off screen...
        assertTrue(harness.deck.offsetOf(cardA).x < 0f, "cardA should have flown left, was ${harness.deck.offsetOf(cardA)}")
        // ...and the card promoted behind it is centred anyway, on the very
        // first frame it is drawn, with no snap and no inherited fling.
        assertEquals(Offset.Zero, harness.deck.offsetOf(cardB))
    }

    // --- dragging ---

    @Test
    fun a_drag_moves_the_card_it_is_on() = runComposeUiTest {
        val harness = show()

        onNodeWithTag(CARD_TAG).performTouchInput {
            down(center)
            moveBy(Offset(-DRAG_PX, 0f))
        }
        waitForIdle()

        assertTrue(harness.deck.offsetOf(cardA).x < 0f, "the card did not follow the finger")
    }

    @Test
    fun a_drag_moves_the_card_with_animations_off_too() = runComposeUiTest {
        val harness = show(animated = false)

        onNodeWithTag(CARD_TAG).performTouchInput {
            down(center)
            moveBy(Offset(-DRAG_PX, 0f))
        }
        waitForIdle()

        // Direct manipulation is not an animation: a card under a finger keeps
        // tracking it however the Settings toggle is set.
        assertTrue(harness.deck.offsetOf(cardA).x < 0f, "the card ignored the finger with motion off")
    }

    @Test
    fun a_drag_let_go_short_of_the_threshold_comes_back_to_centre() = runComposeUiTest {
        val harness = show()

        onNodeWithTag(CARD_TAG).performTouchInput {
            down(center)
            moveBy(Offset(-SHORT_DRAG_PX, 0f))
            up()
        }
        waitForIdle()

        assertEquals(Offset.Zero, harness.deck.offsetOf(cardA))
        assertEquals(emptyList(), harness.decided)
    }

    @Test
    fun a_committed_drag_flies_on_from_where_the_finger_left_it() = runComposeUiTest {
        val harness = show()

        mainClock.autoAdvance = false
        onNodeWithTag(CARD_TAG).performTouchInput {
            down(center)
            moveBy(Offset(-DRAG_PX, 0f))
            up()
        }
        mainClock.advanceTimeBy(FRAME_MS)
        val released = harness.deck.offsetOf(cardA).x
        mainClock.advanceTimeBy(EXIT_MS / 2L)

        // It carries on outward rather than snapping back — the release used to
        // animate to zero whether or not it had committed.
        assertTrue(harness.deck.offsetOf(cardA).x < released, "card went back toward centre after committing")
    }

    // --- the flight ---

    @Test
    fun each_verdict_flies_out_along_its_own_axis() = runComposeUiTest {
        listOf(
            TriageVerdict.SKIP to { o: Offset -> o.x < 0f },
            TriageVerdict.LATER to { o: Offset -> o.x > 0f },
            TriageVerdict.CAUGHT_UP to { o: Offset -> o.y < 0f },
            TriageVerdict.WATCHING to { o: Offset -> o.y > 0f },
        ).forEach { (verdict, isOutward) ->
            val harness = show()
            mainClock.autoAdvance = false
            harness.commit(cardA, verdict)
            mainClock.advanceTimeBy(EXIT_MS / 2L)

            assertTrue(isOutward(harness.deck.offsetOf(cardA)), "$verdict left toward ${harness.deck.offsetOf(cardA)}")
            mainClock.autoAdvance = true
            waitForIdle()
        }
    }

    @Test
    fun the_decision_lands_only_once_the_card_is_gone() = runComposeUiTest {
        val harness = show()

        mainClock.autoAdvance = false
        harness.commit(cardA, TriageVerdict.SKIP)
        mainClock.advanceTimeBy(EXIT_MS / 2L)
        assertEquals(emptyList(), harness.decided)

        mainClock.autoAdvance = true
        waitForIdle()
        assertEquals(listOf(TriageVerdict.SKIP), harness.decided)
    }

    @Test
    fun a_commit_during_a_flight_is_dropped_rather_than_queued() = runComposeUiTest {
        val harness = show()

        mainClock.autoAdvance = false
        harness.commit(cardA, TriageVerdict.SKIP)
        mainClock.advanceTimeBy(EXIT_MS / 2L)
        assertTrue(harness.deck.busy)
        harness.commit(cardA, TriageVerdict.LATER)
        mainClock.autoAdvance = true
        waitForIdle()

        // Queued, the second verdict would land on the next card — one the user
        // has not seen.
        assertEquals(listOf(TriageVerdict.SKIP), harness.decided)
        assertFalse(harness.deck.busy)
    }

    @Test
    fun with_motion_off_the_card_is_gone_in_a_single_frame() = runComposeUiTest {
        val harness = show(animated = false)

        mainClock.autoAdvance = false
        harness.commit(cardA, TriageVerdict.SKIP)
        mainClock.advanceTimeBy(FRAME_MS)

        assertEquals(listOf(TriageVerdict.SKIP), harness.decided)
        assertTrue(harness.deck.offsetOf(cardA).x < 0f)
    }

    // --- undo ---

    @Test
    fun undo_brings_the_card_back_from_where_it_left() = runComposeUiTest {
        val harness = show()
        mainClock.autoAdvance = false
        harness.commit(cardA, TriageVerdict.SKIP)
        mainClock.advanceTimeBy(EXIT_MS.toLong() + FRAME_MS)
        val parked = harness.deck.offsetOf(cardA).x

        harness.enter(cardA, directionFor(TriageVerdict.SKIP))
        mainClock.advanceTimeBy(ENTER_MS / 2L)
        val midway = harness.deck.offsetOf(cardA).x

        // Still on the left it went out on, but on its way home.
        assertTrue(midway < 0f, "the card jumped straight back instead of returning from the left")
        assertTrue(midway > parked, "the card is not moving back toward centre")

        mainClock.autoAdvance = true
        waitForIdle()
        assertEquals(Offset.Zero, harness.deck.offsetOf(cardA))
        assertFalse(harness.deck.busy)
    }

    @Test
    fun undo_can_still_place_a_card_the_deck_has_forgotten() = runComposeUiTest {
        val harness = show()

        // Nothing has flown, so nothing is parked — a card restored after a
        // refill or a process restart still has to come in from somewhere.
        mainClock.autoAdvance = false
        harness.enter(cardB, DragDirection.RIGHT)
        mainClock.advanceTimeBy(FRAME_MS)

        assertTrue(harness.deck.offsetOf(cardB).x > 0f, "the restored card was not placed off screen first")

        mainClock.autoAdvance = true
        waitForIdle()
        assertEquals(Offset.Zero, harness.deck.offsetOf(cardB))
    }

    // --- tap must follow the card, not stick to whichever one composed first ---

    /**
     * Reproduces the real bug: [TriageScreen] renders the top card's
     * `SwipeCard` in one unkeyed slot across every swipe (no `key(top.id)`
     * around it, unlike the backing cards), so this drives the same slot
     * through two different card ids without a `key()` wrapper either.
     */
    @Test
    fun the_tap_detector_follows_the_card_when_it_changes() = runComposeUiTest {
        val tapped = mutableListOf<MediaId>()
        val harness = Harness()
        var currentCard by mutableStateOf(cardA)
        setContent { Deck(harness, currentCard, animated = true, onTap = { tapped += it }) }
        waitForIdle()

        // Engage the tap detector for cardA first — same as the real app,
        // where the card sits on screen (and can be interacted with) well
        // before it is ever swapped out.
        onNodeWithTag(CARD_TAG).performClick()
        waitForIdle()
        assertEquals(listOf(cardA), tapped)

        currentCard = cardB
        waitForIdle()

        onNodeWithTag(CARD_TAG).performClick()
        waitForIdle()

        // The tap detector must have restarted for the new card, not kept
        // running the gesture coroutine (and its captured `onTap(cardId)`) it
        // launched for cardA.
        assertEquals(listOf(cardA, cardB), tapped)
    }

    private companion object {
        const val CARD_TAG = "swipe-card-under-test"
        val CARD_WIDTH = 300.dp
        val CARD_HEIGHT = 500.dp
        const val DRAG_PX = 600f
        const val SHORT_DRAG_PX = 20f
        const val FRAME_MS = 16L
    }
}
