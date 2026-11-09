@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.codingpit.muviss.feature.nextpick.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import com.codingpit.muviss.feature.nextpick.domain.NextPickResultMode
import com.codingpit.muviss.feature.nextpick.domain.PickReason
import com.codingpit.muviss.feature.nextpick.domain.RankedPick
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.WatchStatus
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What each presentation draws from the same engine output. JVM-only: Compose renders through Skiko here.
 * The en-US locale is pinned for test JVMs, so the expected labels are the English strings.
 */
class PickPresentationTest {

    private fun ranked(id: String, library: WatchStatus? = null): RankedPick = RankedPick(
        candidate = candidate(id).copy(libraryStatus = library),
        score = 1.0,
        reason = PickReason.FitsWhatYouAsked,
    )

    private fun result(mode: NextPickResultMode, picks: List<RankedPick>, outcomes: Map<MediaId, PickOutcome> = emptyMap()) = PickSessionUiState.Result(mode = mode, picks = picks, offline = false, outcomes = outcomes)

    @Test
    fun top_picks_gives_every_row_its_own_named_add_button() = runComposeUiTest {
        // Given five picks, none acted on
        val added = mutableListOf<MediaId>()
        val picks = (1..5).map { ranked("$it") }
        setContent {
            MuvissTheme(darkTheme = false) {
                TopPicksPresentation.Content(
                    state = result(NextPickResultMode.TOP_PICKS, picks),
                    callbacks = callbacks(onAdd = { added += it }),
                    modifier = Modifier,
                )
            }
        }

        // When the third row's Add is pressed
        onNodeWithContentDescription("Add Title 3").performClick()

        // Then the Add is for that title, and it is the only write
        assertEquals(listOf(MediaId.tmdbMovie("3")), added)
    }

    @Test
    fun one_pick_hides_the_actions_once_a_title_has_an_outcome() = runComposeUiTest {
        // Given a one-pick result the person has already added
        val pick = ranked("1")
        setContent {
            MuvissTheme(darkTheme = false) {
                OnePickPresentation.Content(
                    state = result(NextPickResultMode.ONE_PICK, listOf(pick), mapOf(pick.candidate.id to PickOutcome.ADDED)),
                    callbacks = callbacks(),
                    modifier = Modifier,
                )
            }
        }

        // Then its Add button is gone rather than offered a second time
        onNodeWithContentDescription("Add Title 1").assertDoesNotExist()
    }

    @Test
    fun deck_offers_the_swipe_when_the_library_does_not_hold_a_pick() = runComposeUiTest {
        // Given a list with one title the library does not hold
        var opened = 0
        setContent {
            MuvissTheme(darkTheme = false) {
                DeckPresentation.Content(
                    state = result(NextPickResultMode.DECK, listOf(ranked("1"))),
                    callbacks = callbacks(onOpenDeck = { opened++ }),
                    modifier = Modifier,
                )
            }
        }

        // When the person opens it as a deck
        onNodeWithText("Swipe through the new ones").performClick()

        // Then the handoff happens once
        assertEquals(1, opened)
    }

    @Test
    fun deck_says_why_there_is_nothing_to_swipe_when_every_pick_is_already_in_the_library() = runComposeUiTest {
        // Given picks that are all backlog titles, which Triage cannot take
        setContent {
            MuvissTheme(darkTheme = false) {
                DeckPresentation.Content(
                    state = result(NextPickResultMode.DECK, listOf(ranked("1", WatchStatus.NOT_STARTED))),
                    callbacks = callbacks(),
                    modifier = Modifier,
                )
            }
        }

        // Then there is no swipe button, and the screen says so instead
        onNodeWithText("Swipe through the new ones").assertDoesNotExist()
        onNodeWithText("Only titles you do not have yet", substring = true).assertExists()
    }
}

private fun callbacks(
    onAdd: (MediaId) -> Unit = {},
    onOpenDeck: () -> Unit = {},
) = PickResultCallbacks(
    onAdd = onAdd,
    onNotForMe = {},
    onSnooze = {},
    onAnother = {},
    onOpenDeck = onOpenDeck,
)
