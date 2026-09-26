package com.codingpit.muviss.feature.cowatch.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codingpit.muviss.core.designsystem.component.EmptyState
import com.codingpit.muviss.core.designsystem.component.PosterImage
import com.codingpit.muviss.core.designsystem.component.PosterSize
import com.codingpit.muviss.core.designsystem.icon.MuvissIcons
import com.codingpit.muviss.core.designsystem.theme.MuvissSpacing
import com.codingpit.muviss.feature.cowatch.api.ShortlistItem
import com.codingpit.muviss.feature.cowatch.api.ShortlistReason
import com.codingpit.muviss.models.MediaId

/**
 * What two people could watch together.
 *
 * Deliberately a list, not a deck: swiping suits deciding alone, and two people
 * on a sofa want to see the options at once. It also reads as clearly distinct
 * from WatchNext, which answers a different question on the neighbouring screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShortlistScreen(
    companionUserId: String,
    viewModel: ShortlistViewModel,
    onBack: () -> Unit,
    onOpenDetail: (MediaId) -> Unit,
) {
    LaunchedEffect(companionUserId) { viewModel.start(companionUserId) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Watch together") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            StalenessNote(state.companionPoolPublishedAtEpochMs)
            when {
                state.loading -> Unit

                state.items.isEmpty() -> EmptyState(
                    icon = MuvissIcons.WatchNext,
                    title = "Nothing in common yet",
                    body = "A title shows up here when you have both saved it. " +
                        "Add a few things you mean to watch, and ask them to do the same.",
                )

                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(state.items, key = { it.mediaId.toString() }) { item ->
                        ShortlistRow(item, onClick = { onOpenDetail(item.mediaId) })
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

/**
 * Says how old their half of this is, rather than implying it is live.
 *
 * Only their device can publish their pool, so this cannot be fixed by syncing
 * harder here — the honest thing is to show it (ADR 0022).
 */
@Composable
private fun StalenessNote(publishedAtEpochMs: Long?) {
    val text = if (publishedAtEpochMs == null) {
        "Their list hasn't arrived yet — it reaches you once they open Muviss."
    } else {
        "Their list is as of the last time they opened Muviss."
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(MuvissSpacing.l),
    )
}

@Composable
private fun ShortlistRow(item: ShortlistItem, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(MuvissSpacing.l),
        horizontalArrangement = Arrangement.spacedBy(MuvissSpacing.l),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PosterImage(url = item.posterUrl, title = item.title, size = PosterSize.Thumbnail)
        Column(Modifier.fillMaxWidth()) {
            Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                text = explain(item),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onClick) { Text("Details") }
        }
    }
}

/**
 * One line saying why a title ranks where it does.
 *
 * The ranking is explicit rules rather than a score precisely so this sentence
 * can exist: a position nobody can explain is one nobody trusts.
 */
private fun explain(item: ShortlistItem): String {
    val parts = buildList {
        if (ShortlistReason.BOTH_PINNED in item.reasons) add("you both picked it")
        if (ShortlistReason.NEITHER_STARTED in item.reasons) add("neither of you has started it")
        if (ShortlistReason.REVISIT in item.reasons) add("one of you has seen it and would again")
        item.runtimeMinutes?.let { add("$it min") }
    }
    return parts.joinToString(" · ")
}
