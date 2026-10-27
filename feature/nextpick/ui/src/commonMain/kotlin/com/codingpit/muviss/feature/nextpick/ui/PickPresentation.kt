package com.codingpit.muviss.feature.nextpick.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.codingpit.muviss.feature.nextpick.domain.NextPickResultMode

/**
 * How a Pick Session's result is drawn (EPIC 51, ADR 0032). The engine's output is the same whichever
 * presentation is chosen, so #401 adds Top Picks and Deck by implementing this, not by touching the
 * session or the engine. Presentations draw; they never write. Every action goes back through
 * [PickResultCallbacks], which the ViewModel routes to the existing Add, Skip and Snooze writes.
 */
interface PickPresentation {
    val mode: NextPickResultMode

    @Composable
    fun Content(state: PickSessionUiState.Result, callbacks: PickResultCallbacks, modifier: Modifier)
}

/** The actions a presentation can raise. Each is a button, never a gesture alone (a mouse has no long press). */
data class PickResultCallbacks(
    val onAdd: () -> Unit,
    val onNotForMe: () -> Unit,
    val onSnooze: () -> Unit,
    val onAnother: () -> Unit,
)

/** Maps the chosen mode to its presentation. A new mode is one line here and its own class. */
fun presentationFor(mode: NextPickResultMode): PickPresentation = when (mode) {
    NextPickResultMode.ONE_PICK -> OnePickPresentation
}
