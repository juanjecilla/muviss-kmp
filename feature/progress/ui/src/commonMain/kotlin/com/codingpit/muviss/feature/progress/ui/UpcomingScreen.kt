package com.codingpit.muviss.feature.progress.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codingpit.muviss.core.designsystem.component.EmptyState
import com.codingpit.muviss.core.designsystem.component.ErrorState
import com.codingpit.muviss.core.designsystem.component.PosterImage
import com.codingpit.muviss.core.designsystem.component.PosterSize
import com.codingpit.muviss.core.designsystem.icon.MuvissIcons
import com.codingpit.muviss.core.designsystem.theme.MuvissSpacing
import com.codingpit.muviss.feature.progress.domain.UpcomingBucket
import com.codingpit.muviss.models.MediaId

/**
 * The Progress tab's Upcoming segment (EPIC 14): an agenda of future-dated
 * episodes across the library's TV shows, grouped into Today / This week /
 * Later. Mirrors [WatchNextScreen]'s loading/error/empty/list shape.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpcomingScreen(
    viewModel: UpcomingViewModel,
    onOpenDetail: (MediaId) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    PullToRefreshBox(
        isRefreshing = state.refreshing,
        onRefresh = viewModel::refresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            when {
                state.loading -> CircularProgressIndicator(Modifier.padding(top = MuvissSpacing.xxl))
                state.error != null -> ErrorState(state.error!!, onRetry = viewModel::refresh)
                state.groups.isEmpty() -> UpcomingEmptyState(hasLibraryEntries = state.hasLibraryEntries)
                else -> UpcomingList(state.groups, onOpenDetail)
            }
        }
    }
}

@Composable
private fun UpcomingEmptyState(hasLibraryEntries: Boolean) {
    if (hasLibraryEntries) {
        EmptyState(
            icon = MuvissIcons.Calendar,
            title = "Nothing upcoming",
            body = "Every saved show is either finished or has no scheduled episodes yet.",
        )
    } else {
        EmptyState(
            icon = MuvissIcons.Calendar,
            title = "Nothing here yet",
            body = "Save a show to your Library to see its upcoming episodes.",
        )
    }
}

@Composable
private fun UpcomingList(
    groups: List<UpcomingUiGroup>,
    onOpenDetail: (MediaId) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(MuvissSpacing.s),
        contentPadding = PaddingValues(
            start = MuvissSpacing.m,
            end = MuvissSpacing.m,
            top = MuvissSpacing.m,
            bottom = MuvissSpacing.bottomContent,
        ),
    ) {
        groups.forEach { group ->
            item(key = "header-${group.bucket}") {
                Text(
                    group.bucket.label().uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = MuvissSpacing.s, bottom = MuvissSpacing.xs),
                )
            }
            items(group.rows, key = { it.episode.id.toString() }) { row ->
                UpcomingRow(row, onClick = { onOpenDetail(row.mediaId) })
            }
        }
    }
}

/** Agenda row: amber date block left (mock's "18 JUL" treatment), episode info right. */
@Composable
private fun UpcomingRow(
    row: UpcomingRow,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable(onClick = onClick).padding(MuvissSpacing.s),
        horizontalArrangement = Arrangement.spacedBy(MuvissSpacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            row.dateLabel,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(64.dp),
        )
        PosterImage(
            url = row.posterUrl,
            title = row.title,
            modifier = Modifier.width(40.dp).height(60.dp).clip(MaterialTheme.shapes.extraSmall),
            size = PosterSize.Thumbnail,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(row.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "S${row.episode.seasonNumber}E${row.episode.episodeNumber} · ${row.episode.name}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun UpcomingBucket.label(): String = when (this) {
    UpcomingBucket.TODAY -> "Today"
    UpcomingBucket.THIS_WEEK -> "This week"
    UpcomingBucket.LATER -> "Later"
}
