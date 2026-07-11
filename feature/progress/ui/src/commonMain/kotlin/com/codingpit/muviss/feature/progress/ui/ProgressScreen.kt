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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codingpit.muviss.core.designsystem.component.PosterImage
import com.codingpit.muviss.models.MediaId

/** The two segments the Progress tab switches between (EPIC 14 adds [UPCOMING] alongside the original watch-next view). */
private enum class ProgressTab {
    WATCH_NEXT,
    UPCOMING,
}

private fun ProgressTab.label(): String = when (this) {
    ProgressTab.WATCH_NEXT -> "Watch Next"
    ProgressTab.UPCOMING -> "Upcoming"
}

/**
 * The Progress tab's root: a segmented switch between "Watch Next" and
 * "Upcoming" (EPIC 14) rather than a sixth bottom-nav destination — both are
 * views over the same saved-shows episode data, just sliced differently.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgressScreen(
    watchNextViewModel: ProgressViewModel,
    upcomingViewModel: UpcomingViewModel,
    onOpenDetail: (MediaId) -> Unit,
) {
    var selectedTab by remember { mutableStateOf(ProgressTab.WATCH_NEXT) }

    Column(Modifier.fillMaxSize()) {
        ProgressTabs(selectedTab, onSelect = { selectedTab = it })
        when (selectedTab) {
            ProgressTab.WATCH_NEXT -> WatchNextScreen(watchNextViewModel, onOpenDetail)
            ProgressTab.UPCOMING -> UpcomingScreen(upcomingViewModel, onOpenDetail)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProgressTabs(selected: ProgressTab, onSelect: (ProgressTab) -> Unit) {
    val tabs = ProgressTab.entries
    PrimaryScrollableTabRow(selectedTabIndex = tabs.indexOf(selected)) {
        tabs.forEach { tab ->
            Tab(
                selected = tab == selected,
                onClick = { onSelect(tab) },
                text = { Text(tab.label()) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WatchNextScreen(
    viewModel: ProgressViewModel,
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

                state.error != null -> ProgressErrorState(state.error!!, onRetry = viewModel::refresh)

                state.items.isEmpty() -> Text(
                    "Nothing to watch next — add a show to your Library and start watching to see it here.",
                    modifier = Modifier.padding(top = 32.dp, start = 24.dp, end = 24.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )

                else -> WatchNextList(state.items, onTick = viewModel::tickNext, onOpenDetail = onOpenDetail)
            }
        }
    }
}

@Composable
private fun ProgressErrorState(message: String, onRetry: () -> Unit) {
    Column(
        Modifier.padding(top = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message, style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onRetry) { Text("Retry") }
    }
}

@Composable
private fun WatchNextList(
    items: List<WatchNextItem>,
    onTick: (WatchNextItem) -> Unit,
    onOpenDetail: (MediaId) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(12.dp),
    ) {
        items(items, key = { it.mediaId.toString() }) { item ->
            WatchNextRow(item, onTick = { onTick(item) }, onClick = { onOpenDetail(item.mediaId) })
        }
    }
}

@Composable
private fun WatchNextRow(
    item: WatchNextItem,
    onTick: () -> Unit,
    onClick: () -> Unit,
) {
    Surface(shape = RoundedCornerShape(8.dp), tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.clickable(onClick = onClick).padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PosterImage(
                url = item.posterUrl,
                title = item.title,
                modifier = Modifier.width(56.dp).height(84.dp).clip(RoundedCornerShape(6.dp)),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val episode = item.nextEpisode
                if (episode != null) {
                    Text(
                        "S${episode.seasonNumber}E${episode.episodeNumber} · ${episode.name}",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Text("Caught up — waiting on new episodes", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (item.nextEpisode != null) {
                Button(onClick = onTick) { Text("Mark seen") }
            }
        }
    }
}
