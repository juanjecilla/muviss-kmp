package com.codingpit.muviss.feature.nextpick.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.codingpit.muviss.core.designsystem.component.PosterImage
import com.codingpit.muviss.core.designsystem.component.PosterSize
import com.codingpit.muviss.feature.nextpick.domain.NextPickResultMode
import com.codingpit.muviss.feature.nextpick.domain.PickReason
import com.codingpit.muviss.feature.nextpick.ui.generated.resources.Res
import com.codingpit.muviss.feature.nextpick.ui.generated.resources.nextpick_add
import com.codingpit.muviss.feature.nextpick.ui.generated.resources.nextpick_another
import com.codingpit.muviss.feature.nextpick.ui.generated.resources.nextpick_not_for_me
import com.codingpit.muviss.feature.nextpick.ui.generated.resources.nextpick_one_pick_label
import com.codingpit.muviss.feature.nextpick.ui.generated.resources.nextpick_outcome_added
import com.codingpit.muviss.feature.nextpick.ui.generated.resources.nextpick_outcome_not_for_me
import com.codingpit.muviss.feature.nextpick.ui.generated.resources.nextpick_outcome_snoozed
import com.codingpit.muviss.feature.nextpick.ui.generated.resources.nextpick_reason_classic
import com.codingpit.muviss.feature.nextpick.ui.generated.resources.nextpick_reason_favorite
import com.codingpit.muviss.feature.nextpick.ui.generated.resources.nextpick_reason_fits
import com.codingpit.muviss.feature.nextpick.ui.generated.resources.nextpick_reason_highly_rated
import com.codingpit.muviss.feature.nextpick.ui.generated.resources.nextpick_reason_language
import com.codingpit.muviss.feature.nextpick.ui.generated.resources.nextpick_reason_like
import com.codingpit.muviss.feature.nextpick.ui.generated.resources.nextpick_reason_long_in_list
import com.codingpit.muviss.feature.nextpick.ui.generated.resources.nextpick_reason_mood
import com.codingpit.muviss.feature.nextpick.ui.generated.resources.nextpick_reason_rated
import com.codingpit.muviss.feature.nextpick.ui.generated.resources.nextpick_reason_recent
import com.codingpit.muviss.feature.nextpick.ui.generated.resources.nextpick_reason_time
import com.codingpit.muviss.feature.nextpick.ui.generated.resources.nextpick_reason_unlike
import com.codingpit.muviss.feature.nextpick.ui.generated.resources.nextpick_snooze
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * One title, "another", and the sentence that says why (EPIC 51, ADR 0032). The default presentation.
 */
object OnePickPresentation : PickPresentation {

    override val mode: NextPickResultMode = NextPickResultMode.ONE_PICK

    @Composable
    override fun Content(state: PickSessionUiState.Result, callbacks: PickResultCallbacks, modifier: Modifier) {
        val pick = state.pick
        Column(modifier = modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(Res.string.nextpick_one_pick_label), style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(12.dp))
            PosterImage(url = pick.candidate.summary.posterUrl, title = pick.candidate.summary.title, size = PosterSize.Detail)
            Spacer(Modifier.height(12.dp))
            Text(pick.candidate.summary.title, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(4.dp))
            Text(stringResource(reasonText(pick.reason)), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(16.dp))
            state.outcome?.let { Text(stringResource(outcomeText(it)), style = MaterialTheme.typography.bodyLarge) }
            if (state.outcome == null) ActionRow(callbacks)
            TextButton(onClick = callbacks.onAnother) { Text(stringResource(Res.string.nextpick_another)) }
        }
    }

    @Composable
    private fun ActionRow(callbacks: PickResultCallbacks) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = callbacks.onAdd) { Text(stringResource(Res.string.nextpick_add)) }
            OutlinedButton(onClick = callbacks.onNotForMe) { Text(stringResource(Res.string.nextpick_not_for_me)) }
            OutlinedButton(onClick = callbacks.onSnooze) { Text(stringResource(Res.string.nextpick_snooze)) }
        }
    }

    private fun reasonText(reason: PickReason): StringResource = when (reason) {
        is PickReason.FitsMood -> Res.string.nextpick_reason_mood
        is PickReason.LikeAnchor -> Res.string.nextpick_reason_like
        is PickReason.UnlikeAnchor -> Res.string.nextpick_reason_unlike
        is PickReason.YouRateTheseGenresHigh -> Res.string.nextpick_reason_rated
        PickReason.YourFavorite -> Res.string.nextpick_reason_favorite
        PickReason.LongInYourList -> Res.string.nextpick_reason_long_in_list
        is PickReason.HighlyRated -> Res.string.nextpick_reason_highly_rated
        is PickReason.FitsTime -> Res.string.nextpick_reason_time
        PickReason.RecentRelease -> Res.string.nextpick_reason_recent
        PickReason.Classic -> Res.string.nextpick_reason_classic
        is PickReason.InYourLanguage -> Res.string.nextpick_reason_language
        PickReason.FitsWhatYouAsked -> Res.string.nextpick_reason_fits
    }

    private fun outcomeText(outcome: PickOutcome): StringResource = when (outcome) {
        PickOutcome.ADDED -> Res.string.nextpick_outcome_added
        PickOutcome.NOT_FOR_ME -> Res.string.nextpick_outcome_not_for_me
        PickOutcome.SNOOZED -> Res.string.nextpick_outcome_snoozed
    }
}
