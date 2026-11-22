package com.codingpit.muviss.feature.nextpick.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.codingpit.muviss.feature.nextpick.domain.NextPickResultMode
import com.codingpit.muviss.models.MediaId

/**
 * How a Pick Session's result is drawn (EPIC 51, ADR 0032). The engine's output is the same whichever
 * presentation is chosen, so the session and the engine never change for a new format. Presentations draw;
 * they never write. Every action goes back through [PickResultCallbacks], which the ViewModel routes to the
 * existing Add, Skip and Snooze writes.
 */
interface PickPresentation {
    val mode: NextPickResultMode

    @Composable
    fun Content(state: PickSessionUiState.Result, callbacks: PickResultCallbacks, modifier: Modifier)
}

/**
 * The actions a presentation can raise. Each is a button, never a gesture alone (a mouse has no long press).
 * The row actions name the title they are for, because a list shows several at once.
 */
data class PickResultCallbacks(
    val onAdd: (MediaId) -> Unit,
    val onNotForMe: (MediaId) -> Unit,
    val onSnooze: (MediaId) -> Unit,
    val onAnother: () -> Unit,
    val onOpenDeck: () -> Unit,
)

/** Maps the chosen mode to its presentation. A new mode is one line here and its own class. */
fun presentationFor(mode: NextPickResultMode): PickPresentation = when (mode) {
    NextPickResultMode.ONE_PICK -> OnePickPresentation
    NextPickResultMode.TOP_PICKS -> TopPicksPresentation
    NextPickResultMode.DECK -> DeckPresentation
}
