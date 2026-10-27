package com.codingpit.muviss.feature.nextpick.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.codingpit.muviss.feature.nextpick.domain.NextPickResultMode
import com.codingpit.muviss.feature.nextpick.ui.generated.resources.Res
import com.codingpit.muviss.feature.nextpick.ui.generated.resources.nextpick_deck_none
import com.codingpit.muviss.feature.nextpick.ui.generated.resources.nextpick_open_deck
import org.jetbrains.compose.resources.stringResource

/**
 * The same picks as Top Picks, plus the titles the person does not have yet opened as a Triage deck (EPIC 51,
 * #401). Swipe, verdicts and Snooze are Triage's, so this presentation only hands the titles over.
 *
 * Only titles the library does not hold can be swiped: Triage excludes what is in the library, and its undo removes
 * the library entry, so a backlog pick would vanish from the deck or be deleted by an undo. Offline the pool is the
 * backlog alone, which leaves nothing to swipe, and the screen says so.
 */
object DeckPresentation : PickPresentation {

    override val mode: NextPickResultMode = NextPickResultMode.DECK

    @Composable
    override fun Content(state: PickSessionUiState.Result, callbacks: PickResultCallbacks, modifier: Modifier) {
        val swipeable = state.picks.count { it.candidate.libraryStatus == null }
        PickList(state = state, callbacks = callbacks, modifier = modifier.fillMaxWidth()) {
            if (swipeable > 0) {
                Button(onClick = callbacks.onOpenDeck) { Text(stringResource(Res.string.nextpick_open_deck)) }
            } else {
                Text(stringResource(Res.string.nextpick_deck_none), style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
