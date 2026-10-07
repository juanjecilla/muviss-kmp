@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.codingpit.muviss.feature.triage.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import com.codingpit.muviss.core.common.flags.SnoozePeriod
import com.codingpit.muviss.core.common.todayEpochDay
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import com.codingpit.muviss.feature.triage.api.TriageVerdict
import com.codingpit.muviss.models.MediaId
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
 * EPIC 42, ADR 0023. Snoozing has to be reachable on every target, which is
 * why the button — not the long press — is the affordance under test first:
 * a mouse has no long press and `runComposeUiTest` renders the desktop path.
 */
class TriageSnoozeScreenTest {

    // The screen collects with `collectAsStateWithLifecycle`, which hops to Main.
    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val filmA = movie("1")
    private val filmB = movie("2")
    private val opened = mutableListOf<MediaId>()

    /**
     * Snooze's two knobs live on the fakes rather than on [TriageHarness]'s
     * constructor, and have to be set before `viewModel()` because the view
     * model reads both with `take(1)` in its init.
     */
    private fun ComposeUiTest.showDeck(
        harness: TriageHarness,
        period: SnoozePeriod = SnoozePeriod.DEFAULT,
        hintSeen: Boolean = true,
    ): TriageViewModel {
        harness.flags.snoozePeriod.value = period
        harness.preferences.snoozeHint.value = hintSeen
        val viewModel = harness.viewModel()
        setContent {
            MuvissTheme {
                TriageScreen(viewModel = viewModel, onBack = {}, onOpenSkipped = {}, onOpenSnoozed = {}, onOpenDetail = { opened += it })
            }
        }
        waitForIdle()
        return viewModel
    }

    @Test
    fun the_button_snoozes_the_top_card_for_the_stored_period() = runComposeUiTest {
        val harness = TriageHarness(movies = listOf(filmA, filmB))
        showDeck(harness, period = SnoozePeriod.ONE_WEEK)

        onNodeWithTag(TRIAGE_SNOOZE_TAG).performClick()
        waitForIdle()

        val stored = harness.snoozes.snoozes.value.getValue(filmA.id)
        assertEquals(FakeClock().todayEpochDay() + 7, stored.dueAtEpochDay)
        // A Snooze is not a verdict (ADR 0023): nothing is decided, nothing saved.
        assertTrue(harness.repository.decisions.value.isEmpty())
        assertTrue(harness.collection.added.isEmpty())
    }

    @Test
    fun snoozing_advances_the_deck() = runComposeUiTest {
        val harness = TriageHarness(movies = listOf(filmA, filmB))
        val viewModel = showDeck(harness)

        onNodeWithTag(TRIAGE_SNOOZE_TAG).performClick()
        waitForIdle()

        assertEquals(filmB.id, viewModel.state.value.topCard?.id)
    }

    @Test
    fun a_long_press_snoozes_too() = runComposeUiTest {
        val harness = TriageHarness(movies = listOf(filmA, filmB))
        showDeck(harness)

        // Held in place past the long-press timeout, without crossing touch
        // slop — which is exactly what distinguishes it from a drag.
        onNodeWithTag(TRIAGE_CARD_TAG).performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            up()
        }
        waitForIdle()

        assertTrue(harness.snoozes.snoozes.value.containsKey(filmA.id))
    }

    @Test
    fun a_slow_drag_does_not_also_snooze() = runComposeUiTest {
        val harness = TriageHarness(movies = listOf(filmA, filmB))
        showDeck(harness)

        // The trap this guards: tap and drag are separate `pointerInput`
        // blocks, so a swipe slow enough to outlast the long-press timeout
        // could snooze the card as well as decide it. What saves it is that
        // `detectDragGestures` consumes each move once the gesture crosses
        // touch slop, and a consumed pointer cancels the tap detector — so the
        // drag has to be delivered as real intermediate events, the way a
        // finger produces them, not as one delayed jump.
        onNodeWithTag(TRIAGE_CARD_TAG).performTouchInput {
            down(center)
            repeat(20) { moveBy(Offset(-30f, 0f), delayMillis = 40) }
            up()
        }
        waitForIdle()

        // Over 800ms, comfortably past the long-press timeout.
        assertTrue(harness.snoozes.snoozes.value.isEmpty(), "a slow drag must never snooze")
        assertEquals(TriageVerdict.SKIP, harness.repository.decisions.value[filmA.id]?.verdict)
    }

    @Test
    fun the_s_key_snoozes() = runComposeUiTest {
        val harness = TriageHarness(movies = listOf(filmA, filmB))
        showDeck(harness)

        // The path a mouse-and-keyboard user has instead of a long press.
        onRoot().performKeyInput { pressKey(Key.S) }
        waitForIdle()

        assertTrue(harness.snoozes.snoozes.value.containsKey(filmA.id))
    }

    @Test
    fun undo_puts_a_snoozed_card_back_and_drops_the_snooze() = runComposeUiTest {
        val harness = TriageHarness(movies = listOf(filmA, filmB))
        val viewModel = showDeck(harness)

        onNodeWithTag(TRIAGE_SNOOZE_TAG).performClick()
        waitForIdle()
        viewModel.onUndo()
        waitForIdle()

        assertEquals(filmA.id, viewModel.state.value.topCard?.id)
        assertTrue(harness.snoozes.snoozes.value.isEmpty())
    }

    @Test
    fun ask_each_time_opens_the_dialog_instead_of_writing_anything() = runComposeUiTest {
        val harness = TriageHarness(movies = listOf(filmA, filmB))
        val viewModel = showDeck(harness, period = SnoozePeriod.ASK_EACH_TIME)

        onNodeWithTag(TRIAGE_SNOOZE_TAG).performClick()
        waitForIdle()

        onNodeWithTag(TRIAGE_SNOOZE_SHEET_TAG).assertIsDisplayed()
        // The card stays put: choosing a date for something you can no longer
        // see is the failure this avoids.
        assertEquals(filmA.id, viewModel.state.value.topCard?.id)
        assertTrue(harness.snoozes.snoozes.value.isEmpty())

        onNodeWithText("3 months").performClick()
        waitForIdle()

        assertEquals(FakeClock().todayEpochDay() + 90, harness.snoozes.snoozes.value.getValue(filmA.id).dueAtEpochDay)
    }

    @Test
    fun the_hint_shows_once_and_is_gone_after_the_first_snooze() = runComposeUiTest {
        val harness = TriageHarness(movies = listOf(filmA, filmB))
        showDeck(harness, hintSeen = false)

        onNodeWithText("Not sure? Snooze it and we'll ask again later.").assertIsDisplayed()

        onNodeWithTag(TRIAGE_SNOOZE_TAG).performClick()
        waitForIdle()

        // Using the gesture is the clearest possible proof the hint worked.
        assertTrue(harness.preferences.snoozeHint.value)
    }
}
