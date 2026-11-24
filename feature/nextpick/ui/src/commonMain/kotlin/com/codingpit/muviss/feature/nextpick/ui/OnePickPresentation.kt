package com.codingpit.muviss.feature.nextpick.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.codingpit.muviss.core.designsystem.component.PosterImage
import com.codingpit.muviss.core.designsystem.component.PosterSize
import com.codingpit.muviss.feature.nextpick.domain.NextPickResultMode
import com.codingpit.muviss.feature.nextpick.ui.generated.resources.Res
import com.codingpit.muviss.feature.nextpick.ui.generated.resources.nextpick_another
import com.codingpit.muviss.feature.nextpick.ui.generated.resources.nextpick_one_pick_label
import org.jetbrains.compose.resources.stringResource

/**
 * One title, "another", and the sentence that says why (EPIC 51, ADR 0032). The default presentation. It draws
 * the first pick of the engine's list; "another" steps to the next.
 */
object OnePickPresentation : PickPresentation {

    private const val POSTER_WIDTH_FRACTION = 0.7f
    private const val POSTER_ASPECT = 2f / 3f

    override val mode: NextPickResultMode = NextPickResultMode.ONE_PICK

    @Composable
    override fun Content(state: PickSessionUiState.Result, callbacks: PickResultCallbacks, modifier: Modifier) {
        val pick = state.picks.first()
        val outcome = state.outcomes[pick.candidate.id]
        // Scrolls: on a tall, narrow phone the poster, the reason and both actions together are taller than the
        // screen, and without it the Add and Another buttons could not be reached (#983).
        Column(modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(Res.string.nextpick_one_pick_label), style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(12.dp))
            // PosterImage fills whatever it is given, so it needs a size of its own: left alone it takes the whole
            // height under the label. A poster ratio at 70% of the width keeps the actions near the fold.
            PosterImage(
                url = pick.candidate.summary.posterUrl,
                title = pick.candidate.summary.title,
                modifier = Modifier.fillMaxWidth(POSTER_WIDTH_FRACTION).aspectRatio(POSTER_ASPECT),
                size = PosterSize.Detail,
            )
            Spacer(Modifier.height(12.dp))
            Text(pick.candidate.summary.title, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(4.dp))
            Text(stringResource(reasonText(pick.reason)), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                if (outcome == null) {
                    PickActionRow(
                        title = pick.candidate.summary.title,
                        id = pick.candidate.id,
                        callbacks = callbacks,
                        failed = pick.candidate.id in state.failed,
                    )
                } else {
                    Text(stringResource(outcomeText(outcome)), style = MaterialTheme.typography.bodyLarge)
                }
            }
            TextButton(onClick = callbacks.onAnother) { Text(stringResource(Res.string.nextpick_another)) }
        }
    }
}
