@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.triage.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import com.codingpit.muviss.core.common.flags.TriageControlScheme
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import com.codingpit.muviss.feature.triage.api.TriageVerdict
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Drives the real [TriageScreen] over fake use cases.
 *
 * These cover what the ViewModel tests cannot: that a drag in a given
 * direction reaches the verdict it is supposed to, that the button row is a
 * genuine parallel path, and that the affordances a screen-reader user relies
 * on are actually attached to the controls.
 */
class TriageScreenTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val filmA = movie("1")
    private val filmB = movie("2")
    private val showA = show("10")

    /** Past the 120.dp commit threshold at any sane test density. */
    private val commitDistance = 600f

    /** Every id a card tap sent to the detail screen. */
    private val opened = mutableListOf<MediaId>()

    private fun ComposeUiTest.showDeck(harness: TriageHarness): TriageViewModel {
        val viewModel = harness.viewModel()
        setContent {
            MuvissTheme {
                TriageScreen(viewModel = viewModel, onBack = {}, onOpenSkipped = {}, onOpenDetail = { opened += it })
            }
        }
        waitForIdle()
        return viewModel
    }

    private fun ComposeUiTest.dragCard(by: Offset) {
        onNodeWithTag(TRIAGE_CARD_TAG).performTouchInput {
            down(center)
            moveBy(by)
            up()
        }
        waitForIdle()
    }

    // --- gestures ---

    @Test
    fun dragging_left_skips() = runComposeUiTest {
        val harness = TriageHarness(movies = listOf(filmA, filmB))
        showDeck(harness)

        dragCard(Offset(-commitDistance, 0f))

        assertEquals(TriageVerdict.SKIP, harness.repository.decisions.value[filmA.id]?.verdict)
    }

    @Test
    fun dragging_right_saves_for_later() = runComposeUiTest {
        val harness = TriageHarness(movies = listOf(filmA, filmB))
        showDeck(harness)

        dragCard(Offset(commitDistance, 0f))

        assertEquals(TriageVerdict.LATER, harness.repository.decisions.value[filmA.id]?.verdict)
    }

    @Test
    fun dragging_up_marks_caught_up() = runComposeUiTest {
        val harness = TriageHarness(movies = listOf(filmA, filmB))
        showDeck(harness)

        dragCard(Offset(0f, -commitDistance))

        assertEquals(TriageVerdict.CAUGHT_UP, harness.repository.decisions.value[filmA.id]?.verdict)
    }

    @Test
    fun dragging_down_marks_watching_under_the_four_way_scheme() = runComposeUiTest {
        val harness = TriageHarness(tv = listOf(show("1"), showA), scheme = TriageControlScheme.FOUR_WAY)
        val first = show("1")
        val viewModel = harness.viewModel()
        setContent { MuvissTheme { TriageScreen(viewModel, onBack = {}, onOpenSkipped = {}, onOpenDetail = { opened += it }) } }
        waitForIdle()

        onNodeWithTag(TRIAGE_CARD_TAG).performTouchInput {
            down(center)
            moveBy(Offset(0f, commitDistance))
            up()
        }
        waitForIdle()

        assertEquals(TriageVerdict.WATCHING, harness.repository.decisions.value[first.id]?.verdict)
    }

    @Test
    fun dragging_down_is_inert_under_the_three_way_scheme() = runComposeUiTest {
        val first = show("1")
        val harness = TriageHarness(tv = listOf(first, showA), scheme = TriageControlScheme.THREE_WAY)
        val viewModel = harness.viewModel()
        setContent { MuvissTheme { TriageScreen(viewModel, onBack = {}, onOpenSkipped = {}, onOpenDetail = { opened += it }) } }
        waitForIdle()

        onNodeWithTag(TRIAGE_CARD_TAG).performTouchInput {
            down(center)
            moveBy(Offset(0f, commitDistance))
            up()
        }
        waitForIdle()

        // The least discoverable direction must not mis-fire the verdict that
        // writes ticks — under THREE_WAY it is reached only by its button.
        assertEquals(emptyMap(), harness.repository.decisions.value)
        assertEquals(first.id, viewModel.state.value.topCard?.id)
    }

    @Test
    fun a_drag_short_of_the_threshold_snaps_back_and_decides_nothing() = runComposeUiTest {
        val harness = TriageHarness(movies = listOf(filmA, filmB))
        val viewModel = showDeck(harness)

        dragCard(Offset(-20f, 0f))

        assertEquals(emptyMap(), harness.repository.decisions.value)
        assertEquals(filmA.id, viewModel.state.value.topCard?.id)
    }

    @Test
    fun the_directional_hint_names_the_pending_verdict_while_dragging_and_clears_on_release() = runComposeUiTest {
        val harness = TriageHarness(movies = listOf(filmA, filmB))
        showDeck(harness)

        onNodeWithTag(TRIAGE_CARD_TAG).performTouchInput {
            down(center)
            moveBy(Offset(-commitDistance, 0f))
        }
        waitForIdle()
        onNodeWithContentDescription("Release to Skip").assertIsDisplayed()

        onNodeWithTag(TRIAGE_CARD_TAG).performTouchInput { up() }
        waitForIdle()
        assertEquals(0, onAllNodesWithContentDescriptionCount("Release to Skip"))
    }

    // --- buttons ---

    @Test
    fun every_verdict_has_a_button_that_commits_it() = runComposeUiTest {
        val harness = TriageHarness(tv = listOf(showA, show("11")))
        showDeck(harness)

        onNodeWithText("Caught up").performClick()
        waitForIdle()

        assertEquals(TriageVerdict.CAUGHT_UP, harness.repository.decisions.value[showA.id]?.verdict)
    }

    @Test
    fun the_watching_button_commits_even_under_the_three_way_scheme() = runComposeUiTest {
        val harness = TriageHarness(tv = listOf(showA, show("11")), scheme = TriageControlScheme.THREE_WAY)
        showDeck(harness)

        onNodeWithText("Watching").performClick()
        waitForIdle()

        assertEquals(TriageVerdict.WATCHING, harness.repository.decisions.value[showA.id]?.verdict)
    }

    @Test
    fun a_movie_card_offers_no_watching_affordance_at_all() = runComposeUiTest {
        val harness = TriageHarness(movies = listOf(filmA, filmB))
        showDeck(harness)

        assertEquals(0, onAllNodesWithTextCount("Watching"))
        onNodeWithText("Skip").assertIsDisplayed()
        onNodeWithText("Later").assertIsDisplayed()
        // A film has one element, so "caught up" says nothing about it. The
        // verdict is the same CAUGHT_UP; only the word changes.
        onNodeWithText("Watched").assertIsDisplayed()
        assertEquals(0, onAllNodesWithTextCount("Caught up"))
    }

    @Test
    fun a_tv_card_still_says_caught_up() = runComposeUiTest {
        val harness = TriageHarness(tv = listOf(showA))
        showDeck(harness)

        onNodeWithText("Caught up").assertIsDisplayed()
        assertEquals(0, onAllNodesWithTextCount("Watched"))
    }

    @Test
    fun a_movie_button_reads_aloud_what_it_actually_does() = runComposeUiTest {
        val harness = TriageHarness(movies = listOf(filmA))
        showDeck(harness)

        // "every aired episode ticked" is nonsense for a film, and this string
        // is the button's contentDescription.
        onNodeWithContentDescription("Watched. Saved and marked as watched.").assertIsDisplayed()
    }

    // --- opening a title ---

    @Test
    fun tapping_the_card_opens_the_title_rather_than_deciding_it() = runComposeUiTest {
        val harness = TriageHarness(tv = listOf(showA, show("11")))
        showDeck(harness)

        onNodeWithTag(TRIAGE_CARD_TAG).performClick()
        waitForIdle()

        assertEquals(listOf(showA.id), opened)
        // A tap is not a verdict: nothing was decided, and the card stayed.
        assertTrue(harness.repository.decisions.value.isEmpty())
    }

    @Test
    fun a_drag_past_the_threshold_is_not_also_a_tap() = runComposeUiTest {
        val harness = TriageHarness(tv = listOf(showA, show("11")))
        showDeck(harness)

        dragCard(Offset(-commitDistance, 0f))

        assertEquals(TriageVerdict.SKIP, harness.repository.decisions.value[showA.id]?.verdict)
        assertEquals(emptyList(), opened)
    }

    @Test
    fun tapping_the_new_top_card_after_a_swipe_opens_it_not_the_skipped_one() = runComposeUiTest {
        val harness = TriageHarness(movies = listOf(filmA, filmB))
        showDeck(harness)

        dragCard(Offset(-commitDistance, 0f)) // skip filmA
        onNodeWithTag(TRIAGE_CARD_TAG).performClick()
        waitForIdle()

        assertEquals(listOf(filmB.id), opened)
    }

    @Test
    fun every_verdict_button_carries_a_description_of_what_it_does() = runComposeUiTest {
        val harness = TriageHarness(tv = listOf(showA))
        showDeck(harness)

        // The buttons are the primary path, so they must read properly aloud —
        // "Caught up" alone does not say that it ticks every aired episode.
        TriageVerdict.entries.forEach { verdict ->
            val label = when (verdict) {
                TriageVerdict.SKIP -> "Skip"
                TriageVerdict.LATER -> "Later"
                TriageVerdict.WATCHING -> "Watching"
                TriageVerdict.CAUGHT_UP -> "Caught up"
            }
            onNodeWithContentDescription("$label. ${explanationFor(verdict, MediaType.TV)}").assertIsDisplayed()
        }
    }

    // --- keyboard ---

    @Test
    fun the_arrow_keys_commit_the_same_verdicts_as_the_drags() = runComposeUiTest {
        val harness = TriageHarness(tv = listOf(showA, show("11")))
        showDeck(harness)

        onRoot().performKeyInput { pressKey(Key.DirectionUp) }
        waitForIdle()

        // The primary input on desktop and web, and the accessible path
        // everywhere — it must reach exactly the same rules, not a subset.
        assertEquals(TriageVerdict.CAUGHT_UP, harness.repository.decisions.value[showA.id]?.verdict)
    }

    @Test
    fun the_down_arrow_is_inert_under_the_three_way_scheme_too() = runComposeUiTest {
        val first = show("1")
        val harness = TriageHarness(tv = listOf(first, showA), scheme = TriageControlScheme.THREE_WAY)
        val viewModel = showDeck(harness)

        onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        waitForIdle()

        assertEquals(emptyMap(), harness.repository.decisions.value)
        assertEquals(first.id, viewModel.state.value.topCard?.id)
    }

    @Test
    fun the_down_arrow_never_applies_watching_to_a_movie() = runComposeUiTest {
        val harness = TriageHarness(movies = listOf(filmA, filmB))
        val viewModel = showDeck(harness)

        onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        waitForIdle()

        assertEquals(emptyMap(), harness.repository.decisions.value)
        assertEquals(filmA.id, viewModel.state.value.topCard?.id)
    }

    @Test
    fun pressing_Z_undoes_the_last_verdict() = runComposeUiTest {
        val harness = TriageHarness(movies = listOf(filmA, filmB))
        val viewModel = showDeck(harness)
        onRoot().performKeyInput { pressKey(Key.DirectionLeft) }
        waitForIdle()

        onRoot().performKeyInput { pressKey(Key.Z) }
        waitForIdle()

        assertEquals(filmA.id, viewModel.state.value.topCard?.id)
        assertEquals(emptyMap(), harness.repository.decisions.value)
    }

    // --- undo ---

    @Test
    fun the_undo_snackbar_names_the_verdict_and_puts_the_card_back() = runComposeUiTest {
        val harness = TriageHarness(movies = listOf(filmA, filmB))
        val viewModel = showDeck(harness)

        dragCard(Offset(-commitDistance, 0f))

        onNodeWithText("Skip \u00b7 ${filmA.title}").assertIsDisplayed()
        onNodeWithText("Undo").performClick()
        waitForIdle()

        assertEquals(filmA.id, viewModel.state.value.topCard?.id)
        assertEquals(emptyMap(), harness.repository.decisions.value)
        // A skip saved nothing, so undoing one has nothing to unwind beyond
        // the decision itself.
        assertEquals(emptyList(), harness.collection.removed)
    }

    @Test
    fun the_undo_snackbar_uses_the_decided_card_own_wording() = runComposeUiTest {
        val harness = TriageHarness(movies = listOf(filmA, filmB))
        showDeck(harness)

        dragCard(Offset(0f, -commitDistance))

        // The card that was decided is a film, so the verdict it names is the
        // one the button offered — "Watched", not "Caught up".
        onNodeWithText("Watched · ${filmA.title}").assertIsDisplayed()
    }

    @Test
    fun the_snackbar_never_covers_the_verdict_buttons() = runComposeUiTest {
        val harness = TriageHarness(tv = listOf(showA, show("11")))
        showDeck(harness)

        dragCard(Offset(-commitDistance, 0f))

        // The buttons are the primary, always-available way to decide, so a
        // snackbar sitting on top of them takes that away for as long as it is
        // up. It sits above the row rather than in Scaffold's bottom slot.
        val snackbarBottom = onNodeWithText("Undo").getUnclippedBoundsInRoot().bottom
        val buttonsTop = onNodeWithText("Skip").getUnclippedBoundsInRoot().top
        assertTrue(
            snackbarBottom <= buttonsTop,
            "snackbar bottom $snackbarBottom overlaps the button row at $buttonsTop",
        )
    }

    // --- deck behaviour ---

    @Test
    fun the_next_card_is_shown_immediately_after_a_swipe() = runComposeUiTest {
        val harness = TriageHarness(movies = listOf(filmA, filmB))
        harness.details.gate = kotlinx.coroutines.CompletableDeferred()
        showDeck(harness)

        dragCard(Offset(commitDistance, 0f))

        // Still saving in the background; the deck has already moved on.
        assertTrue(onAllNodesWithTextCount(filmB.title) > 0)
        assertEquals(emptyList(), harness.collection.added)
    }

    @Test
    fun the_tutorial_appears_on_a_fresh_device_and_explains_every_verdict() = runComposeUiTest {
        val harness = TriageHarness(tv = listOf(showA), tutorialSeen = false)
        showDeck(harness)

        onNodeWithText("How triage works").assertIsDisplayed()
        onNodeWithText(explanationForTutorial(TriageVerdict.CAUGHT_UP)).assertIsDisplayed()

        onNodeWithText("Got it").performClick()
        waitForIdle()

        assertEquals(0, onAllNodesWithTextCount("How triage works"))
        assertTrue(harness.preferences.tutorialSeen.value)
    }

    @Test
    fun the_tutorial_can_be_reopened_from_the_header() = runComposeUiTest {
        val harness = TriageHarness(tv = listOf(showA))
        showDeck(harness)

        onNodeWithText("How it works").performClick()
        waitForIdle()

        onNodeWithText("How triage works").assertIsDisplayed()
    }

    @Test
    fun an_empty_deck_offers_a_way_on_rather_than_a_blank_screen() = runComposeUiTest {
        showDeck(TriageHarness())

        onNodeWithText("All caught up").assertIsDisplayed()
        onNodeWithText("Review skipped").assertIsDisplayed()
    }

    @Test
    fun a_filtered_empty_deck_offers_to_clear_the_filter() = runComposeUiTest {
        val viewModel = showDeck(TriageHarness(movies = listOf(filmA)))

        onNodeWithText("TV").performClick()
        waitForIdle()

        onNodeWithText("Nothing left here").assertIsDisplayed()
        onNodeWithText("Clear filters").performClick()
        waitForIdle()
        assertNull(viewModel.state.value.filter.type)
    }

    private fun ComposeUiTest.onAllNodesWithTextCount(text: String): Int = onAllNodes(hasText(text)).fetchSemanticsNodes().size

    private fun ComposeUiTest.onAllNodesWithContentDescriptionCount(description: String): Int = onAllNodes(androidx.compose.ui.test.hasContentDescription(description)).fetchSemanticsNodes().size
}
