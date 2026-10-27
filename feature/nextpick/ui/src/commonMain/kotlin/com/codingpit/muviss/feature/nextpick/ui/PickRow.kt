package com.codingpit.muviss.feature.nextpick.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.codingpit.muviss.core.designsystem.component.PosterImage
import com.codingpit.muviss.core.designsystem.component.PosterSize
import com.codingpit.muviss.feature.nextpick.domain.PickReason
import com.codingpit.muviss.feature.nextpick.domain.RankedPick
import com.codingpit.muviss.feature.nextpick.ui.generated.resources.Res
import com.codingpit.muviss.feature.nextpick.ui.generated.resources.nextpick_add
import com.codingpit.muviss.feature.nextpick.ui.generated.resources.nextpick_add_title
import com.codingpit.muviss.feature.nextpick.ui.generated.resources.nextpick_not_for_me
import com.codingpit.muviss.feature.nextpick.ui.generated.resources.nextpick_not_for_me_title
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
import com.codingpit.muviss.feature.nextpick.ui.generated.resources.nextpick_snooze_title
import com.codingpit.muviss.models.MediaId
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * One pick drawn as a row: poster, title, the reason, and the three actions (or the outcome once one was taken).
 * Shared by One Pick, Top Picks and Deck so that every mode carries the same actions per title (#401).
 */
@Composable
internal fun PickRow(
    pick: RankedPick,
    outcome: PickOutcome?,
    callbacks: PickResultCallbacks,
    modifier: Modifier = Modifier,
) {
    val title = pick.candidate.summary.title
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        PosterImage(url = pick.candidate.summary.posterUrl, title = title, size = PosterSize.Thumbnail, modifier = Modifier.width(72.dp))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(reasonText(pick.reason)), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            if (outcome == null) {
                PickActionRow(title = title, id = pick.candidate.id, callbacks = callbacks)
            } else {
                Text(stringResource(outcomeText(outcome)), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

/**
 * The three row actions. A FlowRow, not a Row: the Spanish labels are longer, and a fixed row overflowed on a
 * phone (the same reason as Triage's verdict row). Each button names its title for TalkBack, so five rows do not
 * read "Add" five times.
 */
@Composable
internal fun PickActionRow(title: String, id: MediaId, callbacks: PickResultCallbacks) {
    val addLabel = stringResource(Res.string.nextpick_add_title, title)
    val notForMeLabel = stringResource(Res.string.nextpick_not_for_me_title, title)
    val snoozeLabel = stringResource(Res.string.nextpick_snooze_title, title)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Button(
            onClick = { callbacks.onAdd(id) },
            modifier = Modifier.semantics { contentDescription = addLabel },
        ) { Text(stringResource(Res.string.nextpick_add)) }
        OutlinedButton(
            onClick = { callbacks.onNotForMe(id) },
            modifier = Modifier.semantics { contentDescription = notForMeLabel },
        ) { Text(stringResource(Res.string.nextpick_not_for_me)) }
        OutlinedButton(
            onClick = { callbacks.onSnooze(id) },
            modifier = Modifier.semantics { contentDescription = snoozeLabel },
        ) { Text(stringResource(Res.string.nextpick_snooze)) }
    }
}

internal fun reasonText(reason: PickReason): StringResource = when (reason) {
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

internal fun outcomeText(outcome: PickOutcome): StringResource = when (outcome) {
    PickOutcome.ADDED -> Res.string.nextpick_outcome_added
    PickOutcome.NOT_FOR_ME -> Res.string.nextpick_outcome_not_for_me
    PickOutcome.SNOOZED -> Res.string.nextpick_outcome_snoozed
}
