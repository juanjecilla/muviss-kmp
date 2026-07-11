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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.codingpit.muviss.core.designsystem.component.PosterImage
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
                state.loading -> CircularProgressIndicator(Modifier.padding(top = 32.dp))
                state.error != null -> UpcomingErrorState(state.error!!, onRetry = viewModel::refresh)
                state.groups.isEmpty() -> UpcomingEmptyState(hasLibraryEntries = state.hasLibraryEntries)
                else -> UpcomingList(state.groups, onOpenDetail)
            }
        }
    }
}

@Composable
private fun UpcomingErrorState(message: String, onRetry: () -> Unit) {
    Column(
        Modifier.padding(top = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message, style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onRetry) { Text("Retry") }
    }
}

@Composable
private fun UpcomingEmptyState(hasLibraryEntries: Boolean) {
    val message = if (hasLibraryEntries) {
        "Nothing upcoming — every saved show is either finished or has no scheduled episodes yet."
    } else {
        "Nothing here yet — save a show to your Library to see its upcoming episodes."
    }
    Text(
        message,
        modifier = Modifier.padding(top = 32.dp, start = 24.dp, end = 24.dp),
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun UpcomingList(
    groups: List<UpcomingUiGroup>,
    onOpenDetail: (MediaId) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(12.dp),
    ) {
        groups.forEach { group ->
            item(key = "header-${group.bucket}") {
                Text(
                    group.bucket.label(),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                )
            }
            items(group.rows, key = { it.episode.id.toString() }) { row ->
                UpcomingRow(row, onClick = { onOpenDetail(row.mediaId) })
            }
        }
    }
}

@Composable
private fun UpcomingRow(
    row: UpcomingRow,
    onClick: () -> Unit,
) {
    Surface(shape = RoundedCornerShape(8.dp), tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.clickable(onClick = onClick).padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PosterImage(
                url = row.posterUrl,
                title = row.title,
                modifier = Modifier.width(56.dp).height(84.dp).clip(RoundedCornerShape(6.dp)),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(row.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "S${row.episode.seasonNumber}E${row.episode.episodeNumber} · ${row.episode.name}",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(row.dateLabel, style = MaterialTheme.typography.labelMedium)
        }
    }
}

private fun UpcomingBucket.label(): String = when (this) {
    UpcomingBucket.TODAY -> "Today"
    UpcomingBucket.THIS_WEEK -> "This week"
    UpcomingBucket.LATER -> "Later"
}
