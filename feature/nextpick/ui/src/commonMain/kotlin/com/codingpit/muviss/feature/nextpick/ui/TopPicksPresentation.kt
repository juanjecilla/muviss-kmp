package com.codingpit.muviss.feature.nextpick.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.codingpit.muviss.feature.nextpick.domain.NextPickResultMode

/**
 * Three to five picks, each a row with the same three actions (EPIC 51, #401). The list is the engine's rank
 * order; nothing is re-ranked for the screen.
 */
object TopPicksPresentation : PickPresentation {

    override val mode: NextPickResultMode = NextPickResultMode.TOP_PICKS

    @Composable
    override fun Content(state: PickSessionUiState.Result, callbacks: PickResultCallbacks, modifier: Modifier) {
        PickList(state = state, callbacks = callbacks, modifier = modifier)
    }
}

/** The shared list of rows, so Deck can show the same picks it hands to Triage. */
@Composable
internal fun PickList(
    state: PickSessionUiState.Result,
    callbacks: PickResultCallbacks,
    modifier: Modifier,
    header: @Composable () -> Unit = {},
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
    ) {
        item { header() }
        items(state.picks, key = { it.candidate.id.toString() }) { pick ->
            PickRow(pick = pick, outcome = state.outcomes[pick.candidate.id], callbacks = callbacks)
            Spacer(Modifier.height(16.dp))
        }
    }
}
