package com.codingpit.muviss.feature.triage.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codingpit.muviss.core.designsystem.component.EmptyState
import com.codingpit.muviss.core.designsystem.component.PosterImage
import com.codingpit.muviss.core.designsystem.component.PosterSize
import com.codingpit.muviss.core.designsystem.icon.MuvissIcons
import com.codingpit.muviss.core.designsystem.text.dateText
import com.codingpit.muviss.core.designsystem.theme.MuvissSpacing
import com.codingpit.muviss.feature.triage.ui.generated.resources.Res
import com.codingpit.muviss.feature.triage.ui.generated.resources.action_back
import com.codingpit.muviss.feature.triage.ui.generated.resources.comes_back
import com.codingpit.muviss.feature.triage.ui.generated.resources.nothing_snoozed
import com.codingpit.muviss.feature.triage.ui.generated.resources.nothing_snoozed_body
import com.codingpit.muviss.feature.triage.ui.generated.resources.snoozed
import com.codingpit.muviss.feature.triage.ui.generated.resources.unsnooze
import com.codingpit.muviss.models.MediaId
import org.jetbrains.compose.resources.stringResource

/**
 * Postponed titles, soonest to come back first — deliberately the opposite
 * ordering to the Skipped screen's newest-first, because what matters about a
 * Snooze is when it returns, not when it was made.
 */
@Composable
fun SnoozedScreen(
    viewModel: SnoozedViewModel,
    onBack: () -> Unit,
    onOpenDetail: (MediaId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(MuvissSpacing.s)) {
            IconButton(onClick = onBack) { Icon(MuvissIcons.Back, contentDescription = stringResource(Res.string.action_back)) }
            Text(stringResource(Res.string.snoozed), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = MuvissSpacing.xs))
        }

        when {
            state.loading -> CircularProgressIndicator(Modifier.padding(MuvissSpacing.xl))

            state.titles.isEmpty() -> EmptyState(
                icon = MuvissIcons.Snooze,
                title = stringResource(Res.string.nothing_snoozed),
                body = stringResource(Res.string.nothing_snoozed_body),
            )

            else -> LazyColumn(Modifier.testTag(SNOOZED_LIST_TAG)) {
                items(state.titles, key = { it.mediaId.toString() }) { snoozed ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        // The row opens the title; Unsnooze stays its own
                        // target, so a mis-tap reviews rather than un-snoozes.
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenDetail(snoozed.mediaId) }
                            .padding(horizontal = MuvissSpacing.l, vertical = MuvissSpacing.s),
                    ) {
                        PosterImage(
                            url = snoozed.posterUrl,
                            title = snoozed.title,
                            modifier = Modifier.width(POSTER_WIDTH).height(POSTER_HEIGHT),
                            size = PosterSize.Thumbnail,
                        )
                        Column(Modifier.weight(1f).padding(horizontal = MuvissSpacing.m)) {
                            Text(
                                text = snoozed.title,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = stringResource(Res.string.comes_back, dateText(snoozed.dueAtEpochDay)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { viewModel.onUnsnooze(snoozed.mediaId) }) { Text(stringResource(Res.string.unsnooze)) }
                    }
                }
            }
        }
    }
}

/** Identifies the snoozed list, for tests. */
const val SNOOZED_LIST_TAG = "snoozed-list"

private val POSTER_WIDTH = 48.dp
private val POSTER_HEIGHT = 72.dp
